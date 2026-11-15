package nl.bromnav

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Live receipt reader. Every camera frame is OCR'd; each field is collected independently and only
 * gets a ✓ once the same value was read in two frames (the address must also exist in BAG).
 * The checklist shows live what is already reliable: address, delivery time, order number and the
 * customer's message (with an on-device Dutch→English translation).
 * "Add" works at any moment: missing fields can be filled in by hand.
 */
class ScanActivity : AppCompatActivity() {

    /** Draws the OCR line boxes of the latest frame over the camera preview. */
    private class BoxOverlay(ctx: android.content.Context) : View(ctx) {
        data class Box(val r: android.graphics.RectF, val color: Int)
        @Volatile var boxes: List<Box> = emptyList()
        init { setWillNotDraw(false) }
        private val paint = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
        override fun onDraw(c: android.graphics.Canvas) {
            for (b in boxes) { paint.color = b.color; c.drawRoundRect(b.r, 6f, 6f, paint) }
        }
    }

    companion object {
        const val EXTRA_STOPS = "stops"          // JSON array of Stop.toJson()
        const val EXTRA_TYPE_INSTEAD = "type"    // user wants to type an address instead
        const val EXTRA_EXISTING = "existing"    // keys ("postcode no") already in the run
        private const val CONFIRMATIONS = 2
        /** identical address readings (not necessarily consecutive) needed before the BAG check */
        private const val ADDRESS_STREAK = 3
    }

    /**
     * A value is trusted once exactly the same reading was seen [need] times (not necessarily in a
     * row: a misread frame in between only adds a vote for the wrong value). [best] is the
     * most-read value so far; the user can also accept it by hand with [accept].
     */
    private class Votes(val need: Int = CONFIRMATIONS) {
        val counts = HashMap<String, Int>()
        var locked: String? = null
        var best: String? = null
        val bestCount get() = counts[best] ?: 0
        fun add(v: String?): Boolean {
            if (v == null || locked != null) return false
            val n = (counts[v] ?: 0) + 1; counts[v] = n
            if (n >= bestCount) best = v
            if (n >= need) { locked = v; return true }
            return false
        }
        fun accept(): Boolean { if (locked != null || best == null) return false; locked = best; return true }
        fun reset() { counts.clear(); locked = null; best = null }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()

    private lateinit var preview: PreviewView
    private lateinit var overlay: BoxOverlay
    private var lastBoxesAt = 0L
    private lateinit var headTv: TextView
    private lateinit var addrTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var orderTv: TextView
    private lateinit var noteTv: TextView
    private lateinit var addBtn: Button
    private lateinit var countTv: TextView
    private var camera: Camera? = null
    private var torch = false

    // --- per-receipt state
    private val addr = Votes(ADDRESS_STREAK); private val time = Votes(3); private val order = Votes(3); private val note = Votes()
    private val phone = Votes(); private val name = Votes()
    /** "ASAP" or "HH:MM" (exact time requested), and payment kind */
    private val req = Votes(2); private val pay = Votes(2)
    private var asapSeen = 0
    private var bag: PdokGeocoder.Address? = null
    private var bagState = ""            // "", "checking", "notfound", "offline"
    private var bestNote: String? = null
    private var noteEn: String? = null
    private var allBuzzed = false
    private var frame = 0
    @Volatile private var paused = false

    private val added = ArrayList<Stop>()
    private val existing = HashSet<String>()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun card(c: Int, r: Int = 16) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun btn(t: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        background = card(Color.parseColor(color), 26); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(5), 0, dp(5), 0) }
    }
    private fun row(size: Float = 16f) = TextView(this).apply { textSize = size; setTextColor(Color.parseColor("#222222")); setPadding(0, dp(3), 0, dp(3)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringArrayExtra(EXTRA_EXISTING)?.let { existing.addAll(it) }
        NoteTranslator.ensureModel(wifiOnly = true) { ui.post { render() } }

        val side = dp(34)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // TextureView-based preview so the box overlay composes reliably on top
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        overlay = BoxOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        // live checklist
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card(Color.parseColor("#F2FFFFFF")); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        headTv = row(15f).apply { typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#14335e")); gravity = Gravity.CENTER }
        addrTv = row(); timeTv = row(); orderTv = row(); noteTv = row(14f)
        listOf(headTv, addrTv, timeTv, orderTv, noteTv).forEach { list.addView(it) }
        // "I can see it's right": tap a ⏳ line to accept what it currently shows
        addrTv.setOnClickListener { if (bag == null && bagState != "checking" && addr.accept()) lookupBag() }
        timeTv.setOnClickListener { if (time.accept()) render() }
        orderTv.setOnClickListener { if (order.accept()) render() }
        noteTv.setOnClickListener { if (note.locked == null && bestNote != null) { note.locked = bestNote; translateNote(); render() } }
        val resBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        resBtns.addView(btn("↻ Reset", "#6b7280") { resetReceipt() })
        addBtn = btn("Add ✓", "#1e9e4a") { addCurrent() }
        resBtns.addView(addBtn)
        list.addView(resBtns)
        bottom.addView(list, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        countTv = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = card(Color.parseColor("#B0000000"), 12)
            setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = View.GONE
        }
        bottom.addView(countTv, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("🔦", "#374151") { torch = !torch; camera?.cameraControl?.enableTorch(torch) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(52)).apply { setMargins(dp(5), 0, dp(5), 0) }
        })
        row.addView(btn("⌨ Type", "#374151") { finishWith(typeInstead = true) })
        row.addView(btn("Done", "#1f6fe5") { done() })
        bottom.addView(row, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(side, 0, side, dp(44)) })
        setContentView(root)
        render()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else finishWith(typeInstead = true)
    }

    override fun onDestroy() { analysisExec.shutdown(); net.shutdown(); super.onDestroy() }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val provider = fut.get()
            val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExec) { proxy ->
                val media = proxy.image
                if (paused || media == null) { proxy.close(); return@setAnalyzer }
                // cycle the 4 orientations over frames: receipts are held sideways / upside down
                val base = proxy.imageInfo.rotationDegrees
                val extra = intArrayOf(0, 180, 90, 270)[frame++ % 4]
                // upright image size (as shown on screen)
                val w = if (base % 180 == 0) proxy.width else proxy.height
                val h = if (base % 180 == 0) proxy.height else proxy.width
                ReceiptOcr.recognizer.process(InputImage.fromMediaImage(media, (base + extra) % 360))
                    .addOnSuccessListener { t ->
                        val lines = ReceiptOcr.toLines(t)
                        val f = ReceiptParser.parseFields(lines)
                        drawBoxes(lines, f, extra, w, h)
                        onFields(f)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------------ collecting

    /** Main thread, once per analysed frame. */
    private fun onFields(r: ReceiptParser.Receipt?) {
        if (paused || r == null) return
        var changed = false
        if (r.hasAddress && bag == null && bagState != "checking") {
            // the full string (postcode|number|street) must be read identically ADDRESS_STREAK times
            if (addr.add("${r.postcode}|${r.houseNo}|${r.street ?: ""}")) lookupBag()
            changed = true
        }
        if (r.deliveryTime != null) { time.add(r.deliveryTime); changed = true }
        if (r.asap) asapSeen++
        if (r.asap) req.add("ASAP") else if (r.requestedTime != null) req.add(r.requestedTime)
        if (r.payment != null) { pay.add(r.payment); changed = true }
        if (r.orderNo != null) { order.add(r.orderNo); changed = true }
        if (r.phone != null) phone.add(r.phone)
        if (r.name != null) name.add(r.name)
        if (r.note != null && note.locked == null) {
            // OCR of long text varies a little between frames: vote on a normalised form, keep the longest reading
            if (bestNote == null || r.note.length > bestNote!!.length) bestNote = r.note
            if (note.add(r.note.lowercase().replace(Regex("""[^a-z0-9]"""), ""))) { note.locked = bestNote; translateNote() }
            changed = true
        }
        if (changed) render()
    }

    /**
     * Maps line boxes from the orientation the frame was read in ([extra] degrees clockwise on top
     * of upright) back to upright image coordinates, then onto the FILL_CENTER preview.
     */
    private fun drawBoxes(lines: List<ReceiptParser.Line>, f: ReceiptParser.Receipt?, extra: Int, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        if (lines.isEmpty() || (f != null && !ReceiptParser.isHorizontal(lines)) || f == null) {
            // keep the last good boxes a moment (3 of 4 frames are read in the wrong orientation)
            if (now - lastBoxesAt > 1200 && overlay.boxes.isNotEmpty()) { overlay.boxes = emptyList(); overlay.invalidate() }
            return
        }
        fun up(x: Float, y: Float): Pair<Float, Float> = when (extra) {
            90 -> y to (h - x)
            180 -> (w - x) to (h - y)
            270 -> (w - y) to x
            else -> x to y
        }
        val vw = overlay.width.toFloat(); val vh = overlay.height.toFloat()
        if (vw == 0f) return
        val sc = maxOf(vw / w, vh / h)
        val ox = (vw - w * sc) / 2; val oy = (vh - h * sc) / 2
        fun color(t: String): Int {
            val n = t.replace(" ", "").uppercase()
            return when {
                f.postcode != null && n.contains(f.postcode) -> Color.parseColor("#00E676")
                f.street != null && n.contains(f.street.replace(" ", "").uppercase()) -> Color.parseColor("#00E676")
                f.deliveryTime != null && t.contains(f.deliveryTime) -> Color.parseColor("#FFA000")
                f.orderNo != null && n.replace("-", "").contains(f.orderNo.replace("-", "")) -> Color.parseColor("#E040FB")
                f.note != null && t.length > 3 && f.note.contains(t.trim()) -> Color.parseColor("#FF4081")
                else -> Color.argb(170, 255, 255, 255)
            }
        }
        overlay.boxes = lines.map { l ->
            val (x1, y1) = up(l.left.toFloat(), l.top.toFloat()); val (x2, y2) = up(l.right.toFloat(), l.bottom.toFloat())
            BoxOverlay.Box(android.graphics.RectF(minOf(x1, x2) * sc + ox, minOf(y1, y2) * sc + oy, maxOf(x1, x2) * sc + ox, maxOf(y1, y2) * sc + oy), color(l.text))
        }
        lastBoxesAt = now
        overlay.invalidate()
    }

    private fun lookupBag() {
        val (pc, no) = addr.locked!!.split("|")
        bagState = "checking"; render()
        net.execute {
            val res = runCatching { PdokGeocoder.byPostcode(pc, no) }
            ui.post {
                if (isFinishing) return@post
                val a = res.getOrNull()
                when {
                    a != null -> { bag = a; bagState = "" }
                    res.isFailure -> { bagState = "offline"; addr.reset() }
                    else -> { bagState = "notfound"; addr.reset() }   // misread: keep scanning
                }
                render()
            }
        }
    }

    private fun translateNote() {
        val n = note.locked ?: return
        NoteTranslator.translate(n) { en -> noteEn = en; render() }
    }

    private fun stopKey(a: PdokGeocoder.Address) = "${Regex("""\d{4}[A-Z]{2}""").find(a.label)?.value} ${a.houseNo.uppercase()}"

    // ------------------------------------------------------------------ UI

    private fun render() {
        val grey = "#8a8a8a"
        fun line(tv: TextView, ok: Boolean, label: String, value: String?, tentative: String?) {
            tv.text = if (ok) "✓  $label:  $value" else "⏳  $label:  " + (tentative?.let { "$it  · tap ✓" } ?: "reading…")
            tv.setTextColor(Color.parseColor(if (ok) "#1b5e20" else grey))
            tv.typeface = if (ok) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val a = bag
        val addrValue = a?.let { "${it.street} ${it.houseNo} · ${Regex("""\d{4}[A-Z]{2}""").find(it.label)?.value ?: ""}" +
            if (stopKey(it) in existing || added.any { s -> s.key() == stopKey(it) }) "  ⚠ already in run" else "" }
        val addrTent = when (bagState) {
            "checking" -> "checking BAG…"
            "notfound" -> "not in BAG, rescanning"
            "offline" -> "no internet for BAG check"
            else -> addr.best?.split("|")?.let { "${it[2]} ${it[1]}".trim() + "  (${addr.bestCount}/${addr.need})" }
        }
        line(addrTv, a != null, "Address", addrValue, addrTent)
        val kind = req.locked ?: req.best
        val timeText = time.locked?.let { t -> when {
            kind == "ASAP" -> "⚡ ASAP, by $t"
            kind != null -> "⏰ exactly $kind (not earlier)"
            else -> t
        } }
        line(timeTv, time.locked != null, "Time", timeText, time.best?.let { "$it  (${time.bestCount}/${time.need})" })
        val payIcon = when (pay.locked ?: pay.best) { "online" -> "  ·  ✅ paid"; "cash" -> "  ·  💶 CASH"; else -> "" }
        line(orderTv, order.locked != null, "Order", order.locked?.let { "#$it$payIcon" }, order.best?.let { "#$it  (${order.bestCount}/${order.need})$payIcon" })
        val n = note.locked
        noteTv.visibility = if (n != null || bestNote != null) View.VISIBLE else View.GONE
        noteTv.text = when {
            n == null -> "⏳  Message: ${bestNote?.take(60)}…"
            noteEn != null -> "📝  $n\n🇬🇧  $noteEn"
            !NoteTranslator.isDutch(n) -> "📝  $n"
            !NoteTranslator.ready -> "📝  $n\n🇬🇧  (translation after a one-time 30 MB download on Wi-Fi)"
            else -> "📝  $n\n🇬🇧  translating…"
        }
        noteTv.setTextColor(Color.parseColor(if (n != null) "#4a148c" else grey))

        val all = a != null && time.locked != null && order.locked != null
        headTv.text = when {
            all -> "All read ✓  — tap Add" + if (BuildConfig.DEBUG) " [${overlay.boxes.size}]" else ""
            !hasData() -> "Point the camera at the receipt"
            else -> "Keep the receipt in view…" + if (BuildConfig.DEBUG) " [${overlay.boxes.size} boxes]" else ""
        }
        if (all && !allBuzzed) {
            allBuzzed = true
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        addBtn.text = if (all) "Add ✓" else "Add with this…"
        if (added.isNotEmpty()) {
            countTv.visibility = View.VISIBLE
            countTv.text = "${added.size} receipt${if (added.size == 1) "" else "s"} added · scan the next or tap Done"
        }
    }

    private fun hasData() = bag != null || addr.best != null || time.best != null || order.best != null || bestNote != null

    // ------------------------------------------------------------------ add / finish

    /** Adds the current receipt; if something is missing, opens a form prefilled with what was read. */
    private fun addCurrent() {
        val a = bag
        if (a != null && time.locked != null && order.locked != null) { commit(a, order.locked!!, time.locked!!); return }
        paused = true
        val cand = addr.best
        if (a == null && cand != null) {
            // check the most-read address in BAG first, then open the form with it filled in
            val (pc, no) = cand.split("|")
            net.execute {
                val found = runCatching { PdokGeocoder.byPostcode(pc, no) }.getOrNull()
                ui.post { if (!isFinishing) { if (found != null) { bag = found; addr.locked = cand }; completeDialog() } }
            }
            return
        }
        completeDialog()
    }

    private fun completeDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        var chosen: PdokGeocoder.Address? = bag
        val addrBtn = Button(this).apply { isAllCaps = false; textSize = 16f }
        fun showAddr() { addrBtn.text = chosen?.let { "📍 ${it.label.removeSuffix(" Groningen")}  (change)" } ?: "📍 Choose address…" }
        showAddr()
        addrBtn.setOnClickListener {
            val guess = addr.best?.split("|")?.let { "${it[2]} ${it[1]}".trim() } ?: ""
            AddressSearch.show(this, "Address", guess) { chosen = it; showAddr() }
        }
        val orderEt = EditText(this).apply { hint = "Order number"; setText(order.locked ?: order.best ?: ""); inputType = InputType.TYPE_CLASS_TEXT }
        val timeEt = EditText(this).apply {
            hint = "Deliver before (HH:MM)"; inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setText(time.locked ?: time.best ?: "")
        }
        val kind = req.locked ?: req.best
        val earliestEt = EditText(this).apply {
            hint = "Exact time: not before (HH:MM) — empty = ASAP"; inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setText(if (kind != null && kind != "ASAP") kind else "")
        }
        box.addView(addrBtn); box.addView(orderEt); box.addView(timeEt); box.addView(earliestEt)
        AlertDialog.Builder(this).setTitle("Complete this order").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val c = chosen
                if (c == null) { Toast.makeText(this, "An address is needed", Toast.LENGTH_SHORT).show(); paused = false; return@setPositiveButton }
                commit(c, orderEt.text.toString().trim(), timeEt.text.toString().trim(), earliestEt.text.toString().trim())
            }
            .setNegativeButton("Back to scanning") { _, _ -> paused = false }
            .setOnCancelListener { paused = false }
            .show()
    }

    /**
     * [notBefore]: requested exact time ("" = ASAP). Exact-time orders get "not before" = that time
     * and a deadline of that time + grace; ASAP orders just get the promised time as deadline.
     */
    private fun commit(a: PdokGeocoder.Address, orderNo: String, hm: String,
                       notBefore: String = (req.locked ?: req.best)?.takeIf { it != "ASAP" } ?: "") {
        val n = note.locked ?: bestNote
        val earliest = deadlineFrom(notBefore)
        val deadline = if (earliest != null) maxOf(earliest, deadlineFrom(hm) ?: earliest) + Config.SCHEDULED_GRACE_MIN * 60_000L else deadlineFrom(hm)
        val s = Stop(System.currentTimeMillis(), a.label.removeSuffix(" Groningen"), a.pos, orderNo, deadline,
            street = a.street, houseNo = a.houseNo, phone = phone.locked ?: phone.best ?: "",
            note = listOfNotNull(n, noteEn?.let { "🇬🇧 $it" }).joinToString("\n"), name = name.locked ?: name.best ?: "",
            earliest = earliest, payment = pay.locked ?: pay.best ?: "")
        added.add(s)
        resetReceipt()
    }

    private fun resetReceipt() {
        listOf(addr, time, order, note, phone, name, req, pay).forEach { it.reset() }
        bag = null; bagState = ""; noteEn = null; bestNote = null; asapSeen = 0; allBuzzed = false
        paused = false
        render()
    }

    private fun deadlineFrom(hm: String): Long? {
        val m = Regex("""^\s*(\d{1,2})[:.]?(\d{2})\s*$""").find(hm) ?: return null
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt().coerceIn(0, 23)); c.set(Calendar.MINUTE, m.groupValues[2].toInt().coerceIn(0, 59))
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis < System.currentTimeMillis() - 6 * 3600_000L) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun done() {
        if (!hasData()) { finishWith(false); return }
        paused = true
        AlertDialog.Builder(this).setMessage("Add the receipt you are scanning now?")
            .setPositiveButton("Add it") { _, _ -> addCurrent() }
            .setNegativeButton("Discard") { _, _ -> finishWith(false) }
            .setOnCancelListener { paused = false }
            .show()
    }

    private fun finishWith(typeInstead: Boolean) {
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_STOPS, JSONArray().apply { added.forEach { put(it.toJson()) } }.toString())
            .putExtra(EXTRA_TYPE_INSTEAD, typeInstead))
        finish()
    }
}
