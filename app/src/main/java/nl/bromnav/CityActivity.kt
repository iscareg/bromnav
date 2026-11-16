package nl.bromnav

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Choose your city (first start, or Settings → City): shows what it costs on the phone, then
 * downloads routing data, map, places and addresses for it and restarts the app.
 */
class CityActivity : AppCompatActivity() {

    companion object { const val EXTRA_FIRST = "first" }

    private val ui = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private var busy = false
    private var openCity: City? = null
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.init(this)
        build()
    }

    private fun build() {
        val first = intent.getBooleanExtra(EXTRA_FIRST, false)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        col.addView(Ui.text(this, 24f, bold = true, center = true).apply { text = "🏙  Where do you work?"; setPadding(0, dp(8), 0, dp(4)) })
        col.addView(Ui.text(this, 15f, Ui.c(Pal.sub), center = true).apply {
            text = (if (first) "BromNav works offline. Choose your city: its routing data, map, places and addresses are downloaded once.\n" else "") +
                "Free space on this phone: ${fmt(getExternalFilesDir(null)!!.usableSpace)}"
            setPadding(0, 0, 0, dp(14))
        })
        val current = Region.city
        for (c in Cities.all.sortedBy { if (it == current) "" else it.name }) col.addView(cityCard(c, c == current))
        if (!first) col.addView(Ui.pill(this, "← Back") { finish() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16); leftMargin = dp(20); rightMargin = dp(20) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.page); addView(col) })
    }

    /** the release APK carries Groningen's maps: no download needed */
    private fun bundled(c: City) = c.name == "Groningen" && runCatching { assets.list("mapdata")?.contains("groningen.pmtiles") == true }.getOrDefault(false)

    private fun installed(c: City) = bundled(c) || c.segments().all { File(MapData.segDir(this), "$it.rd5").exists() } &&
        File(getExternalFilesDir(null), "tiles/${c.key}.pmtiles").exists() &&
        (File(getExternalFilesDir(null), "${c.key}_${MapData.ADDR_NAME}").exists() || (c.name == "Groningen" && builtInAddresses()))

    /** the APK carries Groningen's address book and places */
    private fun builtInAddresses() = runCatching { assets.list("")?.any { it.startsWith(MapData.ADDR_NAME.removeSuffix(".gz")) } == true }.getOrDefault(false)

    /** files only this city uses: its map, places, addresses, and routing tiles no other city on the phone needs */
    private fun cityFiles(c: City): List<File> {
        val dir = getExternalFilesDir(null)!!
        val own = listOf(File(dir, "tiles/${c.key}.pmtiles"), File(dir, "${c.key}_${MapData.ADDR_NAME}"), File(dir, "${c.key}_${MapData.POIS_NAME}"))
        val othersNeed = Cities.all.filter { it != c && (it == Region.city || File(dir, "tiles/${it.key}.pmtiles").exists()) }.flatMap { it.segments() }.toSet()
        val segs = c.segments().filter { it !in othersNeed }.map { File(MapData.segDir(this), "$it.rd5") }
        return (own + segs).filter { it.exists() }
    }

    private fun cityCard(c: City, current: Boolean): View {
        val card = Ui.card(this, 18, 1).apply { isClickable = true }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, 19f, bold = true).apply { text = c.name }, LinearLayout.LayoutParams(0, -2, 1f))
        val have = installed(c)
        head.addView(Ui.chip(this, when { current -> "✓ current"; have -> "on phone"; else -> "%.0f km²".format(c.areaKm2) },
            if (current) Ui.Tone.GREEN else if (have) Ui.Tone.BLUE else Ui.Tone.NEUTRAL))
        box.addView(head)
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (openCity == c) View.VISIBLE else View.GONE; setPadding(0, dp(8), 0, 0) }
        box.addView(detail)
        card.addView(box)
        // data of another city on the phone: can be deleted to free space
        val files = if (current) emptyList() else cityFiles(c)
        if (files.isNotEmpty()) {
            val size = files.sumOf { it.length() }
            box.addView(Ui.chip(this, "🗑 Delete from phone (${fmt(size)})", Ui.Tone.RED) {
                Ui.dialog(this).setMessage("Delete the offline data of ${c.name} (${fmt(size)})? You can download it again later.")
                    .setPositiveButton("Delete") { _, _ -> files.forEach { it.delete() }; android.widget.Toast.makeText(this, "${c.name} deleted", android.widget.Toast.LENGTH_SHORT).show(); build() }
                    .setNegativeButton("Cancel", null).show()
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        card.setOnClickListener {
            if (busy) return@setOnClickListener
            if (detail.visibility == View.VISIBLE) { detail.visibility = View.GONE; openCity = null; return@setOnClickListener }
            openCity = c; detail.removeAllViews(); detail.visibility = View.VISIBLE
            if (have) {
                detail.addView(Ui.text(this, 15f, Ui.c(Pal.sub)).apply { text = "Already on this phone — nothing to download." })
                detail.addView(Ui.pill(this, if (current) "Keep ${c.name}" else "Use ${c.name}", height = 52) { choose(c, download = false) },
                    LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
                return@setOnClickListener
            }
            val info = Ui.text(this, 15f, Ui.c(Pal.sub)).apply { text = "Estimating the download…" }
            detail.addView(info)
            detail.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(-1, dp(24)))
            Thread {
                val e = MapData.estimate(this, c)
                ui.post {
                    if (isFinishing || openCity != c) return@post
                    detail.removeAllViews()
                    val chips = Ui.flex(this, 6, center = false)
                    chips.addView(if (e.routing == 0L) Ui.chip(this, "🧭 routing ✓ on phone", Ui.Tone.GREEN)
                        else Ui.chip(this, "🧭 routing ${fmt(e.routing)}" + if (e.routingPresent > 0) " (+${fmt(e.routingPresent)} already here)" else "", Ui.Tone.NEUTRAL))
                    chips.addView(if (e.map == 0L) Ui.chip(this, "🗺 map ✓ on phone", Ui.Tone.GREEN) else Ui.chip(this, "🗺 map ${fmt(e.map)}", Ui.Tone.NEUTRAL))
                    chips.addView(Ui.chip(this, "🏠 places + addresses ≈ ${fmt(e.places)}", Ui.Tone.NEUTRAL))
                    detail.addView(chips)
                    val free = getExternalFilesDir(null)!!.usableSpace
                    val enough = free > e.toDownload * 1.2
                    detail.addView(Ui.text(this, 16f, if (enough) Ui.c(Pal.text) else Ui.danger, bold = true).apply {
                        text = (if (e.exact) "" else "≈ ") + "${fmt(e.toDownload)} on the phone" + if (!enough) " — not enough free space (${fmt(free)})" else ""
                        setPadding(0, dp(8), 0, 0)
                    })
                    if (!e.exact) detail.addView(Ui.text(this, 13f, Ui.c(Pal.sub)).apply { text = "No internet right now: the numbers are rough." })
                    detail.addView(Ui.pill(this, "⬇ Download ${c.name}", height = 52) { choose(c, download = true) }.apply { isEnabled = enough; alpha = if (enough) 1f else 0.4f },
                        LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
                }
            }.start()
        }
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
    }

    private fun choose(c: City, download: Boolean) {
        val prev = Region.city
        Region.setCity(this, c)
        if (!download) { restart(); return }
        busy = true
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(8)) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val msg = Ui.text(this, 14f).apply { text = "Starting…" }
        box.addView(bar); box.addView(msg)
        val dlg = Ui.dialog(this).setTitle("Downloading ${c.name}").setView(box).setCancelable(false).show()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val err = runCatching { MapData.downloadCity(this) { f, m -> ui.post { bar.progress = (f * 1000).toInt(); msg.text = m } } }.exceptionOrNull()
            ui.post {
                dlg.dismiss(); busy = false
                if (err == null) restart()
                else {
                    prev?.let { Region.setCity(this, it) }       // keep working with the old city
                    Ui.dialog(this).setTitle("Download failed").setMessage(err.message ?: err.toString()).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    /** fresh process: routing engine, map and indexes open the new city */
    private fun restart() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    private fun fmt(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "${(b / 1_000_000).coerceAtLeast(1)} MB"

    override fun onBackPressed() { if (intent.getBooleanExtra(EXTRA_FIRST, false)) finishAffinity() else super.onBackPressed() }
}
