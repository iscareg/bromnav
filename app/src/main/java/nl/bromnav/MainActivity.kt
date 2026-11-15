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
    private lateinit var orderTv: TextView
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
    private lateinit var bigNoteTv: TextView
    private lateinit var mainBtn: Button
    private lateinit var recenterBtn: Button
    private lateinit var undoBar: LinearLayout
    private lateinit var undoTv: TextView
    private var undoAction: (() -> Unit)? = null
    private val hideUndo = Runnable { undoBar.visibility = View.GONE; undoAction = null }
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
        if (BuildConfig.DEBUG) DebugBridge.register(this)
        NoteTranslator.ensureModel(wifiOnly = true)
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
        if (BuildConfig.DEBUG) DebugBridge.unregister(this)
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
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(dp(4), 0, dp(4), 0) }
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
        recenterBtn = Button(this).apply {
            text = "◎"; textSize = 22f; background = card(Color.WHITE, 28); elevation = dp(4).toFloat()
            setOnClickListener { follow = true; lastFix?.let { moveCamera(it, mode == Mode.NAV) } ?: showRestaurant() }
        }
        root.addView(recenterBtn, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })

        // --- bottom column: [stop list card] [info card] [icon row]
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        listCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); elevation = dp(6).toFloat()
            setPadding(dp(8), dp(6), dp(8), dp(6)); visibility = View.GONE
        }
        orderTv = tv(13f, "#14335e", true).apply {
            gravity = Gravity.CENTER; setPadding(dp(6), dp(4), dp(6), dp(6))
            setOnClickListener { if (store.manualOrder) autoOrder() }
        }
        listCard.addView(orderTv, LinearLayout.LayoutParams(-1, -2))
        stopsRv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = this@MainActivity.adapter }
        listCard.addView(stopsRv, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(listCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); elevation = dp(6).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener {
                val t = legTarget
                if (bigBox.visibility == View.VISIBLE && t != null && t.phone.isNotEmpty())
                    startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${t.phone}")))
                else toggleList()
            }
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
        bigNoteTv = tv(11f, "#5b2a86").apply { gravity = Gravity.CENTER; maxLines = 4; setPadding(0, dp(3), 0, 0) }
        bigBox.addView(bigRow); bigBox.addView(bigSubTv, LinearLayout.LayoutParams(-1, -2)); bigBox.addView(bigNoteTv, LinearLayout.LayoutParams(-1, -2))
        infoCard.addView(bigBox, LinearLayout.LayoutParams(-1, -2))
        undoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = card(Color.parseColor("#F0202020"), 14); setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        undoTv = tv(15f, "#ffffff").apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        undoBar.addView(undoTv, LinearLayout.LayoutParams(0, -2, 1f))
        undoBar.addView(Button(this).apply {
            text = "UNDO"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#ffd54a"))
            background = null; setOnClickListener { val a = undoAction; hideUndo.run(); a?.invoke() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        bottom.addView(undoBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        bottom.addView(infoCard, LinearLayout.LayoutParams(-1, -2))

        // icon row: 🔍  📋  [ main action ]  ☰   (centred)
        val icons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        icons.addView(iconBtn("📷") { openScanner() })
        icons.addView(iconBtn("📋") { toggleList() })
        mainBtn = Button(this).apply {
            isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = card(Color.parseColor("#1f6fe5"), 28); elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(128), dp(52)).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { if (mode == Mode.PLAN) startRun() else delivered() }
        }
        icons.addView(mainBtn)
        icons.addView(iconBtn("☰") { openMenu(it) })
        bottom.addView(icons, LinearLayout.LayoutParams(-1, -2))

        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(side, 0, side, dp(44)) })
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

    private val scanLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data ?: return@registerForActivityResult
        val arr = org.json.JSONArray(data.getStringExtra(ScanActivity.EXTRA_STOPS) ?: "[]")
        for (i in 0 until arr.length()) {
            val st = Stop.fromJson(arr.getJSONObject(i))
            if (store.stops.none { !it.delivered && it.key() == st.key() }) store.stops.add(st)
        }
        if (arr.length() > 0) {
            store.save(); lastEtaCalc = 0; refreshAll()
            worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
            toast("${arr.length()} stop${if (arr.length() > 1) "s" else ""} added from receipts")
            if (mode == Mode.NAV && !store.manualOrder) autoOrder()
        }
        if (data.getBooleanExtra(ScanActivity.EXTRA_TYPE_INSTEAD, false)) openSearch()
    }

    private fun openScanner() {
        scanLauncher.launch(Intent(this, ScanActivity::class.java)
            .putExtra(ScanActivity.EXTRA_EXISTING, store.pending().map { it.key() }.toTypedArray()))
    }

    private fun toggleList() { listOpen = !listOpen; refreshAll() }

    private fun openMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Type an address…")
        pm.menu.add(0, 9, 0, "📷 Scan receipts…")
        pm.menu.add(0, 2, 0, "Stats & history")
        pm.menu.add(0, 3, 0, if (voice.enabled) "Voice: on (tap to mute)" else "Voice: off (tap to enable)")
        if (mode == Mode.NAV) {
            if (legTarget != null) pm.menu.add(0, 4, 0, "Skip this stop (do it later)")
            pm.menu.add(0, 5, 0, "End navigation")
        } else pm.menu.add(0, 6, 0, "Clear all stops")
        pm.menu.add(0, 7, 0, "Remove all avoid marks")
        if (BuildConfig.DEBUG && mode == Mode.NAV) pm.menu.add(0, 8, 0, if (simulating) "Stop simulation" else "Simulate ride (debug)")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openSearch()
                9 -> openScanner()
                2 -> startActivity(Intent(this, StatsActivity::class.java))
                3 -> voice.enabled = !voice.enabled
                4 -> skip()
                5 -> endNav()
                6 -> confirmClear()
                7 -> { store.nogos.clear(); store.save(); refreshAll(); if (mode == Mode.NAV) reroute("Avoid marks removed") }
                8 -> if (simulating) simulating = false else simulate()
            }
            true
        }
        pm.show()
    }

    // ------------------------------------------------------------------ search / stops

    private fun openSearch() {
        AddressSearch.show(this) { a ->
            addStop(Stop(System.currentTimeMillis(), a.label.removeSuffix(" Groningen"), a.pos, street = a.street, houseNo = a.houseNo))
        }
    }

    private fun addStop(s: Stop, ask: Boolean = true) {
        store.stops.add(s); store.save()
        refreshAll()
        worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
        if (ask) editStop(s)
    }

    /** Edit everything of an order that is already in the run (address, order no, time, phone, name, message). */
    private fun editStop(s: Stop) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        var newAddr: PdokGeocoder.Address? = null
        val addrBtn = Button(this).apply { isAllCaps = false; textSize = 15f; text = "📍 ${s.label}  (change)" }
        addrBtn.setOnClickListener {
            AddressSearch.show(this, "Change address", "${s.streetName()} ${s.number()}") { a -> newAddr = a; addrBtn.text = "📍 ${a.label.removeSuffix(" Groningen")}  (change)" }
        }
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply { this.hint = hint; setText(value); inputType = type }
        val orderEt = field("Order number", s.orderNo)
        val timeEt = field("Deliver before (HH:MM)", s.deadline?.let { hm(it) } ?: "", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        val phoneEt = field("Phone", s.phone, InputType.TYPE_CLASS_PHONE)
        val nameEt = field("Name (doorbell)", s.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        val noteEt = field("Message from customer", s.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { maxLines = 4; textSize = 14f }
        listOf<View>(addrBtn, orderEt, timeEt, phoneEt, nameEt, noteEt).forEach { box.addView(it) }
        if (s.phone.isNotEmpty()) box.addView(Button(this).apply {
            text = "📞 Call ${s.phone}"; isAllCaps = false
            setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${s.phone}"))) }
        })
        AlertDialog.Builder(this).setTitle(if (s.orderNo.isNotEmpty()) "Order #${s.orderNo}" else "Edit stop")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val moved = newAddr?.let { a ->
                    s.label = a.label.removeSuffix(" Groningen"); s.pos = a.pos; s.street = a.street; s.houseNo = a.houseNo; true
                } ?: false
                s.orderNo = orderEt.text.toString().trim()
                s.deadline = parseHm(timeEt.text.toString())
                s.phone = phoneEt.text.toString().trim(); s.name = nameEt.text.toString().trim()
                val noteText = noteEt.text.toString().trim()
                val noteChanged = noteText != s.note
                s.note = noteText
                store.save(); lastEtaCalc = 0; refreshAll()
                if (moved) {
                    worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
                    if (mode == Mode.NAV && s == legTarget) startLeg()
                }
                // a typed Dutch message gets an English line too
                if (noteChanged && noteText.isNotEmpty() && !noteText.contains("🇬🇧"))
                    NoteTranslator.translate(noteText) { en -> if (en != null) { s.note = "$noteText\n🇬🇧 $en"; store.save(); refreshAll() } }
            }
            .setNegativeButton("Delete") { _, _ -> store.stops.remove(s); store.save(); refreshAll() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setMessage("Remove all stops of this run?")
            .setPositiveButton("Clear") { _, _ -> store.stops.clear(); store.manualOrder = false; store.save(); refreshAll() }
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
        if (pending.isEmpty()) { openScanner(); return }
        val start = here()?.takeIf { Geo.dist(it, Config.RESTAURANT) < 30_000 } ?: Config.RESTAURANT
        if (store.manualOrder) {
            mode = Mode.NAV
            if (runStartMs == 0L) { runStartMs = System.currentTimeMillis(); lapDeliveries = 0; lapOdoM = 0.0 }
            startLeg(); return
        }
        mainBtn.text = "Planning…"
        worker.execute {
            val t0 = System.currentTimeMillis()
            val plan = try { planner!!.optimise(start, pending, store.nogos) } catch (e: Exception) {
                Log.e(TAG, "optimise", e); ui.post { toast("Planning failed: ${e.message}"); refreshAll() }; return@execute
            }
            Log.i(TAG, "optimised ${pending.size} stops in ${System.currentTimeMillis() - t0} ms")
            ui.post {
                // the user re-ordered by hand (or changed stops) while we were planning: their order wins
                if (!store.manualOrder && store.pending().toSet() == plan.order.toSet()) {
                    val delivered = store.stops.filter { it.delivered }
                    store.stops.clear(); store.stops.addAll(delivered); store.stops.addAll(plan.order); store.save()
                }
                mode = Mode.NAV
                if (runStartMs == 0L) { runStartMs = System.currentTimeMillis(); lapDeliveries = 0; lapOdoM = 0.0 }
                startLeg()
            }
        }
    }

    private fun showUndo(text: String, action: () -> Unit) {
        undoTv.text = text; undoAction = action; undoBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideUndo); ui.postDelayed(hideUndo, 10_000)
    }

    /** Timing of a leg to restore after an undone "Delivered" (startMs, predictedS, distM, odoM). */
    private var resumeLeg: DoubleArray? = null

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
                val resume = resumeLeg; resumeLeg = null
                if (resume != null) {
                    legStartMs = resume[0].toLong(); legPredictedS = resume[1]; legDistM = resume[2]; legOdoM = resume[3]
                } else {
                    legStartMs = System.currentTimeMillis(); legOdoM = 0.0
                    legPredictedS = r.timeS.toDouble(); legDistM = r.lengthM.toDouble()
                }
                val mins = Math.round(r.timeS * speed.factor(legDistM) / 60.0)
                voice.say(legTarget?.let { "Going to ${spokenStop(it)}, $mins minutes" } ?: "Heading back to ${Config.RESTAURANT_NAME}, $mins minutes")
                follow = true
                val fix = lastFix
                if (fix != null) onLocationChanged(fix, false)
                else navigator?.let { n -> val pr = n.update(r.pts[0]); lastProgress = pr; showProgress(pr); updateInfoCard() }
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
            val lapStart = runStartMs; val dels = lapDeliveries; val odo = lapOdoM
            val lapSaved = lapStart > 0
            if (lapSaved) history.addLap(LapRecord(lapStart, lapStart, now, dels, odo))
            runStartMs = 0
            voice.say("Run complete"); endNav()
            showUndo("Run finished") {
                if (lapSaved) history.removeLastLap()
                runStartMs = lapStart; lapDeliveries = dels; lapOdoM = odo
                mode = Mode.NAV; startLeg()
            }
            return
        }
        val actual = (now - legStartMs) / 1000.0
        val leg = doubleArrayOf(legStartMs.toDouble(), legPredictedS, legDistM, legOdoM)
        val sampled = speed.record(legDistM, legPredictedS, actual)
        val saved = history.addOrder(OrderRecord(runStartMs, t.orderNo, t.label, if (legOdoM > 100) legOdoM else legDistM, legPredictedS, actual, t.deadline, now))
        lapDeliveries++
        t.delivered = true; store.save()
        startLeg()
        showUndo("Delivered " + (if (t.orderNo.isNotEmpty()) "#${t.orderNo} " else "") + "${t.streetName()} ${t.number()}") {
            if (sampled) speed.removeLast(leg[2])
            if (saved) history.removeLastOrder()
            lapDeliveries--
            t.delivered = false
            // put it back as the next stop
            store.stops.remove(t); store.stops.add(store.stops.indexOfFirst { !it.delivered && !it.cancelled }.let { if (it < 0) store.stops.size else it }, t)
            store.save()
            resumeLeg = leg
            startLeg()
        }
    }

    /** Disable an order without deleting it (customer cancelled etc.). */
    private fun cancelStop(st: Stop) {
        val wasTarget = mode == Mode.NAV && st == legTarget
        st.cancelled = true; store.save(); lastEtaCalc = 0
        if (wasTarget) startLeg() else refreshAll()
        showUndo("Cancelled " + (if (st.orderNo.isNotEmpty()) "#${st.orderNo} " else "") + "${st.streetName()} ${st.number()}") { restoreStop(st) }
    }

    private fun restoreStop(st: Stop) {
        st.cancelled = false; store.save(); lastEtaCalc = 0
        worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
        if (mode == Mode.NAV && store.pending().firstOrNull() != legTarget) startLeg() else refreshAll()
    }

    /** Manual override of the planned order (positions among pending stops, 0-based). */
    private fun moveStop(from: Int, to: Int) {
        store.movePending(from, to)
        lastEtaCalc = 0
        if (mode == Mode.NAV && store.pending().firstOrNull() != legTarget) startLeg() else refreshAll()
    }

    /** Back to automatic ordering: re-optimise the remaining stops from where you are. */
    private fun autoOrder() {
        store.manualOrder = false; store.save()
        val pending = store.pending()
        if (pending.size < 2) { refreshAll(); return }
        val start = here() ?: Config.RESTAURANT
        worker.execute {
            val plan = runCatching { planner!!.optimise(start, pending, store.nogos) }.getOrNull() ?: return@execute
            ui.post {
                if (store.manualOrder || store.pending().toSet() != plan.order.toSet()) return@post
                val done = store.stops.filter { it.delivered }
                store.stops.clear(); store.stops.addAll(done); store.stops.addAll(plan.order); store.save()
                lastEtaCalc = 0
                if (mode == Mode.NAV && store.pending().firstOrNull() != legTarget) startLeg() else refreshAll()
                toast("Order optimised")
            }
        }
    }

    private fun skip() {
        val t = legTarget ?: return
        store.stops.remove(t); store.stops.add(t); store.manualOrder = true; store.save()
        startLeg()
    }

    private fun endNav() {
        mode = Mode.PLAN; navigator = null; legTarget = null; lastProgress = null
        store.stops.removeAll { it.delivered || it.cancelled }; store.save()
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

    // ---- debug: fake GPS that rides along the current route at ~25 km/h
    private var simulating = false
    private fun simulate() {
        val r = navigator?.route ?: return
        simulating = true
        var along = 0.0; var i = 0
        val v = 7.0
        val tick = object : Runnable {
            override fun run() {
                val route = navigator?.route
                if (!simulating || mode != Mode.NAV || route == null) { simulating = false; return }
                if (route !== r) { simulating = false; simulate(); return }   // new leg -> restart
                along = (along + v).coerceAtMost(route.cum.last())
                while (i < route.pts.size - 2 && route.cum[i + 1] < along) i++
                val a = route.pts[i]; val b = route.pts[i + 1]
                val seg = (route.cum[i + 1] - route.cum[i]).coerceAtLeast(0.01)
                val t = ((along - route.cum[i]) / seg).coerceIn(0.0, 1.0)
                val l = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = a.lat + t * (b.lat - a.lat); longitude = a.lon + t * (b.lon - a.lon)
                    speed = v.toFloat(); bearing = Geo.bearing(a, b).toFloat(); accuracy = 5f; time = System.currentTimeMillis()
                }
                onLocationChanged(l, fromSim = true)
                if (along < route.cum.last()) ui.postDelayed(this, 1000) else simulating = false
            }
        }
        ui.post(tick)
    }

    override fun onLocationChanged(loc: Location) { if (!simulating) onLocationChanged(loc, false) }

    private fun onLocationChanged(loc: Location, fromSim: Boolean) {
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
                (side?.let { " · on the $it" } ?: "") +
                (if (next.name.isNotEmpty()) "\n🔔 ${next.name}" else "") +
                (if (next.phone.isNotEmpty()) "  📞 tap to call" else "")
            bigNoteTv.visibility = if (next.note.isNotEmpty()) View.VISIBLE else View.GONE
            bigNoteTv.text = "📝 " + next.note
            return
        }
        normalBox.visibility = View.VISIBLE; bigBox.visibility = View.GONE
        when {
            mode == Mode.NAV && next == null -> {
                streetTv.text = Config.RESTAURANT_NAME; numberTv.text = "🏠"
                detailTv.text = "All delivered — heading back" + (pr?.let { " · ${Navigator.fmtDist(it.remainingM)}" } ?: "")
            }
            next == null -> { streetTv.text = "No stops yet"; numberTv.text = ""; detailTv.text = "Tap 📷 to scan receipts (or ☰ → type an address)" }
            else -> {
                streetTv.text = next.streetName(); numberTv.text = next.number()
                val parts = ArrayList<String>()
                if (next.orderNo.isNotEmpty()) parts.add("#${next.orderNo}")
                if (mode == Mode.NAV && pr != null) {
                    val etaS = pr.remainingS * speed.factor(pr.remainingM)
                    parts.add(Navigator.fmtDist(pr.remainingM)); parts.add("ETA ${hm(System.currentTimeMillis() + (etaS * 1000).toLong())}")
                } else etaByStop[next.id]?.let { parts.add("ETA ${hm(it)}") }
                next.deadline?.let { parts.add("due ${hm(it)}") }
                if (next.note.isNotEmpty()) parts.add("📝 note")
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
            mode == Mode.PLAN && store.pending().isEmpty() -> "📷 Scan"
            mode == Mode.PLAN -> "Go ▶"
            legTarget == null -> "Done ✓"
            else -> "Delivered ✓"
        }
        mainBtn.background = card(Color.parseColor(if (mode == Mode.NAV) "#1e9e4a" else "#1f6fe5"), 28)
        listCard.visibility = if (listOpen && (store.pending().isNotEmpty() || store.cancelled().isNotEmpty())) View.VISIBLE else View.GONE
        recenterBtn.visibility = if (listCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        orderTv.text = if (store.manualOrder) "Order: MANUAL · tap here to auto-optimise" else "Order: automatic (fastest, deadlines first) · ▲▼ to change"
        adapter.notifyDataSetChanged()
        stopsRv.layoutParams = stopsRv.layoutParams.apply {
            height = if (adapter.itemCount > 4) dp(4 * 52) else ViewGroup.LayoutParams.WRAP_CONTENT
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

    // ------------------------------------------------------------------ debug bridge API (debug builds only)

    internal fun dbgClear() { if (mode == Mode.NAV) endNav(); store.stops.clear(); store.manualOrder = false; store.save(); refreshAll() }
    internal fun dbgMove(from: Int, to: Int) = moveStop(from - 1, to - 1)
    internal fun dbgAuto() = autoOrder()

    /** OCR a receipt image file (path relative to the app's external files dir), optionally add it. */
    internal fun dbgOcr(path: String, add: Boolean, done: (String) -> Unit) {
        net.execute {
            val o = org.json.JSONObject()
            try {
                val f = File(getExternalFilesDir(null), path)
                val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: throw IllegalArgumentException("cannot read $f")
                val t0 = System.currentTimeMillis()
                val res = ReceiptOcr.parseBitmap(bmp)
                val r = res.receipt; val lines = res.lines
                o.put("ms", System.currentTimeMillis() - t0).put("lines", lines.size).put("rotation", res.rotation)
                o.put("raw", org.json.JSONArray(lines.map { "${it.left},${it.top},${it.right},${it.bottom} ${it.text}" }))
                if (r == null) {
                    o.put("parsed", false).put("text", org.json.JSONArray(lines.map { it.text }))
                } else {
                    o.put("parsed", true).put("postcode", r.postcode).put("houseNo", r.houseNo).put("street", r.street)
                        .put("city", r.city).put("name", r.name != null).put("phone", r.phone != null)
                        .put("orderNo", r.orderNo).put("deliveryTime", r.deliveryTime).put("asap", r.asap).put("note", r.note)
                    val a = runCatching { PdokGeocoder.byPostcode(r.postcode!!, r.houseNo!!) }.getOrNull()
                    o.put("bag", a?.label)
                    if (add && a != null) ui.post {
                        val st = Stop(System.currentTimeMillis(), a.label.removeSuffix(" Groningen"), a.pos, r.orderNo ?: "",
                            r.deliveryTime?.let { parseHm(it) }, street = a.street, houseNo = a.houseNo,
                            phone = r.phone ?: "", note = r.note ?: "", name = r.name ?: "")
                        store.stops.add(st); store.save(); lastEtaCalc = 0; refreshAll()
                    }
                }
            } catch (e: Exception) { o.put("error", e.toString()) }
            ui.post { done(o.toString()) }
        }
    }
    internal fun dbgUndo(): String { val a = undoAction ?: return "nothing to undo"; hideUndo.run(); a(); return "ok" }
    internal fun dbgCancel(n: Int): String { val st = store.pending().getOrNull(n - 1) ?: return "no stop $n"; cancelStop(st); return "ok" }
    internal fun dbgRestore(n: Int): String { val st = store.cancelled().getOrNull(n - 1) ?: return "no cancelled $n"; restoreStop(st); return "ok" }

    internal fun dbgAdd(q: String, order: String, due: String?, done: (String) -> Unit) {
        net.execute {
            val a = runCatching { PdokGeocoder.search(q).firstOrNull() }.getOrNull()
            ui.post {
                if (a == null) { done("not found: $q"); return@post }
                val s = Stop(System.currentTimeMillis(), a.label.removeSuffix(" Groningen"), a.pos, order, due?.let { parseHm(it) }, street = a.street, houseNo = a.houseNo)
                store.stops.add(s); store.save(); lastEtaCalc = 0; refreshAll()
                worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
                done("added ${s.label}")
            }
        }
    }

    internal fun dbgResetStats() { history.clear(); speed.clear(); refreshAll() }
    internal fun dbgGo() = startRun()
    internal fun dbgDelivered() = delivered()
    internal fun dbgSkip() = skip()
    internal fun dbgEnd() = endNav()
    internal fun dbgSim(on: Boolean) { if (on) { if (!simulating) simulate() } else simulating = false }
    internal fun dbgList(open: Boolean) { listOpen = open; refreshAll() }

    internal fun dbgState(): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("mode", mode.name).put("simulating", simulating).put("listOpen", listOpen).put("manualOrder", store.manualOrder)
        o.put("legTarget", legTarget?.label ?: if (mode == Mode.NAV) Config.RESTAURANT_NAME else null)
        o.put("speedFactor2km", speed.factor(2000.0)).put("speedSamples", speed.sampleCount())
        o.put("history", "${history.orders.size} orders, ${history.laps.size} laps")
        o.put("fix", lastFix?.let { "%.5f,%.5f acc=%.0f %s".format(Locale.US, it.latitude, it.longitude, it.accuracy, it.provider) })
        val stops = org.json.JSONArray()
        store.pending().forEachIndexed { i, s ->
            stops.put(org.json.JSONObject().put("n", i + 1).put("label", s.label).put("street", s.streetName()).put("no", s.number())
                .put("order", s.orderNo).put("due", s.deadline?.let { hm(it) }).put("eta", etaByStop[s.id]?.let { hm(it) })
                .put("stars", starsOf(s)).put("slackMin", slackMin(s)))
        }
        o.put("stops", stops)
        o.put("cancelled", org.json.JSONArray(store.cancelled().map { it.label }))
        o.put("undo", if (undoBar.visibility == View.VISIBLE) undoTv.text.toString() else null)
        lastProgress?.let { p ->
            o.put("progress", org.json.JSONObject().put("remainingM", p.remainingM.toInt()).put("remainingS", p.remainingS.toInt())
                .put("offRouteM", p.offRouteM.toInt()).put("class", p.currentClass?.name)
                .put("nextHint", p.nextHint?.let { Navigator.describe(it).second }).put("distToHintM", p.distToHintM.toInt())
                .put("nextTransition", p.nextTransition?.let { Navigator.describeTransition(it) }).put("distToTransitionM", p.distToTransitionM.toInt()))
        }
        navigator?.route?.let { r ->
            o.put("route", org.json.JSONObject().put("lengthM", r.lengthM).put("timeS", r.timeS).put("segments", r.segs.size)
                .put("pathSegments", r.segs.count { it.cls == WayClass.MOPED_PATH }).put("hints", r.hints.size).put("transitions", r.transitions.size))
        }
        // what is on screen
        o.put("screen", org.json.JSONObject()
            .put("banner", if (banner.visibility == View.VISIBLE) "${arrowTv.text} ${instrTv.text} | ${subTv.text}" else null)
            .put("card", if (bigBox.visibility == View.VISIBLE)
                "BIG ${if (bigLeftTv.visibility == View.VISIBLE) "◀ " else ""}${bigNumTv.text}${if (bigRightTv.visibility == View.VISIBLE) " ▶" else ""} | ${bigSubTv.text} | small: ${bigNoteTv.text}"
                else "${streetTv.text} ${numberTv.text} | ${detailTv.text}")
            .put("vars", "${speedTv.text} ${factorTv.text} ${starsTv.text}")
            .put("mainButton", mainBtn.text))
        o.put("voice", org.json.JSONArray(synchronized(voice.log) { voice.log.takeLast(8) }))
        return o
    }

    // ------------------------------------------------------------------ stop list

    private inner class StopsAdapter : RecyclerView.Adapter<StopsAdapter.VH>() {
        inner class VH(val row: LinearLayout, val badge: TextView, val title: TextView, val sub: TextView,
                       val up: TextView, val down: TextView, val cancel: TextView) : RecyclerView.ViewHolder(row)

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
            fun arrow(t: String) = TextView(parent.context).apply {
                text = t; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#14335e"))
                background = GradientDrawable().apply { setColor(Color.parseColor("#e9ecf1")); cornerRadius = dp(8).toFloat() }
            }
            val up = arrow("▲"); val down = arrow("▼")
            row.addView(up, LinearLayout.LayoutParams(dp(42), dp(40)).apply { leftMargin = dp(4) })
            row.addView(down, LinearLayout.LayoutParams(dp(42), dp(40)).apply { leftMargin = dp(4) })
            val cancel = arrow("✕").apply { setTextColor(Color.parseColor("#d93025")) }
            row.addView(cancel, LinearLayout.LayoutParams(dp(42), dp(40)).apply { leftMargin = dp(4) })
            return VH(row, badge, title, sub, up, down, cancel)
        }

        override fun getItemCount() = store.pending().size + store.cancelled().size

        override fun onBindViewHolder(h: VH, position: Int) {
            val pend = store.pending()
            if (position >= pend.size) { bindCancelled(h, store.cancelled()[position - pend.size]); return }
            val s = pend[position]
            h.title.paintFlags = h.title.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            h.row.alpha = 1f; h.up.visibility = View.VISIBLE; h.down.visibility = View.VISIBLE
            h.cancel.text = "✕"; h.cancel.setTextColor(Color.parseColor("#d93025"))
            h.cancel.setOnClickListener { cancelStop(s) }
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
            h.row.setOnLongClickListener { moveStop(h.bindingAdapterPosition, 0); toast("${s.streetName()} ${s.number()} is next"); true }
            h.up.alpha = if (position == 0) 0.3f else 1f
            h.down.alpha = if (position == pend.size - 1) 0.3f else 1f
            h.up.setOnClickListener { val p = h.bindingAdapterPosition; if (p > 0) moveStop(p, p - 1) }
            h.down.setOnClickListener { val p = h.bindingAdapterPosition; if (p < store.pending().size - 1) moveStop(p, p + 1) }
        }

        private fun bindCancelled(h: VH, s: Stop) {
            h.badge.text = "✕"
            h.badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#9e9e9e")) }
            h.title.text = (if (s.orderNo.isNotEmpty()) "#${s.orderNo}  " else "") + s.label
            h.title.paintFlags = h.title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            h.sub.text = "cancelled · tap ↺ to restore"
            h.row.alpha = 0.6f
            h.up.visibility = View.INVISIBLE; h.down.visibility = View.INVISIBLE
            h.cancel.text = "↺"; h.cancel.setTextColor(Color.parseColor("#1e9e4a"))
            h.cancel.setOnClickListener { restoreStop(s) }
            h.row.setOnClickListener(null); h.row.setOnLongClickListener(null)
        }
    }
}
