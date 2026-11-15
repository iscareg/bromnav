package nl.bromnav

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Single-screen UI built for a phone in a bike holder:
 *  - nothing essential in the four corners (holder clamps may cover them),
 *  - a centred bottom card with only the next stop, speed, speed multiplier and wanted-level stars,
 *  - within 200 m of a stop the card turns into a huge house number with a left/right arrow,
 *  - search, stop list and the menu live behind centred icons.
 */
class MainActivity : AppCompatActivity(), LocationListener {

    private val TAG = "BromNav"
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor() // routing (one BRouter at a time)
    private val net = Executors.newSingleThreadExecutor()    // geocoding

    private lateinit var store: RunStore
    private lateinit var speed: SpeedModel
    private lateinit var history: History
    private lateinit var voice: VoiceGuide
    private var engine: BRouterEngine? = null
    private var planner: Planner? = null

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null

    // --- views
    private lateinit var banner: LinearLayout
    private lateinit var arrowTv: TextView
    private lateinit var instrTv: TextView
    private lateinit var subTv: TextView
    private lateinit var listCard: LinearLayout
    private lateinit var stopsRv: RecyclerView
    private lateinit var infoCard: LinearLayout
    private lateinit var normalBox: LinearLayout
    private lateinit var streetTv: TextView
    private lateinit var numberTv: TextView
    private lateinit var detailTv: TextView
    private lateinit var starsTv: TextView
    private lateinit var speedTv: TextView
    private lateinit var factorTv: TextView
    private lateinit var bigBox: LinearLayout
    private lateinit var bigLeftTv: TextView
    private lateinit var bigNumTv: TextView
    private lateinit var bigRightTv: TextView
    private lateinit var bigSubTv: TextView
    private lateinit var mainBtn: Button
    private val adapter = StopsAdapter()

    // --- state
    private enum class Mode { PLAN, NAV }
    private var mode = Mode.PLAN
    private var listOpen = false
    private var lastFix: Location? = null
    private var follow = true
    private var navigator: Navigator? = null
    private var legTarget: Stop? = null      // null = back to the restaurant
    private var legStartMs = 0L
    private var legPredictedS = 0.0
    private var legDistM = 0.0
    private var rerouting = false
    private var arrivedSaid = false
    private var sideSaid = false
    private var lastProgress: Navigator.Progress? = null
    private var etaByStop: Map<Long, Long> = emptyMap()
    private var lastEtaCalc = 0L
    private var runStartMs = 0L              // 0 = no lap in progress
    private var lapDeliveries = 0
    private var legOdoM = 0.0
    private var lapOdoM = 0.0
    private var odoFix: Location? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapLibre.getInstance(this)
        store = RunStore(this)
        speed = SpeedModel(this)
        history = History(this)
        voice = VoiceGuide(this)

        val files = getExternalFilesDir(null)!!
        val segDir = File(files, "segments")
        val tiles = File(files, "tiles/groningen.pmtiles")
        segDir.mkdirs(); tiles.parentFile?.mkdirs()
        val haveData = segDir.listFiles { f -> f.name.endsWith(".rd5") }?.isNotEmpty() == true && tiles.exists()
        if (!haveData) { showMissingData(files); return }

        engine = BRouterEngine(segDir, installProfiles())
        planner = Planner(engine!!, speed)

        buildUi(savedInstanceState, tiles)
        requestLocation()
        refreshAll()
        worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume(); if (::infoCard.isInitialized) refreshAll() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }
    override fun onDestroy() {
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        mapView?.onDestroy(); voice.shutdown(); worker.shutdownNow(); net.shutdownNow()
        super.onDestroy()
    }

    private fun installProfiles(): File {
        val dir = File(filesDir, "profiles"); dir.mkdirs()
        for (name in assets.list("profiles") ?: emptyArray()) {
            assets.open("profiles/$name").use { inp -> File(dir, name).outputStream().use { inp.copyTo(it) } }
        }
        return File(dir, "bromfiets.brf")
    }

    private fun showMissingData(files: File) {
        setContentView(TextView(this).apply {
            setPadding(dp(32), dp(48), dp(32), dp(24)); textSize = 16f
            text = "BromNav needs its offline data.\n\nFrom the PC run:\n\n  ~/bromnav/tools/push_data.sh\n\n" +
                "It copies the routing data (segments/*.rd5) and the map (tiles/groningen.pmtiles) to\n\n${files.absolutePath}\n\nthen restart the app."
        })
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun card(color: Int = Color.WHITE, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun tv(size: Float, color: String = "#1b1b1b", bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(Color.parseColor(color)); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun iconBtn(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label; textSize = 22f; setPadding(0, 0, 0, 0)
        background = card(Color.WHITE, 28); elevation = dp(4).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(5), 0, dp(5), 0) }
        setOnClickListener { onClick(it) }
    }

    private fun buildUi(saved: Bundle?, tiles: File) {
        val side = dp(34)  // keep clear of the holder clamps at the corners
        val root = FrameLayout(this)
        val mv = MapView(this); mapView = mv
        root.addView(mv, FrameLayout.LayoutParams(-1, -1))
        mv.onCreate(saved)

        // --- top: turn banner (centred)
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = card(Color.parseColor("#14335e")); setPadding(dp(14), dp(10), dp(14), dp(10)); visibility = View.GONE
        }
        arrowTv = tv(40f, "#ffffff").apply { minWidth = dp(54); gravity = Gravity.CENTER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        instrTv = tv(20f, "#ffffff", true); subTv = tv(14f, "#cfe0ff")
        col.addView(instrTv); col.addView(subTv)
        banner.addView(arrowTv); banner.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(side, dp(14), side, 0) })

        // --- recenter: middle of the right edge
        root.addView(Button(this).apply {
            text = "◎"; textSize = 22f; background = card(Color.WHITE, 28); elevation = dp(4).toFloat()
            setOnClickListener { follow = true; lastFix?.let { moveCamera(it, mode == Mode.NAV) } ?: showRestaurant() }
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })

        // --- bottom column: [stop list card] [info card] [icon row]
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        listCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); elevation = dp(6).toFloat()
            setPadding(dp(8), dp(6), dp(8), dp(6)); visibility = View.GONE
        }
        stopsRv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = this@MainActivity.adapter }
        listCard.addView(stopsRv, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(listCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); elevation = dp(6).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { toggleList() }
        }
        // normal content: street + number, details, variables
        normalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val addrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        streetTv = tv(20f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        numberTv = tv(30f, "#14335e", true).apply { setPadding(dp(8), 0, 0, 0) }
        addrRow.addView(streetTv, LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
        addrRow.addView(numberTv)
        detailTv = tv(13f, "#666666").apply { gravity = Gravity.CENTER }
        val varsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        speedTv = tv(22f, "#1b1b1b", true).apply { text = "– km/h" }
        factorTv = tv(16f, "#555555").apply { setPadding(dp(14), 0, dp(14), 0) }
        starsTv = tv(24f, "#888888").apply { text = "☆☆☆☆☆" }
        varsRow.addView(speedTv); varsRow.addView(factorTv); varsRow.addView(starsTv)
        normalBox.addView(addrRow, LinearLayout.LayoutParams(-1, -2))
        normalBox.addView(detailTv, LinearLayout.LayoutParams(-1, -2))
        normalBox.addView(varsRow, LinearLayout.LayoutParams(-1, -2))
        infoCard.addView(normalBox, LinearLayout.LayoutParams(-1, -2))
        // "almost there" content: huge house number + side arrow
        bigBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
        val bigRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bigLeftTv = tv(64f, "#e8900c", true).apply { text = "◀" }
        bigNumTv = tv(84f, "#14335e", true).apply { setPadding(dp(12), 0, dp(12), 0); includeFontPadding = false }
        bigRightTv = tv(64f, "#e8900c", true).apply { text = "▶" }
        bigRow.addView(bigLeftTv); bigRow.addView(bigNumTv); bigRow.addView(bigRightTv)
        bigSubTv = tv(16f, "#444444").apply { gravity = Gravity.CENTER }
        bigBox.addView(bigRow); bigBox.addView(bigSubTv, LinearLayout.LayoutParams(-1, -2))
        infoCard.addView(bigBox, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(infoCard, LinearLayout.LayoutParams(-1, -2))

        // icon row: 🔍  📋  [ main action ]  ☰   (centred)
        val icons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        icons.addView(iconBtn("🔍") { openSearch() })
        icons.addView(iconBtn("📋") { toggleList() })
        mainBtn = Button(this).apply {
            isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = card(Color.parseColor("#1f6fe5"), 28); elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(56)).apply { setMargins(dp(5), 0, dp(5), 0) }
            setOnClickListener { if (mode == Mode.PLAN) startRun() else delivered() }
        }
        icons.addView(mainBtn)
        icons.addView(iconBtn("☰") { openMenu(it) })
        bottom.addView(icons, LinearLayout.LayoutParams(-1, -2))

        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(side, 0, side, dp(34)) })
        setContentView(root)

        mv.getMapAsync { m ->
            map = m
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.setMinZoomPreference(9.0); m.setMaxZoomPreference(19.0)
            m.setStyle(Style.Builder().fromJson(MapStyle.json(tiles))) { s -> style = s; showRestaurant(); refreshAll() }
            m.addOnMapLongClickListener { ll -> onLongPress(LatLon(ll.latitude, ll.longitude)); true }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
        }
    }

    private fun showRestaurant() {
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(Config.RESTAURANT.lat, Config.RESTAURANT.lon), 14.0))
    }

    private fun toggleList() { listOpen = !listOpen; refreshAll() }

    private fun openMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Add address…")
        pm.menu.add(0, 2, 0, "Stats & history")
        pm.menu.add(0, 3, 0, if (voice.enabled) "Voice: on (tap to mute)" else "Voice: off (tap to enable)")
        if (mode == Mode.NAV) {
            if (legTarget != null) pm.menu.add(0, 4, 0, "Skip this stop (do it later)")
            pm.menu.add(0, 5, 0, "End navigation")
        } else pm.menu.add(0, 6, 0, "Clear all stops")
        pm.menu.add(0, 7, 0, "Remove all avoid marks")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openSearch()
                2 -> startActivity(Intent(this, StatsActivity::class.java))
                3 -> voice.enabled = !voice.enabled
                4 -> skip()
                5 -> endNav()
                6 -> confirmClear()
                7 -> { store.nogos.clear(); store.save(); refreshAll(); if (mode == Mode.NAV) reroute("Avoid marks removed") }
            }
            true
        }
        pm.show()
    }

    // ------------------------------------------------------------------ search / stops

    private fun openSearch() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val et = EditText(this).apply { hint = "Street + number, or postcode + number"; isSingleLine = true; textSize = 18f }
        val results = ArrayList<PdokGeocoder.Address>()
        val la = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val lv = ListView(this).apply { adapter = la }
        box.addView(et); box.addView(lv, LinearLayout.LayoutParams(-1, dp(320)))
        val dlg = AlertDialog.Builder(this).setTitle("Add stop").setView(box).setNegativeButton("Close", null).create()
        var seq = 0
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""; val my = ++seq
                if (q.length < 3) return
                ui.postDelayed({
                    if (my != seq) return@postDelayed
                    net.execute {
                        val res = try { PdokGeocoder.search(q) } catch (e: Exception) { Log.w(TAG, "search", e); null }
                        ui.post {
                            if (my != seq) return@post
                            if (res == null) { toast("Address search needs mobile data"); return@post }
                            results.clear(); results.addAll(res)
                            la.clear(); la.addAll(res.map { it.label.removeSuffix(" Groningen") }); la.notifyDataSetChanged()
                        }
                    }
                }, 350)
            }
        })
        lv.setOnItemClickListener { _, _, pos, _ ->
            val a = results[pos]; dlg.dismiss()
            addStop(Stop(System.currentTimeMillis(), a.label.removeSuffix(" Groningen"), a.pos, street = a.street, houseNo = a.houseNo))
        }
        dlg.show()
        et.requestFocus()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun addStop(s: Stop, ask: Boolean = true) {
        store.stops.add(s); store.save()
        refreshAll()
        worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
        if (ask) editStop(s)
    }

    private fun editStop(s: Stop) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val orderEt = EditText(this).apply { hint = "Order number"; setText(s.orderNo); inputType = InputType.TYPE_CLASS_TEXT }
        val timeEt = EditText(this).apply {
            hint = "Deliver before (HH:MM)"; inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            s.deadline?.let { setText(hm(it)) }
        }
        box.addView(orderEt); box.addView(timeEt)
        AlertDialog.Builder(this).setTitle(s.label).setView(box)
            .setPositiveButton("Save") { _, _ ->
                s.orderNo = orderEt.text.toString().trim()
                s.deadline = parseHm(timeEt.text.toString())
                store.save(); lastEtaCalc = 0; refreshAll()
            }
            .setNegativeButton("Delete") { _, _ -> store.stops.remove(s); store.save(); refreshAll() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setMessage("Remove all stops of this run?")
            .setPositiveButton("Clear") { _, _ -> store.stops.clear(); store.save(); refreshAll() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun onLongPress(p: LatLon) {
        val items = arrayOf("Add stop here", "Avoid here (not allowed for bromfiets)", "Remove avoid marks nearby")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> addStop(Stop(System.currentTimeMillis(), "Pin %.5f, %.5f".format(Locale.US, p.lat, p.lon), p, street = "Pin", houseNo = "📍"))
                1 -> { store.nogos.add(NoGo(p, 15)); store.save(); refreshAll(); if (mode == Mode.NAV) reroute("Avoiding that spot") }
                2 -> { store.nogos.removeAll { Geo.dist(it.p, p) < 60 }; store.save(); refreshAll() }
            }
        }.show()
    }

    // ------------------------------------------------------------------ run / navigation

    private fun here(): LatLon? = lastFix?.let { LatLon(it.latitude, it.longitude) }

    private fun startRun() {
        val pending = store.pending()
        if (pending.isEmpty()) { openSearch(); return }
        val start = here()?.takeIf { Geo.dist(it, Config.RESTAURANT) < 30_000 } ?: Config.RESTAURANT
        mainBtn.text = "Planning…"
        worker.execute {
            val t0 = System.currentTimeMillis()
            val plan = try { planner!!.optimise(start, pending, store.nogos) } catch (e: Exception) {
                Log.e(TAG, "optimise", e); ui.post { toast("Planning failed: ${e.message}"); refreshAll() }; return@execute
            }
            Log.i(TAG, "optimised ${pending.size} stops in ${System.currentTimeMillis() - t0} ms")
            ui.post {
                val delivered = store.stops.filter { it.delivered }
                store.stops.clear(); store.stops.addAll(delivered); store.stops.addAll(plan.order); store.save()
                mode = Mode.NAV
                if (runStartMs == 0L) { runStartMs = System.currentTimeMillis(); lapDeliveries = 0; lapOdoM = 0.0 }
                startLeg()
            }
        }
    }

    private fun startLeg() {
        legTarget = store.pending().firstOrNull()
        val target = legTarget?.pos ?: Config.RESTAURANT
        val from = here() ?: Config.RESTAURANT
        arrivedSaid = false; sideSaid = false; lastProgress = null
        banner.visibility = View.VISIBLE
        instrTv.text = "Calculating route…"; subTv.text = ""; arrowTv.text = "…"
        refreshAll()
        worker.execute {
            val t0 = System.currentTimeMillis()
            val r = try { engine!!.route(listOf(from, target), store.nogos) } catch (e: Exception) {
                Log.e(TAG, "route", e); ui.post { instrTv.text = "No route: ${e.message}" }; return@execute
            }
            Log.i(TAG, "route ${r.lengthM} m / ${r.timeS} s computed in ${System.currentTimeMillis() - t0} ms")
            ui.post {
                setRoute(r)
                legStartMs = System.currentTimeMillis(); legOdoM = 0.0
                legPredictedS = r.timeS.toDouble(); legDistM = r.lengthM.toDouble()
                val mins = Math.round(r.timeS * speed.factor(legDistM) / 60.0)
                voice.say(legTarget?.let { "Going to ${spokenStop(it)}, $mins minutes" } ?: "Heading back to ${Config.RESTAURANT_NAME}, $mins minutes")
                follow = true
                lastFix?.let { onLocationChanged(it) }
            }
        }
    }

    private fun spokenStop(s: Stop) = (if (s.orderNo.isNotEmpty()) "order ${s.orderNo}, " else "") + "${s.streetName()} ${s.number()}"

    private fun setRoute(r: Route) {
        navigator = Navigator(r); voice.newRoute(r); rerouting = false
        drawRoute(r); refreshAll()
    }

    private fun reroute(reason: String) {
        if (rerouting || mode != Mode.NAV) return
        val from = here() ?: return
        rerouting = true
        instrTv.text = reason
        val target = legTarget?.pos ?: Config.RESTAURANT
        worker.execute {
            val r = runCatching { engine!!.route(listOf(from, target), store.nogos) }.getOrNull()
            ui.post { if (r != null && mode == Mode.NAV) { setRoute(r); voice.say("Route recalculated") } else rerouting = false }
        }
    }

    private fun delivered() {
        val t = legTarget
        val now = System.currentTimeMillis()
        if (t == null) {
            if (runStartMs > 0) history.addLap(LapRecord(runStartMs, runStartMs, now, lapDeliveries, lapOdoM))
            runStartMs = 0
            voice.say("Run complete"); endNav(); return
        }
        val actual = (now - legStartMs) / 1000.0
        speed.record(legDistM, legPredictedS, actual)
        history.addOrder(OrderRecord(runStartMs, t.orderNo, t.label, if (legOdoM > 100) legOdoM else legDistM, legPredictedS, actual, t.deadline, now))
        lapDeliveries++
        t.delivered = true; store.save()
        startLeg()
    }

    private fun skip() {
        val t = legTarget ?: return
        store.stops.remove(t); store.stops.add(t); store.save()
        startLeg()
    }

    private fun endNav() {
        mode = Mode.PLAN; navigator = null; legTarget = null; lastProgress = null
        store.stops.removeAll { it.delivered }; store.save()
        banner.visibility = View.GONE
        drawRoute(null); refreshAll()
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().tilt(0.0).bearing(0.0).padding(0.0, 0.0, 0.0, 0.0).build()))
    }

    // ------------------------------------------------------------------ location

    private fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        startGps()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startGps()
        else toast("Location permission is needed for navigation")
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, Looper.getMainLooper())
        (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { onLocationChanged(it) }
    }

    override fun onLocationChanged(loc: Location) {
        val prev = lastFix
        if (loc.provider == LocationManager.NETWORK_PROVIDER && prev != null &&
            prev.provider == LocationManager.GPS_PROVIDER && loc.time - prev.time < 10_000) return
        lastFix = loc
        val p = LatLon(loc.latitude, loc.longitude)
        if (loc.provider == LocationManager.GPS_PROVIDER) {
            speedTv.text = if (loc.hasSpeed()) "${Math.round(loc.speed * 3.6)} km/h" else "– km/h"
            if (loc.accuracy < 25) {
                odoFix?.let { o -> val d = o.distanceTo(loc); if (d > 3 && d < 200) { legOdoM += d; lapOdoM += d } }
                odoFix = loc
            }
        }
        setGeoJson("me", """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]},"properties":{}}""")

        val nav = navigator
        if (mode == Mode.NAV && nav != null) {
            val pr = nav.update(p)
            lastProgress = pr
            showProgress(pr)
            voice.onProgress(pr, if (loc.hasSpeed()) loc.speed.toDouble() else 6.0)
            if (nav.offRoute && loc.accuracy < 30) reroute("Off route — recalculating…")
            val t = legTarget
            if (t != null && pr.remainingM < 150 && !sideSaid) {
                sideSaid = true
                val side = nav.destinationSide(t.pos)
                voice.say("Number ${t.number()} is " + (side?.let { "on your $it" } ?: "ahead"))
            }
            if (pr.remainingM < 30 && !arrivedSaid) {
                arrivedSaid = true
                voice.say(t?.let { "You have arrived at ${spokenStop(it)}" } ?: "You are back at ${Config.RESTAURANT_NAME}")
            }
            updateInfoCard()
            if (System.currentTimeMillis() - lastEtaCalc > 15_000) updateEtas()
        }
        if (follow && map != null) moveCamera(loc, mode == Mode.NAV)
    }

    private fun moveCamera(loc: Location, navStyle: Boolean) {
        val m = map ?: return
        val b = CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude))
        if (navStyle) {
            b.zoom(17.0).tilt(40.0)
            if (loc.hasBearing() && loc.speed > 2) b.bearing(loc.bearing.toDouble())
            b.padding(0.0, mapView!!.height * 0.30, 0.0, 0.0)
        } else b.zoom(maxOf(m.cameraPosition.zoom, 15.0))
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 800)
    }

    private fun showProgress(pr: Navigator.Progress) {
        val r = navigator?.route ?: return
        if (pr.remainingM < 30) {
            arrowTv.text = "⚑"
            instrTv.text = if (legTarget != null) "Arrived" else "Back at ${Config.RESTAURANT_NAME}"
            subTv.text = "Tap ${if (legTarget == null) "Done" else "Delivered"} when finished"
            return
        }
        val h = pr.nextHint
        val t = pr.nextTransition
        val useTransition = t != null && (h == null || pr.distToTransitionM < pr.distToHintM - 20)
        if (useTransition) {
            arrowTv.text = if (t!!.to == WayClass.MOPED_PATH) "🛵" else "🛣"
            instrTv.text = "${Navigator.fmtDist(pr.distToTransitionM)}  ${Navigator.describeTransition(t).replaceFirstChar { it.uppercase() }}"
        } else if (h != null) {
            val (arrow, text) = Navigator.describe(h)
            arrowTv.text = arrow
            val name = r.segmentAt(h.idx)?.name
            instrTv.text = "${Navigator.fmtDist(pr.distToHintM)}  $text" + (if (name != null && h.cmd !in 13..14) " · $name" else "")
        } else {
            arrowTv.text = "↑"; instrTv.text = "Continue ${Navigator.fmtDist(pr.remainingM)}"
        }
        subTv.text = when (pr.currentClass) { WayClass.MOPED_PATH -> "on moped path"; WayClass.ROAD -> "on road"; else -> "" }
    }

    // ------------------------------------------------------------------ info card

    private fun updateInfoCard() {
        if (!::infoCard.isInitialized) return
        val pending = store.pending()
        val next = if (mode == Mode.NAV) legTarget else pending.firstOrNull()
        val pr = lastProgress
        factorTv.text = "×%.2f".format(speed.factor(pr?.remainingM ?: 2000.0))

        // within 200 m of the stop: huge house number + side arrow
        val nav = navigator
        if (mode == Mode.NAV && next != null && pr != null && nav != null && pr.remainingM < 200) {
            normalBox.visibility = View.GONE; bigBox.visibility = View.VISIBLE
            val side = nav.destinationSide(next.pos)
            bigLeftTv.visibility = if (side == "left") View.VISIBLE else View.INVISIBLE
            bigRightTv.visibility = if (side == "right") View.VISIBLE else View.INVISIBLE
            bigNumTv.text = next.number().ifEmpty { "?" }
            bigSubTv.text = "${next.streetName()} · ${Navigator.fmtDist(pr.remainingM)}" +
                (if (next.orderNo.isNotEmpty()) " · #${next.orderNo}" else "") +
                (side?.let { " · on the $it" } ?: "")
            return
        }
        normalBox.visibility = View.VISIBLE; bigBox.visibility = View.GONE
        when {
            mode == Mode.NAV && next == null -> {
                streetTv.text = Config.RESTAURANT_NAME; numberTv.text = "🏠"
                detailTv.text = "All delivered — heading back" + (pr?.let { " · ${Navigator.fmtDist(it.remainingM)}" } ?: "")
            }
            next == null -> { streetTv.text = "No stops yet"; numberTv.text = ""; detailTv.text = "Tap 🔍 to add an address, or long-press the map" }
            else -> {
                streetTv.text = next.streetName(); numberTv.text = next.number()
                val parts = ArrayList<String>()
                if (next.orderNo.isNotEmpty()) parts.add("#${next.orderNo}")
                if (mode == Mode.NAV && pr != null) {
                    val etaS = pr.remainingS * speed.factor(pr.remainingM)
                    parts.add(Navigator.fmtDist(pr.remainingM)); parts.add("ETA ${hm(System.currentTimeMillis() + (etaS * 1000).toLong())}")
                } else etaByStop[next.id]?.let { parts.add("ETA ${hm(it)}") }
                next.deadline?.let { parts.add("due ${hm(it)}") }
                if (pending.size > 1) parts.add("+${pending.size - 1} more")
                detailTv.text = parts.joinToString(" · ")
            }
        }
        updateStars()
    }

    // ------------------------------------------------------------------ ETAs / urgency

    private fun updateEtas() {
        lastEtaCalc = System.currentTimeMillis()
        val pending = store.pending().toList()
        if (pending.isEmpty()) { etaByStop = emptyMap(); return }
        val nogos = store.nogos.toList()
        val pr = lastProgress
        val start = here() ?: Config.RESTAURANT
        val target = legTarget
        val navMode = mode == Mode.NAV
        worker.execute {
            val first = if (navMode && pr != null && target == pending.first()) pr.remainingS * speed.factor(pr.remainingM)
            else planner!!.leg(start, pending.first().pos, nogos).let { it.timeS * speed.factor(it.distM) }
            val etas = planner!!.etas(pending, first, nogos)
            val m = pending.mapIndexed { i, s -> s.id to etas[i] }.toMap()
            ui.post { etaByStop = m; adapter.notifyDataSetChanged(); updateInfoCard(); drawStops() }
        }
    }

    private fun slackMin(s: Stop): Long? {
        val d = s.deadline ?: return null
        val eta = etaByStop[s.id] ?: return null
        return (d - eta) / 60_000
    }

    /** 0..5 wanted-level stars for one stop, or null without deadline/ETA. */
    private fun starsOf(s: Stop, now: Long = System.currentTimeMillis()): Int? {
        val d = s.deadline ?: return null
        val eta = etaByStop[s.id] ?: return null
        return Urgency.stars((eta - now) / 1000.0, (d - now) / 1000.0)
    }

    private fun urgencyColor(s: Stop): Int = when (starsOf(s)) {
        null -> Color.parseColor("#1f6fe5")
        0, 1 -> Color.parseColor("#1e9e4a")
        2, 3 -> Color.parseColor("#e8900c")
        4 -> Color.parseColor("#e5560c")
        else -> Color.parseColor("#d93025")
    }

    /** Stars for the most urgent pending order (the current one or a later one). */
    private fun updateStars() {
        val pending = store.pending()
        val best = pending.mapNotNull { s -> starsOf(s)?.let { s to it } }.maxByOrNull { it.second }
        if (best == null) { starsTv.text = "☆☆☆☆☆"; starsTv.setTextColor(Color.parseColor("#bbbbbb")); return }
        val (s, n) = best
        starsTv.text = "★".repeat(n) + "☆".repeat(5 - n)
        starsTv.setTextColor(when { n >= 5 -> Color.parseColor("#d93025"); n >= 3 -> Color.parseColor("#e8900c"); else -> Color.parseColor("#e0b000") })
        // if a later order is the urgent one, say which
        if (s != (if (mode == Mode.NAV) legTarget else pending.firstOrNull()) && n > 0)
            detailTv.text = "${detailTv.text}  ⚠ stop ${pending.indexOf(s) + 1}" + (if (s.orderNo.isNotEmpty()) " #${s.orderNo}" else "")
    }

    private fun refreshAll() {
        if (!::infoCard.isInitialized) return
        mainBtn.text = when {
            mode == Mode.PLAN && store.pending().isEmpty() -> "+ Add"
            mode == Mode.PLAN -> "Go ▶"
            legTarget == null -> "Done ✓"
            else -> "Delivered ✓"
        }
        mainBtn.background = card(Color.parseColor(if (mode == Mode.NAV) "#1e9e4a" else "#1f6fe5"), 28)
        listCard.visibility = if (listOpen && store.pending().isNotEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        stopsRv.layoutParams = stopsRv.layoutParams.apply {
            height = if (store.pending().size > 4) dp(4 * 52) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        updateInfoCard(); drawStops()
        if (System.currentTimeMillis() - lastEtaCalc > 2000) updateEtas()
    }

    // ------------------------------------------------------------------ map drawing

    private fun setGeoJson(source: String, json: String) { style?.getSourceAs<GeoJsonSource>(source)?.setGeoJson(json) }

    private fun drawRoute(r: Route?) {
        if (r == null) { setGeoJson("route", """{"type":"FeatureCollection","features":[]}"""); return }
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        val segs = r.segs.ifEmpty { listOf(Segment(0, r.pts.size - 1, emptyMap())) }
        segs.forEachIndexed { k, s ->
            if (k > 0) sb.append(',')
            sb.append("""{"type":"Feature","properties":{"cls":"${if (s.cls == WayClass.MOPED_PATH) "path" else "road"}"},"geometry":{"type":"LineString","coordinates":[""")
            for (i in s.startIdx..s.endIdx) { if (i > s.startIdx) sb.append(','); sb.append('[').append(r.pts[i].lon).append(',').append(r.pts[i].lat).append(']') }
            sb.append("]}}")
        }
        sb.append("]}")
        setGeoJson("route", sb.toString())
    }

    private fun drawStops() {
        val f = ArrayList<String>()
        fun pt(p: LatLon, n: String, color: String) =
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]},"properties":{"n":"$n","color":"$color"}}"""
        f.add(pt(Config.RESTAURANT, "W", "#202020"))
        store.pending().forEachIndexed { i, s -> f.add(pt(s.pos, "${i + 1}", String.format("#%06X", 0xFFFFFF and urgencyColor(s)))) }
        setGeoJson("stops", """{"type":"FeatureCollection","features":[${f.joinToString(",")}]}""")
        setGeoJson("nogos", """{"type":"FeatureCollection","features":[${store.nogos.joinToString(",") { pt(it.p, "", "#d93025") }}]}""")
    }

    // ------------------------------------------------------------------ helpers

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun parseHm(s: String): Long? {
        val m = Regex("""^\s*(\d{1,2})[:.]?(\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        // a time more than 6 h in the past means "tomorrow" (shift past midnight)
        if (c.timeInMillis < System.currentTimeMillis() - 6 * 3600_000L) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    // ------------------------------------------------------------------ stop list

    private inner class StopsAdapter : RecyclerView.Adapter<StopsAdapter.VH>() {
        inner class VH(val row: LinearLayout, val badge: TextView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(5), dp(6), dp(5)); layoutParams = RecyclerView.LayoutParams(-1, dp(52))
            }
            val badge = TextView(parent.context).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; textSize = 15f }
            val col = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            val title = TextView(parent.context).apply { textSize = 15f; setTextColor(Color.parseColor("#1b1b1b")); isSingleLine = true }
            val sub = TextView(parent.context).apply { textSize = 13f; setTextColor(Color.parseColor("#666666")); isSingleLine = true }
            col.addView(title); col.addView(sub)
            row.addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            return VH(row, badge, title, sub)
        }

        override fun getItemCount() = store.pending().size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = store.pending()[position]
            h.badge.text = "${position + 1}"
            h.badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(urgencyColor(s)) }
            h.title.text = (if (s.orderNo.isNotEmpty()) "#${s.orderNo}  " else "") + s.label
            val parts = ArrayList<String>()
            etaByStop[s.id]?.let { parts.add("ETA ${hm(it)}") }
            s.deadline?.let { parts.add("due ${hm(it)}") }
            starsOf(s)?.let { parts.add("★".repeat(it) + "☆".repeat(5 - it)) }
            slackMin(s)?.let { parts.add(if (it >= 0) "+$it min" else "${-it} min LATE") }
            if (parts.isEmpty()) parts.add("tap to set order no. / deadline")
            h.sub.text = parts.joinToString(" · ")
            h.row.setOnClickListener { editStop(s) }
        }
    }
}
