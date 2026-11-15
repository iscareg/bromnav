package nl.bromnav

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
 * Live receipt scanner: point the camera at the receipt, it reads continuously (on-device OCR),
 * and when the same address is read twice it stops, confirms it with BAG (PDOK) and shows the result.
 * "Add" keeps the camera open for the next receipt; "Done" returns all scanned stops to the map.
 * All controls are centred (bike-holder corners stay free).
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STOPS = "stops"          // JSON array of Stop.toJson()
        const val EXTRA_TYPE_INSTEAD = "type"    // user wants to type an address instead
        const val EXTRA_EXISTING = "existing"    // keys ("postcode no") already in the run
    }

    private val ui = Handler(Looper.getMainLooper())
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()

    private lateinit var preview: PreviewView
    private lateinit var hintTv: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var resultSub: TextView
    private lateinit var addBtn: Button
    private lateinit var countTv: TextView
    private var camera: Camera? = null
    private var torch = false

    @Volatile private var paused = false
    private var lastKey: String? = null
    private var frame = 0
    private var pending: Stop? = null
    private val added = ArrayList<Stop>()
    private val existing = HashSet<String>()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun card(c: Int, r: Int = 16) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun btn(t: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        background = card(Color.parseColor(color), 26); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(5), 0, dp(5), 0) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringArrayExtra(EXTRA_EXISTING)?.let { existing.addAll(it) }
        val side = dp(34)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        hintTv = TextView(this).apply {
            text = "Point at the Klant / Customer block\n(address, postcode, phone)"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); background = card(Color.parseColor("#B0000000")); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(hintTv, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(side, dp(24), side, 0) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(Color.WHITE); setPadding(dp(14), dp(12), dp(14), dp(12)); visibility = View.GONE
        }
        resultTitle = TextView(this).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#14335e")); gravity = Gravity.CENTER }
        resultSub = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) }
        val resBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resBtns.addView(btn("↻ Rescan", "#6b7280") { resume() })
        addBtn = btn("Add ✓", "#1e9e4a") { addPending() }
        resBtns.addView(addBtn)
        resultCard.addView(resultTitle); resultCard.addView(resultSub); resultCard.addView(resBtns)
        bottom.addView(resultCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        countTv = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = card(Color.parseColor("#B0000000"), 12)
            setPadding(dp(10), dp(4), dp(10), dp(4)); text = "Scanning…"
        }
        bottom.addView(countTv, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("🔦", "#374151") { torch = !torch; camera?.cameraControl?.enableTorch(torch) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(52)).apply { setMargins(dp(5), 0, dp(5), 0) }
        })
        row.addView(btn("⌨ Type", "#374151") { finishWith(typeInstead = true) })
        row.addView(btn("Done", "#1f6fe5") { finishWith(false) })
        bottom.addView(row, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(side, 0, side, dp(44)) })
        setContentView(root)

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
                val rot = (proxy.imageInfo.rotationDegrees + intArrayOf(0, 180, 90, 270)[frame++ % 4]) % 360
                ReceiptOcr.recognizer.process(InputImage.fromMediaImage(media, rot))
                    .addOnSuccessListener { t -> onText(ReceiptParser.parse(ReceiptOcr.toLines(t))) }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Called per analysed frame (main thread). Two identical reads in a row = confident. */
    private fun onText(r: ReceiptParser.Receipt?) {
        if (paused) return
        if (r == null) { lastKey = null; return }
        if (r.key != lastKey) { lastKey = r.key; hintTv.text = "Reading… ${r.street ?: ""} ${r.houseNo}"; return }
        paused = true
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        confirm(r)
    }

    private fun confirm(r: ReceiptParser.Receipt) {
        resultCard.visibility = View.VISIBLE
        resultTitle.text = "${r.street ?: "?"} ${r.houseNo}"
        resultSub.text = "Checking address…"
        addBtn.isEnabled = false
        net.execute {
            val a = runCatching { PdokGeocoder.byPostcode(r.postcode, r.houseNo) }
            ui.post {
                if (isFinishing) return@post
                val addr = a.getOrNull()
                if (addr == null) {
                    resultSub.text = if (a.isFailure) "No internet — can't look up ${r.postcode} ${r.houseNo}" else "${r.postcode} ${r.houseNo} not found in BAG. Rescan?"
                    return@post
                }
                val s = Stop(System.currentTimeMillis(), addr.label.removeSuffix(" Groningen"), addr.pos, r.orderNo ?: "", r.deliveryTime?.let { deadlineFrom(it) },
                    street = addr.street, houseNo = addr.houseNo, phone = r.phone ?: "", note = r.note ?: "", name = r.name ?: "")
                pending = s
                val dup = s.key() in existing || added.any { it.key() == s.key() }
                resultTitle.text = "${addr.street} ${addr.houseNo}"
                resultSub.text = listOfNotNull(
                    "✓ ${addr.label.substringAfter(", ").removeSuffix(" Groningen")} (BAG)",
                    r.orderNo?.let { "#$it" },
                    r.deliveryTime?.let { "deliver by $it" + if (r.asap) " (ASAP)" else "" } ?: "no delivery time read",
                    r.note?.let { "📝 $it" },
                    if (dup) "⚠ already in this run" else null,
                ).joinToString("\n")
                addBtn.isEnabled = true
            }
        }
    }

    private fun deadlineFrom(hm: String): Long {
        val (h, m) = hm.split(":").map { it.toInt() }
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis < System.currentTimeMillis() - 6 * 3600_000L) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun addPending() {
        pending?.let { added.add(it) }
        pending = null
        countTv.text = "${added.size} receipt${if (added.size == 1) "" else "s"} added · scan the next one or tap Done"
        resume()
    }

    private fun resume() {
        resultCard.visibility = View.GONE; pending = null; lastKey = null
        hintTv.text = "Point at the Klant / Customer block\n(address, postcode, phone)"
        paused = false
    }

    private fun finishWith(typeInstead: Boolean) {
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_STOPS, JSONArray().apply { added.forEach { put(it.toJson()) } }.toString())
            .putExtra(EXTRA_TYPE_INSTEAD, typeInstead))
        finish()
    }
}
