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
    @Volatile private var pois: PoiIndex? = null
    @Volatile private var poisLoadMs = 0L
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
    /** app mode chosen at start: delivery (runs, receipts, stats) or simple single-destination navigation */
    private var simple = false
    private var simpleTarget: Stop? = null
    private lateinit var searchHistory: SearchHistory
    private lateinit var scanBtn: Button
    private lateinit var listBtn: Button
    private var chooser: AlertDialog? = null
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
    private var earlySaid = false
    private var lastProgress: Navigator.Progress? = null
    private var etaByStop: Map<Long, Long> = emptyMap()
    private var lastEtaCalc = 0L
    private var runStartMs = 0L              // 0 = no lap in progress
    private var lapDeliveries = 0
    private var legOdoM = 0.0
    private var lapOdoM = 0.0
    private var odoFix: Location? = null
    // road access layer / your road corrections
    private var roadLayer: RoadLayer? = null
    private var showLegal = false
    private var editRoads = false
    private var legalSeq = 0
    private lateinit var roadChip: TextView

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Pal.init(this)
        Vehicle.load(this)
        RoadOverrides.init(filesDir)
        MapLibre.getInstance(this)
        store = RunStore(this)
        speed = SpeedModel(this)
        history = History(this)
        searchHistory = SearchHistory(this)
        voice = VoiceGuide(this)

        val files = getExternalFilesDir(null)!!
        val segDir = MapData.segDir(this)
        val tiles = MapData.tilesFile(this)
        if (!MapData.haveData(this)) { showSetup(files); return }

        val profile = installProfiles()
        engine = BRouterEngine(segDir, profile)
        roadLayer = RoadLayer(segDir, profile)
        Thread { val t0 = System.currentTimeMillis(); pois = MapData.openPois(this); poisLoadMs = System.currentTimeMillis() - t0 }.start()
        planner = Planner(engine!!, speed)

        buildUi(savedInstanceState, tiles)
        val restoredMode = savedInstanceState?.getString("appMode")
        if (restoredMode != null) { simple = restoredMode == "simple"; refreshAll() } else showModeChooser()
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
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState)
        if (chooser == null) outState.putString("appMode", if (simple) "simple" else "delivery")
    }
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

    /** First start without offline data: install the bundled maps, or offer to download them. */
    private fun showSetup(files: File) {
        if (MapData.hasBundled(this)) {
            runMapJob("Setting up offline maps", restart = true) { p -> MapData.installBundled(this, p) }
            return
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(48), dp(32), dp(24))
            setBackgroundColor(Pal.page)
        }
        col.addView(tv(22f, Pal.text, true).apply { text = "Offline maps needed"; gravity = Gravity.CENTER })
        col.addView(tv(15f, Pal.sub).apply {
            text = "BromNav routes and shows the map without internet. Download the routing data and the map once " +
                "(about 270 MB, Wi-Fi recommended)."; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(20))
        })
        col.addView(Button(this).apply {
            text = "⬇  Download offline maps"; isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE)
            background = card(Color.parseColor("#1f6fe5"), 26)
            setOnClickListener { confirmMapDownload(restart = true) }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        col.addView(tv(12f, Pal.sub).apply {
            text = "\nAlternatively copy segments/*.rd5 and tiles/${MapData.TILES_NAME} to\n${files.absolutePath}"; gravity = Gravity.CENTER
        })
        setContentView(col)
    }

    private fun confirmMapDownload(restart: Boolean) {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val metered = cm.isActiveNetworkMetered
        AlertDialog.Builder(this).setTitle("Update maps from internet")
            .setMessage("Downloads the newest routing data (~180 MB, brouter.de), map (~90 MB, Protomaps) and places (~7 MB, OpenStreetMap)." +
                if (metered) "\n\n⚠ You are on mobile data." else "\n\nYou are on Wi-Fi.")
            .setPositiveButton("Download") { _, _ ->
                runMapJob("Updating maps", restart) { p ->
                    MapData.downloadRouting(this) { f, m -> p(f * 0.55, m) }
                    MapData.downloadMap(this) { f, m -> p(0.55 + f * 0.35, m) }
                    MapData.downloadPois(this) { f, m -> p(0.9 + f * 0.1, m) }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Runs a long data job with a progress dialog; on success restarts the app on the new data. */
    private fun runMapJob(title: String, restart: Boolean, job: ((Double, String) -> Unit) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(8)) }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val msg = tv(14f, Pal.text).apply { text = "Starting…" }
        box.addView(bar); box.addView(msg)
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val err = runCatching { job { f, m -> ui.post { bar.progress = (f * 1000).toInt(); msg.text = m } } }.exceptionOrNull()
            ui.post {
                dlg.dismiss()
                if (err != null) {
                    AlertDialog.Builder(this).setTitle("Map update failed").setMessage(err.message ?: err.toString())
                        .setPositiveButton("OK", null).show()
                } else if (restart) restartApp()
            }
        }.start()
    }

    /** Fresh process, so the routing engine and the map open the new files. */
    private fun restartApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun card(color: Int = Pal.card, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun tv(size: Float, color: String = Pal.text, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(Color.parseColor(color)); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun iconBtn(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label; textSize = 22f; setPadding(0, 0, 0, 0); setTextColor(Color.parseColor(Pal.text))
        background = card(Pal.chip, 28); elevation = dp(4).toFloat()
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
            text = "◎"; textSize = 22f; background = card(Pal.chip, 28); elevation = dp(4).toFloat(); setTextColor(Color.parseColor(Pal.text))
            setOnClickListener { follow = true; lastFix?.let { moveCamera(it, mode == Mode.NAV) } ?: showRestaurant() }
        }
        root.addView(recenterBtn, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })

        // --- bottom column: [stop list card] [info card] [icon row]
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        listCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); elevation = dp(6).toFloat()
            setPadding(dp(8), dp(6), dp(8), dp(6)); visibility = View.GONE
        }
        orderTv = tv(13f, Pal.accent, true).apply {
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
        numberTv = tv(30f, Pal.accent, true).apply { setPadding(dp(8), 0, 0, 0) }
        addrRow.addView(streetTv, LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
        addrRow.addView(numberTv)
        detailTv = tv(13f, Pal.sub).apply { gravity = Gravity.CENTER }
        val varsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        speedTv = tv(22f, Pal.text, true).apply { text = "– km/h" }
        factorTv = tv(16f, Pal.sub).apply { setPadding(dp(14), 0, dp(14), 0) }
        starsTv = tv(24f, Pal.faint).apply { text = "☆☆☆☆☆" }
        varsRow.addView(speedTv); varsRow.addView(factorTv); varsRow.addView(starsTv)
        normalBox.addView(addrRow, LinearLayout.LayoutParams(-1, -2))
        normalBox.addView(detailTv, LinearLayout.LayoutParams(-1, -2))
        normalBox.addView(varsRow, LinearLayout.LayoutParams(-1, -2))
        infoCard.addView(normalBox, LinearLayout.LayoutParams(-1, -2))
        // "almost there" content: huge house number + side arrow
        bigBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
        val bigRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bigLeftTv = tv(64f, "#e8900c", true).apply { text = "◀" }
        bigNumTv = tv(84f, Pal.accent, true).apply { setPadding(dp(12), 0, dp(12), 0); includeFontPadding = false }
        bigRightTv = tv(64f, "#e8900c", true).apply { text = "▶" }
        bigRow.addView(bigLeftTv); bigRow.addView(bigNumTv); bigRow.addView(bigRightTv)
        bigSubTv = tv(16f, Pal.sub).apply { gravity = Gravity.CENTER }
        bigNoteTv = tv(11f, Pal.note).apply { gravity = Gravity.CENTER; maxLines = 4; setPadding(0, dp(3), 0, 0) }
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
        roadChip = tv(13f, "#ffffff").apply {
            background = card(Color.parseColor("#E0202020"), 14); setPadding(dp(12), dp(6), dp(12), dp(6)); gravity = Gravity.CENTER
            visibility = View.GONE; setOnClickListener { roadChipMenu() }
        }
        bottom.addView(roadChip, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        bottom.addView(undoBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        bottom.addView(infoCard, LinearLayout.LayoutParams(-1, -2))

        // icon row: 🔍  📋  [ main action ]  ☰   (centred)
        val icons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        scanBtn = iconBtn("📷") { if (simple) openSearch() else openScanner() }
        listBtn = iconBtn("📋") { toggleList() }
        icons.addView(scanBtn); icons.addView(listBtn)
        mainBtn = Button(this).apply {
            isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = card(Color.parseColor("#1f6fe5"), 28); elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(128), dp(52)).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener {
                when {
                    simple && mode == Mode.NAV -> endNav()
                    simple -> if (simpleTarget != null) startSimple() else openSearch()
                    mode == Mode.PLAN -> startRun()
                    else -> delivered()
                }
            }
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
            m.setStyle(Style.Builder().fromJson(MapStyle.json(tiles, Pal.dark))) { s -> style = s; s.addImage("scooter", scooterBitmap()); showRestaurant(); refreshAll(); lastFix?.let { onLocationChanged(it, simulating) } }
            m.addOnMapLongClickListener { ll -> onLongPress(LatLon(ll.latitude, ll.longitude)); true }
            m.addOnMapClickListener { ll -> if (editRoads) { pickRoad(LatLon(ll.latitude, ll.longitude)); true } else false }
            m.addOnCameraIdleListener { if (showLegal) refreshLegal() }
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

    /** Asked at every app start: delivery mode or simple navigation. */
    private fun showModeChooser() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(20), dp(24), dp(12)) }
        fun big(t: String, sub: String, color: String, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = card(Color.parseColor(color), 20)
            setPadding(dp(16), dp(18), dp(16), dp(18)); setOnClickListener { onClick() }
            addView(tv(24f, "#ffffff", true).apply { text = t; gravity = Gravity.CENTER })
            addView(tv(14f, "#e6eefc").apply { text = sub; gravity = Gravity.CENTER })
        }
        val pending = store.pending().size
        box.addView(big("🛵  Delivery", if (pending > 0) "receipts, multi-stop runs, stats · $pending stop(s) waiting" else "receipts, multi-stop runs, stats", "#1f6fe5") { setAppMode(false) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        box.addView(big("🧭  Simple nav", "search an address and go · no stats", "#14335e") { setAppMode(true) }, LinearLayout.LayoutParams(-1, -2))
        chooser = AlertDialog.Builder(this).setView(box).setCancelable(false).show()
    }

    private fun setAppMode(s: Boolean) {
        chooser?.dismiss(); chooser = null
        if (mode == Mode.NAV) endNav()
        simple = s
        if (!s) worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
        lastEtaCalc = 0; refreshAll()
    }

    /** Simple mode: navigate from here to [simpleTarget]; nothing is recorded. */
    private fun startSimple() {
        if (simpleTarget == null) return
        mode = Mode.NAV
        startLeg()
    }

    private fun openMenu(anchor: View) {
        if (simple) { openSimpleMenu(anchor); return }
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
        addRoadItems(pm)
        pm.menu.add(0, 10, 0, "🧭 Switch to simple nav")
        pm.menu.add(0, 11, 0, "🌓 Theme: ${Pal.choice(this)} (tap to change)")
        pm.menu.add(0, 12, 0, "⬇ Update maps from internet")
        if (BuildConfig.DEBUG && mode == Mode.NAV) pm.menu.add(0, 8, 0, if (simulating) "Stop simulation" else "Simulate ride (debug)")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                12 -> confirmMapDownload(restart = true)
                1 -> openSearch()
                9 -> openScanner()
                2 -> startActivity(Intent(this, StatsActivity::class.java))
                3 -> voice.enabled = !voice.enabled
                4 -> skip()
                5 -> endNav()
                6 -> confirmClear()
                7 -> { store.nogos.clear(); store.save(); refreshAll(); if (mode == Mode.NAV) reroute("Avoid marks removed") }
                8 -> if (simulating) stopSimulation() else simulate()
                10 -> setAppMode(true)
                11 -> cycleTheme()
                else -> onRoadItem(item.itemId)
            }
            true
        }
        pm.show()
    }

    /** auto -> dark -> light -> auto; AppCompat recreates the activity with the new palette. */
    private fun cycleTheme() {
        val next = when (Pal.choice(this)) { "auto" -> "dark"; "dark" -> "light"; else -> "auto" }
        Pal.setChoice(this, next)
        toast("Theme: $next")
    }

    private fun openSimpleMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "🔍 Search address…")
        pm.menu.add(0, 3, 0, if (voice.enabled) "Voice: on (tap to mute)" else "Voice: off (tap to enable)")
        if (mode == Mode.NAV) pm.menu.add(0, 5, 0, "End navigation")
        pm.menu.add(0, 7, 0, "Remove all avoid marks")
        addRoadItems(pm)
        pm.menu.add(0, 10, 0, "🛵 Switch to delivery mode")
        pm.menu.add(0, 11, 0, "🌓 Theme: ${Pal.choice(this)} (tap to change)")
        pm.menu.add(0, 12, 0, "⬇ Update maps from internet")
        if (BuildConfig.DEBUG && mode == Mode.NAV) pm.menu.add(0, 8, 0, if (simulating) "Stop simulation" else "Simulate ride (debug)")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openSearch()
                3 -> voice.enabled = !voice.enabled
                5 -> endNav()
                7 -> { store.nogos.clear(); store.save(); refreshAll(); if (mode == Mode.NAV) reroute("Avoid marks removed") }
                8 -> if (simulating) stopSimulation() else simulate()
                10 -> setAppMode(false)
                11 -> cycleTheme()
                12 -> confirmMapDownload(restart = true)
                else -> onRoadItem(item.itemId)
            }
            true
        }
        pm.show()
    }

    // ------------------------------------------------------------------ vehicle, road access layer, road corrections

    private fun addRoadItems(pm: PopupMenu) {
        pm.menu.add(0, 20, 0, "${Vehicle.current.icon} Vehicle: ${Vehicle.current.label}")
        pm.menu.add(0, 21, 0, if (showLegal) "🗺 Road access layer: ON (tap to hide)" else "🗺 Show road access layer")
        pm.menu.add(0, 22, 0, if (editRoads) "✏ Stop editing roads" else "✏ Edit road access (tap a road)")
        val n = RoadOverrides.all().size
        if (n > 0) pm.menu.add(0, 23, 0, "Remove all my road corrections ($n)")
    }

    private fun onRoadItem(id: Int) {
        when (id) {
            20 -> chooseVehicle()
            21 -> setLegal(!showLegal, false)
            22 -> if (editRoads) setLegal(showLegal, false) else setLegal(true, true)
            23 -> AlertDialog.Builder(this).setMessage("Remove all ${RoadOverrides.all().size} road corrections (allowed / forbidden sections)?")
                .setPositiveButton("Remove") { _, _ -> RoadOverrides.clear(); roadsChanged() }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun chooseVehicle() {
        val vs = Vehicle.entries
        AlertDialog.Builder(this).setTitle("What do you ride?")
            .setSingleChoiceItems(vs.map { "${it.icon}  ${it.label}" }.toTypedArray(), vs.indexOf(Vehicle.current)) { d, w ->
                d.dismiss()
                if (vs[w] == Vehicle.current) return@setSingleChoiceItems
                Vehicle.set(this, vs[w]); toast("Routing for: ${vs[w].label}")
                worker.execute { roadLayer?.clear() }
                roadsChanged("Vehicle changed")
            }.show()
    }

    private fun setLegal(show: Boolean, edit: Boolean) {
        showLegal = show; editRoads = edit && show
        if (!editRoads) setGeoJson("selroad", EMPTY_FC)
        roadChip.visibility = if (showLegal) View.VISIBLE else View.GONE
        roadChip.text = (if (editRoads) "✏ Tap a road to allow / forbid it\n" else "") +
            "🟩 allowed  🟨 ${Vehicle.current.pathName}  🟥 not allowed  🟦 your allowed  🟪 your forbidden" + "   ✖"
        if (showLegal) refreshLegal() else setGeoJson("legal", EMPTY_FC)
        if (show && !edit) toast("Road access for ${Vehicle.current.short}. Long-press or ✏ edit mode to correct a road.")
    }

    private fun roadChipMenu() {
        val items = arrayOf(if (editRoads) "Stop editing (keep layer)" else "✏ Edit roads (tap to allow / forbid)", "Hide road layer")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) { 0 -> setLegal(true, !editRoads); 1 -> setLegal(false, false) }
        }.show()
    }

    /** Recompute the visible part of the road layer (background, latest request wins). */
    private fun refreshLegal() {
        val m = map ?: return
        val zoom = m.cameraPosition.zoom
        if (zoom < 13.5) { setGeoJson("legal", EMPTY_FC); if (showLegal) toast("Zoom in to see road access"); return }
        val b = m.projection.visibleRegion.latLngBounds
        val my = ++legalSeq
        val withFoot = zoom >= 16
        worker.execute {
            if (my != legalSeq) return@execute
            val json = runCatching {
                val all = roadLayer!!.links(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
                RoadLayer.geoJson(RoadLayer.visible(all, b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast, withFoot))
            }.onFailure { Log.w("BromNav", "road layer", it) }.getOrNull() ?: return@execute
            ui.post { if (my == legalSeq && showLegal) setGeoJson("legal", json) }
        }
    }

    private fun drawOverrides() {
        val links = RoadOverrides.all().map { RoadLayer.Link(it.a, it.b, it.geom, if (it.mode == RoadOverrides.FORBID) RoadLayer.FORBIDDEN_OVR else RoadLayer.ALLOWED_OVR, it.tags) }
        setGeoJson("overrides", RoadLayer.geoJson(links) { it.cls })
    }

    /** after a vehicle change or a road correction: redraw, recompute legs, reroute */
    private fun roadsChanged(reason: String = "Road access changed") {
        drawOverrides()
        if (showLegal) { setLegal(true, editRoads) }
        if (mode == Mode.NAV) reroute(reason)
        worker.execute { runCatching { planner!!.warm(store.pending(), store.nogos) } }
        refreshAll()
    }

    private fun pickRoad(p: LatLon) {
        worker.execute {
            val l = runCatching { roadLayer!!.nearest(p, 30.0) }.getOrNull()
            ui.post { if (l == null) toast("No road found here") else roadDialog(l) }
        }
    }

    private fun roadDialog(l: RoadLayer.Link) {
        setGeoJson("selroad", RoadLayer.geoJson(listOf(l)) { 0 })
        val ov = RoadOverrides.get(l.a, l.b)
        val v = Vehicle.current
        val state = when {
            l.cls == RoadLayer.FORBIDDEN -> "⛔ NOT allowed for ${v.short} (map data)"
            l.oneway -> "↗ allowed for ${v.short}, but ONE WAY"
            l.cls == RoadLayer.PATH -> "🟨 allowed: ${v.pathName}"
            else -> "🟩 allowed road"
        }
        val yours = when (ov?.mode) {
            RoadOverrides.ALLOW -> "\nYour correction: ✅ ALLOWED"
            RoadOverrides.ALLOW_BOTH -> "\nYour correction: ✅ ALLOWED both directions"
            RoadOverrides.FORBID -> "\nYour correction: ⛔ FORBIDDEN"
            else -> ""
        }
        val tags = l.tags.split(' ').filter { !it.startsWith("reversedirection") }.joinToString("\n")
        val opts = ArrayList<Pair<String, () -> Unit>>()
        fun put(mode: Int) { RoadOverrides.set(RoadOverrides.Entry(l.a, l.b, mode, l.geom, l.tags)); setGeoJson("selroad", EMPTY_FC); roadsChanged() }
        if (ov?.mode != RoadOverrides.ALLOW) opts.add("✅ Allow this section" to { put(RoadOverrides.ALLOW) })
        if (ov?.mode != RoadOverrides.ALLOW_BOTH) opts.add("✅ Allow, both directions (ignore one-way)" to { put(RoadOverrides.ALLOW_BOTH) })
        if (ov?.mode != RoadOverrides.FORBID) opts.add("⛔ Forbid this section" to { put(RoadOverrides.FORBID) })
        if (ov != null) opts.add("↩ Remove my correction (use map data)" to { RoadOverrides.remove(l.a, l.b); setGeoJson("selroad", EMPTY_FC); roadsChanged() })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(tv(15f, Pal.text, true).apply { text = state + yours })
        box.addView(tv(12f, Pal.sub).apply { text = "${l.lengthM.toInt()} m · OSM tags:\n$tags"; setPadding(0, dp(6), 0, 0) })
        val dlg = AlertDialog.Builder(this).setTitle("Road section").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close") { _, _ -> setGeoJson("selroad", EMPTY_FC) }
            .setOnCancelListener { setGeoJson("selroad", EMPTY_FC) }.create()
        box.addView(View(this), LinearLayout.LayoutParams(-1, dp(8)))
        for ((label, action) in opts) box.addView(tv(17f, Pal.text).apply {
            text = label; setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { dlg.dismiss(); action() }
        })
        dlg.show()
    }

    // ------------------------------------------------------------------ search / stops

    private fun openSearch() {
        // delivery mode: addresses only (receipts always have one); simple nav: places + addresses
        AddressSearch.show(this, if (simple) "Where to?" else "Add stop", history = searchHistory.all(),
            pois = if (simple) pois else null, places = simple, near = here() ?: Config.RESTAURANT) { a0 ->
            // drop the distance ("· 990 m") so history entries don't show stale distances
            val a = a0.copy(label = a0.label.replace(Regex("""\s·\s[\d.,]+\s(m|km)$"""), ""))
            searchHistory.add(a)
            // places get a clean label "Name · Street 12" (without icon / distance)
            val label = a.label.replace(Regex("""^\S+ (?=.)"""), "").substringBeforeLast(" · ").takeIf { a.label.contains(" · ") } ?: a.label.removeSuffix(" Groningen")
            val st = Stop(System.currentTimeMillis(), label, a.pos, street = a.street, houseNo = a.houseNo)
            if (simple) navigateTo(st) else addStop(st)
        }
    }

    /** Simple mode: (re)start navigation to a new destination. */
    private fun navigateTo(st: Stop) {
        simpleTarget = st
        if (mode == Mode.NAV) startLeg() else startSimple()
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
        val earliestEt = field("Exact time: not before (HH:MM) — empty = ASAP", s.earliest?.let { hm(it) } ?: "", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        var pay = s.payment
        val payBtn = Button(this).apply { isAllCaps = false; textSize = 15f }
        fun showPay() { payBtn.text = "Payment: " + when (pay) { "online" -> "✅ prepaid online"; "cash" -> "💶 cash"; else -> "unknown" } + "  (tap to change)" }
        showPay()
        payBtn.setOnClickListener { pay = when (pay) { "online" -> "cash"; "cash" -> ""; else -> "online" }; showPay() }
        val phoneEt = field("Phone", s.phone, InputType.TYPE_CLASS_PHONE)
        val nameEt = field("Name (doorbell)", s.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        val noteEt = field("Message from customer", s.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { maxLines = 4; textSize = 14f }
        listOf<View>(addrBtn, orderEt, timeEt, earliestEt, payBtn, phoneEt, nameEt, noteEt).forEach { box.addView(it) }
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
                s.earliest = parseHm(earliestEt.text.toString())
                s.payment = pay
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
        if (simple) {
            AlertDialog.Builder(this).setItems(arrayOf("Navigate here", "Avoid here (not allowed for ${Vehicle.current.short})", "Remove avoid marks nearby", "🛣 This road: allow / forbid…")) { _, w ->
                when (w) {
                    3 -> pickRoad(p)
                    0 -> navigateTo(Stop(System.currentTimeMillis(), "Pin %.5f, %.5f".format(Locale.US, p.lat, p.lon), p, street = "Pin", houseNo = "📍"))
                    1 -> { store.nogos.add(NoGo(p, 15)); store.save(); refreshAll(); if (mode == Mode.NAV) reroute("Avoiding that spot") }
                    2 -> { store.nogos.removeAll { Geo.dist(it.p, p) < 60 }; store.save(); refreshAll() }
                }
            }.show()
            return
        }
        val items = arrayOf("Add stop here", "Avoid here (not allowed for ${Vehicle.current.short})", "Remove avoid marks nearby", "🛣 This road: allow / forbid…")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                3 -> pickRoad(p)
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
        legTarget = if (simple) simpleTarget else store.pending().firstOrNull()
        val target = legTarget?.pos ?: Config.RESTAURANT
        val from = here() ?: Config.RESTAURANT
        arrivedSaid = false; sideSaid = false; earlySaid = false; lastProgress = null
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
        if (r.walkM > 25) {
            val t = "The last ${r.walkM} m have no legal road for ${Vehicle.current.short}: park at the end of the route and walk. " +
                "If you may ride there, correct it with ☰ → ✏ Edit road access."
            Toast.makeText(this, t, Toast.LENGTH_LONG).show()
        }
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
        if (simple) { endNav(); return }
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
        if (simple) simpleTarget = null
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
        // GPS for speed/bearing on the road; the fused provider (GPS + Wi-Fi + cell) gives a position
        // immediately, also indoors; network as a last resort
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (pr in providers) {
            val interval = if (pr == LocationManager.NETWORK_PROVIDER) 5000L else 1000L
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    // explicit high accuracy: the legacy call defaults to LOW_POWER on Android 12+
                    val req = android.location.LocationRequest.Builder(interval)
                        .setQuality(if (pr == LocationManager.NETWORK_PROVIDER) android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                                    else android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMinUpdateIntervalMillis(interval).build()
                    lm.requestLocationUpdates(pr, req, mainExecutor, this)
                } else lm.requestLocationUpdates(pr, interval, 0f, this, Looper.getMainLooper())
            }
        }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { onLocationChanged(it) }
        ui.postDelayed(gpsWatch, 5000)
    }

    /** Last real (non-simulated) fix, and a watchdog that marks the position stale. */
    private var lastRealFix: Location? = null
    private var centeredOnMe = false
    private val gpsWatch = object : Runnable {
        override fun run() {
            val f = lastRealFix
            if (!simulating && (f == null || System.currentTimeMillis() - f.time > 15_000)) speedTv.text = "no GPS"
            ui.postDelayed(this, 5000)
        }
    }

    private fun isGps(l: Location) = l.provider == LocationManager.GPS_PROVIDER

    private fun drawMe(p: LatLon, acc: Float) {
        setGeoJson("me", """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]},"properties":{"bearing":${heading ?: 0f},"acc":$acc}}""")
    }

    private fun stopSimulation() {
        simulating = false
        val real = lastRealFix
        lastFix = real
        if (real != null) { gpsFilter.reset(); onLocationChanged(smooth(real), false) }
        else setGeoJson("me", """{"type":"FeatureCollection","features":[]}""")
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
                if (along < route.cum.last()) ui.postDelayed(this, 1000) else stopSimulation()
            }
        }
        ui.post(tick)
    }

    override fun onLocationChanged(loc: Location) {
        // keep only useful fixes: a fresh GPS fix beats fused/network ones
        val prevReal = lastRealFix
        if (!isGps(loc) && prevReal != null && isGps(prevReal) && loc.time - prevReal.time < 5_000) return
        lastRealFix = loc
        if (!simulating) onLocationChanged(smooth(loc), false)
    }

    private val gpsFilter = GpsFilter()

    /** Kalman-smoothed copy of a raw fix (speed/bearing/accuracy kept from the raw fix). */
    private fun smooth(loc: Location): Location {
        val p = gpsFilter.process(loc.latitude, loc.longitude, loc.accuracy, loc.time,
            if (loc.hasSpeed()) loc.speed else null, if (loc.hasBearing()) loc.bearing else null)
        return Location(loc).apply { latitude = p.lat; longitude = p.lon }
    }

    private fun onLocationChanged(loc: Location, fromSim: Boolean) {
        lastFix = loc
        val p = LatLon(loc.latitude, loc.longitude)
        // speed field doubles as GPS status: km/h with real GPS, "±N m" for a Wi-Fi/cell position
        speedTv.text = when {
            isGps(loc) && loc.hasSpeed() -> "${Math.round(loc.speed * 3.6)} km/h"
            isGps(loc) -> "– km/h"
            else -> "±${loc.accuracy.toInt()} m"
        }
        if (!fromSim && !centeredOnMe && mode == Mode.PLAN && map != null) {
            centeredOnMe = true
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0), 800)
        }
        if (isGps(loc)) {
            if (loc.accuracy < 25) {
                odoFix?.let { o -> val d = o.distanceTo(loc); if (d > 3 && d < 200) { legOdoM += d; lapOdoM += d } }
                odoFix = loc
            }
        }
        updateHeading(loc)
        drawMe(p, loc.accuracy)

        val nav = navigator
        if (mode == Mode.NAV && nav != null) {
            val pr = nav.update(p)
            lastProgress = pr
            // on the route (within 20 m): show the marker exactly on it - no sideways jitter
            if (pr.offRouteM < 20) drawMe(pr.snapped, loc.accuracy)
            showProgress(pr)
            voice.onProgress(pr, if (loc.hasSpeed()) loc.speed.toDouble() else 6.0)
            if (nav.offRoute && loc.accuracy < 30) reroute("Off route — recalculating…")
            val t = legTarget
            if (t != null && pr.remainingM < 150 && !sideSaid) {
                sideSaid = true
                val side = nav.destinationSide(t.pos)
                voice.say("Number ${t.number()} is " + (side?.let { "on your $it" } ?: "ahead"))
            }
            if (t != null && !earlySaid) t.earliest?.let { e ->
                val arrive = System.currentTimeMillis() + (pr.remainingS * speed.factor(pr.remainingM) * 1000).toLong()
                if (arrive < e - 60_000 && pr.remainingM < 1500) {
                    earlySaid = true
                    voice.say("You are ${(e - arrive) / 60_000} minutes early. ${spokenStop(t)} is for ${hm(e)}, not earlier.")
                }
            }
            if (pr.remainingM < 30 && !arrivedSaid) {
                arrivedSaid = true
                voice.say(t?.let { "You have arrived at ${spokenStop(it)}" + when (it.payment) {
                    "cash" -> ". Collect cash payment."; else -> "" } }
                    ?: "You are back at ${Config.RESTAURANT_NAME}")
            }
            updateInfoCard()
            if (System.currentTimeMillis() - lastEtaCalc > 15_000) updateEtas()
        }
        if (follow && map != null) moveCamera(loc, mode == Mode.NAV)
    }

    /**
     * Direction of travel: GPS bearing while riding (> ~5 km/h); otherwise from the movement
     * between fixes (also works for fused/Wi-Fi positions). Kept when standing still, so the map
     * doesn't snap back to north at every traffic light.
     */
    private var heading: Float? = null
    private var headingFix: Location? = null
    private fun updateHeading(loc: Location) {
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) { heading = loc.bearing; headingFix = loc; return }
        val prev = headingFix
        if (prev == null) { headingFix = loc; return }
        val d = prev.distanceTo(loc)
        if (d > maxOf(8f, loc.accuracy * 0.8f)) {      // moved clearly more than the position noise
            heading = prev.bearingTo(loc).let { if (it < 0) it + 360 else it }
            headingFix = loc
        } else if (loc.time - prev.time > 30_000) headingFix = loc
    }

    private fun moveCamera(loc: Location, navStyle: Boolean) {
        val m = map ?: return
        val b = CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude))
        heading?.let { b.bearing(it.toDouble()) }          // heading up while following
        if (navStyle) {
            b.zoom(speedZoom(loc)).tilt(40.0)
            b.padding(0.0, mapView!!.height * 0.30, 0.0, 0.0)
        } else b.zoom(maxOf(m.cameraPosition.zoom, 15.0))
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 800)
    }

    /**
     * Zoom from speed: z17.5 when slow (see the street), z16 at 45 km/h (see further ahead).
     * Smoothed so the map doesn't pump; slightly closer just before a turn.
     */
    private var zoomSmooth = 17.0
    private fun speedZoom(loc: Location): Double {
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        var target = 17.5 - (kmh.coerceIn(10.0, 45.0) - 10.0) / 35.0 * 1.5
        lastProgress?.let { if (it.nextHint != null && it.distToHintM < 120) target += 0.4 }
        zoomSmooth += (target - zoomSmooth) * 0.25
        return zoomSmooth
    }

    /** Top-down scooter marker (pointing up = direction of travel), drawn in code. */
    private fun scooterBitmap(): android.graphics.Bitmap {
        val sz = 120
        val bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cx = sz / 2f
        p.color = Color.WHITE; c.drawCircle(cx, cx, 56f, p)                                  // white disc for contrast
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.parseColor("#1f6fe5")
        c.drawCircle(cx, cx, 54f, p); p.style = android.graphics.Paint.Style.FILL
        p.color = Color.parseColor("#14335e")
        c.drawRoundRect(cx - 9, 16f, cx + 9, 40f, 8f, 8f, p)                                // front wheel
        c.drawRoundRect(cx - 9, 82f, cx + 9, 106f, 8f, 8f, p)                               // rear wheel
        p.color = Color.parseColor("#1f6fe5")
        c.drawRoundRect(cx - 17, 36f, cx + 17, 92f, 14f, 14f, p)                            // body / seat
        p.color = Color.parseColor("#14335e"); p.strokeWidth = 7f; p.strokeCap = android.graphics.Paint.Cap.ROUND
        c.drawLine(cx - 28, 40f, cx + 28, 40f, p)                                           // handlebar
        p.color = Color.parseColor("#ffd54a")
        val tri = android.graphics.Path().apply { moveTo(cx, 6f); lineTo(cx - 9, 18f); lineTo(cx + 9, 18f); close() }
        c.drawPath(tri, p)                                                                  // headlight = front
        return bmp
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
            arrowTv.text = if (t!!.to == WayClass.MOPED_PATH) Vehicle.current.icon else "🛣"
            instrTv.text = "${Navigator.fmtDist(pr.distToTransitionM)}  ${Navigator.describeTransition(t).replaceFirstChar { it.uppercase() }}"
        } else if (h != null) {
            val (arrow, text) = Navigator.describe(h)
            arrowTv.text = arrow
            val name = r.segmentAt(h.idx)?.name
            instrTv.text = "${Navigator.fmtDist(pr.distToHintM)}  $text" + (if (name != null && h.cmd !in 13..14) " · $name" else "")
        } else {
            arrowTv.text = "↑"; instrTv.text = "Continue ${Navigator.fmtDist(pr.remainingM)}"
        }
        subTv.text = when (pr.currentClass) { WayClass.MOPED_PATH -> "on ${Vehicle.current.pathName}"; WayClass.ROAD -> "on road"; else -> "" }
    }

    // ------------------------------------------------------------------ info card

    private fun updateInfoCard() {
        if (!::infoCard.isInitialized) return
        val pending = if (simple) emptyList() else store.pending()
        val next = if (simple) simpleTarget else if (mode == Mode.NAV) legTarget else pending.firstOrNull()
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
            bigSubTv.text = (next.paymentIcon().takeIf { it.isNotEmpty() && it != "✅ paid" }?.let { "$it   " } ?: "") +
                "${next.streetName()} · ${Navigator.fmtDist(pr.remainingM)}" +
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
            next == null && simple -> { streetTv.text = "Simple nav"; numberTv.text = "🧭"; detailTv.text = "Tap 🔍 to search a place or address (or long-press the map)" }
            next == null -> { streetTv.text = "No stops yet"; numberTv.text = ""; detailTv.text = "Tap 📷 to scan receipts (or ☰ → type an address)" }
            else -> {
                streetTv.text = next.streetName(); numberTv.text = next.number()
                val parts = ArrayList<String>()
                if (next.orderNo.isNotEmpty()) parts.add("#${next.orderNo}")
                if (mode == Mode.NAV && pr != null) {
                    val etaS = pr.remainingS * speed.factor(pr.remainingM)
                    val arrive = System.currentTimeMillis() + (etaS * 1000).toLong()
                    parts.add(Navigator.fmtDist(pr.remainingM)); parts.add("ETA ${hm(arrive)}")
                    next.earliest?.let { e -> if (arrive < e) parts.add("⏸ ${(e - arrive) / 60_000} min early") }
                } else etaByStop[next.id]?.let { parts.add("ETA ${hm(it)}") }
                timeLabel(next).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
                next.paymentIcon().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
                if (next.note.isNotEmpty()) parts.add("📝 note")
                if (pending.size > 1) parts.add("+${pending.size - 1} more")
                detailTv.text = parts.joinToString(" · ")
            }
        }
        updateStars()
    }

    /** "⏰ 17:30" for exact-time orders (not earlier), "⚡ by 20:25" for ASAP, "" without times. */
    private fun timeLabel(s: Stop): String = when {
        s.earliest != null -> "⏰ ${hm(s.earliest!!)}"
        s.deadline != null -> "⚡ by ${hm(s.deadline!!)}"
        else -> ""
    }

    // ------------------------------------------------------------------ ETAs / urgency

    private fun updateEtas() {
        lastEtaCalc = System.currentTimeMillis()
        if (simple) return
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
        if (simple) return
        val pending = store.pending()
        val best = pending.mapNotNull { s -> starsOf(s)?.let { s to it } }.maxByOrNull { it.second }
        if (best == null) { starsTv.text = "☆☆☆☆☆"; starsTv.setTextColor(Color.parseColor(Pal.faint)); return }
        val (s, n) = best
        starsTv.text = "★".repeat(n) + "☆".repeat(5 - n)
        starsTv.setTextColor(when { n >= 5 -> Color.parseColor("#d93025"); n >= 3 -> Color.parseColor("#e8900c"); else -> Color.parseColor("#e0b000") })
        // if a later order is the urgent one, say which
        if (s != (if (mode == Mode.NAV) legTarget else pending.firstOrNull()) && n > 0)
            detailTv.text = "${detailTv.text}  ⚠ stop ${pending.indexOf(s) + 1}" + (if (s.orderNo.isNotEmpty()) " #${s.orderNo}" else "")
    }

    private fun refreshAll() {
        if (!::infoCard.isInitialized) return
        scanBtn.text = if (simple) "🔍" else "📷"
        listBtn.visibility = if (simple) View.GONE else View.VISIBLE
        starsTv.visibility = if (simple) View.GONE else View.VISIBLE
        factorTv.visibility = if (simple) View.GONE else View.VISIBLE
        if (simple) listOpen = false
        mainBtn.text = when {
            simple && mode == Mode.NAV -> "End ■"
            simple -> if (simpleTarget != null) "Go ▶" else "Where to?"
            mode == Mode.PLAN && store.pending().isEmpty() -> "📷 Scan"
            mode == Mode.PLAN -> "Go ▶"
            legTarget == null -> "Done ✓"
            else -> "Delivered ✓"
        }
        mainBtn.background = card(Color.parseColor(when { simple && mode == Mode.NAV -> "#6b7280"; mode == Mode.NAV -> "#1e9e4a"; else -> "#1f6fe5" }), 28)
        listCard.visibility = if (listOpen && (store.pending().isNotEmpty() || store.cancelled().isNotEmpty())) View.VISIBLE else View.GONE
        recenterBtn.visibility = if (listCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        orderTv.text = if (store.manualOrder) "Order: MANUAL · tap here to auto-optimise" else "Order: automatic (fastest, deadlines first) · ▲▼ to change"
        adapter.notifyDataSetChanged()
        stopsRv.layoutParams = stopsRv.layoutParams.apply {
            height = if (adapter.itemCount > 4) dp(4 * 52) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        updateInfoCard(); drawStops(); drawOverrides()
        if (System.currentTimeMillis() - lastEtaCalc > 2000) updateEtas()
    }

    // ------------------------------------------------------------------ map drawing

    private val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
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
        if (simple) {
            simpleTarget?.let { f.add(pt(it.pos, "★", "#14335e")) }
            setGeoJson("stops", """{"type":"FeatureCollection","features":[${f.joinToString(",")}]}""")
            setGeoJson("nogos", """{"type":"FeatureCollection","features":[${store.nogos.joinToString(",") { pt(it.p, "", "#d93025") }}]}""")
            return
        }
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
    internal fun dbgMode(m: String) = setAppMode(m == "simple")
    internal fun dbgSearch(q: String): String {
        val idx = pois ?: return "places not loaded yet ${MapData.poiError ?: ""}"
        val t0 = System.nanoTime()
        val r = idx.search(q, here() ?: Config.RESTAURANT, 5)
        val ms = (System.nanoTime() - t0) / 1_000_000
        return "${ms} ms (index loaded in $poisLoadMs ms): " + r.joinToString(" | ") { "${it.poi.icon} ${it.poi.name} (${(it.distM / 100).toInt() / 10.0} km)" }
    }
    internal fun dbgNavTo(q: String, done: (String) -> Unit) {
        net.execute {
            val a = runCatching { PdokGeocoder.search(q).firstOrNull() }.getOrNull()
            ui.post {
                if (a == null) { done("not found: $q"); return@post }
                searchHistory.add(a)
                navigateTo(Stop(System.currentTimeMillis(), a.label.removeSuffix(" Groningen"), a.pos, street = a.street, houseNo = a.houseNo))
                done("navigating to ${a.label} @ ${a.pos.lat},${a.pos.lon}")
            }
        }
    }

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
                    o.put("parsed", true).put("requestedTime", r.requestedTime).put("payment", r.payment).put("postcode", r.postcode).put("houseNo", r.houseNo).put("street", r.street)
                        .put("city", r.city).put("name", r.name != null).put("phone", r.phone != null)
                        .put("orderNo", r.orderNo).put("deliveryTime", r.deliveryTime).put("asap", r.asap).put("note", r.note)
                    val a = runCatching { PdokGeocoder.byPostcode(r.postcode!!, r.houseNo!!) }.getOrNull()
                    o.put("bag", a?.label)
                    if (add && a != null) ui.post {
                        val st = Stop(System.currentTimeMillis(), a.label.removeSuffix(" Groningen"), a.pos, r.orderNo ?: "",
                            r.deliveryTime?.let { parseHm(it) }, street = a.street, houseNo = a.houseNo,
                            phone = r.phone ?: "", note = r.note ?: "", name = r.name ?: "",
                            earliest = r.requestedTime?.let { parseHm(it) }, payment = r.payment ?: "")
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

    internal fun dbgAdd(q: String, order: String, due: String?, done: (String) -> Unit, earliest: String? = null, pay: String? = null) {
        net.execute {
            val a = runCatching { PdokGeocoder.search(q).firstOrNull() }.getOrNull()
            ui.post {
                if (a == null) { done("not found: $q"); return@post }
                val s = Stop(System.currentTimeMillis(), a.label.removeSuffix(" Groningen"), a.pos, order, due?.let { parseHm(it) }, street = a.street, houseNo = a.houseNo,
                    earliest = earliest?.let { parseHm(it) }, payment = pay ?: "")
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
    internal fun dbgSim(on: Boolean) { if (on) { if (!simulating) simulate() } else stopSimulation() }
    internal fun dbgList(open: Boolean) { listOpen = open; refreshAll() }

    internal fun dbgVehicle(v: Int): String { Vehicle.set(this, Vehicle.entries.first { it.code == v }); worker.execute { roadLayer?.clear() }; roadsChanged("Vehicle changed"); return Vehicle.current.label }
    internal fun dbgLegal(on: Boolean, edit: Boolean): String { setLegal(on, edit); return "ok" }
    internal fun dbgCamera(lat: Double, lon: Double, z: Double): String { follow = false; map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z)); return "ok" }
    /** set = allow | both | forbid | reset | null (just inspect) */
    internal fun dbgRoad(lat: Double, lon: Double, set: String?, done: (String) -> Unit) {
        worker.execute {
            val t0 = System.currentTimeMillis()
            val l = roadLayer!!.nearest(LatLon(lat, lon), 30.0)
            val ms = System.currentTimeMillis() - t0
            ui.post {
                if (l == null) { done("no road"); return@post }
                when (set) {
                    "allow" -> RoadOverrides.set(RoadOverrides.Entry(l.a, l.b, RoadOverrides.ALLOW, l.geom, l.tags))
                    "both" -> RoadOverrides.set(RoadOverrides.Entry(l.a, l.b, RoadOverrides.ALLOW_BOTH, l.geom, l.tags))
                    "forbid" -> RoadOverrides.set(RoadOverrides.Entry(l.a, l.b, RoadOverrides.FORBID, l.geom, l.tags))
                    "reset" -> RoadOverrides.remove(l.a, l.b)
                }
                if (set != null) roadsChanged()
                done("cls=${l.cls} oneway=${l.oneway} len=${l.lengthM.toInt()} ovr=${RoadOverrides.get(l.a, l.b)?.mode} tags=${l.tags} (lookup $ms ms, cells ${roadLayer!!.lastLoadMs} ms)")
            }
        }
    }

    internal fun dbgState(): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("vehicle", Vehicle.current.short).put("roadOverrides", RoadOverrides.all().size).put("showLegal", showLegal).put("editRoads", editRoads)
            .put("roadLayerMs", roadLayer?.lastLoadMs)
        o.put("appMode", if (simple) "simple" else "delivery").put("chooserOpen", chooser != null)
            .put("searchHistory", org.json.JSONArray(searchHistory.all().take(5).map { it.label }))
        o.put("mode", mode.name).put("simulating", simulating).put("listOpen", listOpen).put("manualOrder", store.manualOrder)
        o.put("legTarget", legTarget?.label ?: if (mode == Mode.NAV) Config.RESTAURANT_NAME else null)
        o.put("speedFactor2km", speed.factor(2000.0)).put("speedSamples", speed.sampleCount())
        o.put("history", "${history.orders.size} orders, ${history.laps.size} laps")
        o.put("fix", lastFix?.let { "%.5f,%.5f acc=%.0f %s".format(Locale.US, it.latitude, it.longitude, it.accuracy, it.provider) })
        val stops = org.json.JSONArray()
        store.pending().forEachIndexed { i, s ->
            stops.put(org.json.JSONObject().put("n", i + 1).put("label", s.label).put("street", s.streetName()).put("no", s.number())
                .put("order", s.orderNo).put("due", s.deadline?.let { hm(it) }).put("eta", etaByStop[s.id]?.let { hm(it) })
                .put("stars", starsOf(s)).put("slackMin", slackMin(s)).put("notBefore", s.earliest?.let { hm(it) }).put("pay", s.payment))
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
            val title = TextView(parent.context).apply { textSize = 15f; setTextColor(Color.parseColor(Pal.text)); isSingleLine = true }
            val sub = TextView(parent.context).apply { textSize = 13f; setTextColor(Color.parseColor(Pal.sub)); isSingleLine = true }
            col.addView(title); col.addView(sub)
            row.addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            fun arrow(t: String) = TextView(parent.context).apply {
                text = t; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(Pal.accent))
                background = GradientDrawable().apply { setColor(Pal.chip2); cornerRadius = dp(8).toFloat() }
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
            timeLabel(s).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            s.paymentIcon().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
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
