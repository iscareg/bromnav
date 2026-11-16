package nl.bromnav

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen settings, in sections. Choices that are plain settings (vehicle, auto order, theme)
 * are stored here; things the map screen has to do (edit roads, update maps, switch mode...) are
 * returned as [EXTRA_ACTION] and carried out by MainActivity.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_SIMPLE = "simple"
        const val EXTRA_AVOID = "avoidMarks"
        const val EXTRA_NAV = "navigating"
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private lateinit var col: LinearLayout
    private var simple = false
    private var avoid = 0
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.init(this)
        simple = intent.getBooleanExtra(EXTRA_SIMPLE, false)
        avoid = intent.getIntExtra(EXTRA_AVOID, 0)
        navigating = intent.getBooleanExtra(EXTRA_NAV, false)
        build()
    }

    private val corrections = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        val key = res.data?.getStringExtra(RoadCorrectionsActivity.EXTRA_GOTO)
        if (key != null) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, "goto").putExtra(RoadCorrectionsActivity.EXTRA_GOTO, key)); finish() }
        else build()                     // corrections may have been deleted
    }

    private fun finishWith(action: String) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action)); finish() }

    private fun build() {
        // wide side margins and the back button centred at the bottom: nothing in the corners
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(20), dp(28), dp(24)) }
        col.addView(TextView(this).apply {
            text = "⚙  Settings"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(Pal.text)); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })

        section("Delivery")
        row("🔀", "Auto order priority", OrderMode.current.label) { chooseOrderMode() }
        row(Vehicle.current.icon, "Vehicle", Vehicle.current.label) { chooseVehicle() }
        row("📊", "Stats & history", "Laps, time per order, your speed") { startActivity(Intent(this, StatsActivity::class.java)) }

        section("Roads & map")
        row("✏", "Edit road access", "Tap a road on the map to allow or forbid it") { finishWith("edit_roads") }
        val n = RoadOverrides.all().size
        row("🛣", "My road corrections", if (n == 0) "none" else "$n section${if (n == 1) "" else "s"} · list, show on map, delete") {
            corrections.launch(Intent(this, RoadCorrectionsActivity::class.java))
        }
        row("⛔", "Avoid marks", if (avoid == 0) "none (long-press the map to add one)" else "$avoid · tap to remove all") {
            if (avoid > 0) confirm("Remove all $avoid avoid marks?") { finishWith("clear_avoid") }
        }
        row("⬇", "Update maps from internet", "Routing data, map, places and addresses (~310 MB)") { finishWith("update_maps") }

        section("App")
        row(if (simple) "🛵" else "🧭", if (simple) "Switch to delivery mode" else "Switch to simple navigation",
            if (simple) "Receipts, multi-stop runs, stats" else "Search a place or address and go; no stats") {
            finishWith(if (simple) "mode_delivery" else "mode_simple")
        }
        row("🌓", "Theme", when (Pal.choice(this)) { "dark" -> "Dark"; "light" -> "Light"; else -> "Automatic (follows the phone)" }) { chooseTheme() }
        val book = PdokGeocoder.book
        row("ℹ", "About", "BromNav ${BuildConfig.VERSION_NAME} · offline addresses: " + (book?.let { "${it.groupCount} streets" } ?: "loading…")) {}

        if (BuildConfig.DEBUG_TOOLS && navigating) {
            section("Debug")
            row("🧪", "Simulate ride / stop simulation", "Fake GPS along the route") { finishWith("simulate") }
        }

        col.addView(Ui.pill(this, "← Back to the map") { finish() },
            LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(24); leftMargin = dp(20); rightMargin = dp(20) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.page); addView(col) })
    }

    private fun section(title: String) = col.addView(TextView(this).apply {
        text = title.uppercase(); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        setTextColor(Ui.primary); setPadding(dp(4), dp(22), 0, dp(8))
    })

    private fun row(icon: String, title: String, sub: String, onClick: () -> Unit) {
        val card = Ui.card(this, 18, 1).apply { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(66)
            setPadding(dp(12), dp(10), dp(14), dp(10))
        }
        // icon in a tinted circle
        r.addView(TextView(this).apply {
            text = icon; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.toneBg(Ui.Tone.BLUE)) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
        texts.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(Color.parseColor(Pal.text)) })
        texts.addView(TextView(this).apply { text = sub; textSize = 14f; setTextColor(Color.parseColor(Pal.sub)) })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(TextView(this).apply { text = "›"; textSize = 26f; setTextColor(Color.parseColor(Pal.faint)) })
        card.addView(r)
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun confirm(msg: String, ok: () -> Unit) =
        Ui.dialog(this).setMessage(msg).setPositiveButton("Remove") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()

    private fun chooseOrderMode() {
        val ms = OrderMode.entries
        val help = mapOf(OrderMode.SHORTEST to "least riding in total; times only break ties",
            OrderMode.HOTTEST to "least time left first, distance still counts",
            OrderMode.ON_TIME to "as few late orders as possible, with a safety margin")
        Ui.dialog(this).setTitle("Auto order priority")
            .setSingleChoiceItems(ms.map { "${it.label}\n   ${help[it]}" }.toTypedArray(), ms.indexOf(OrderMode.current)) { d, w ->
                d.dismiss(); OrderMode.set(this, ms[w]); build()
            }.show()
    }

    private fun chooseVehicle() {
        val vs = Vehicle.entries
        Ui.dialog(this).setTitle("What do you ride?")
            .setSingleChoiceItems(vs.map { "${it.icon}  ${it.label}" }.toTypedArray(), vs.indexOf(Vehicle.current)) { d, w ->
                d.dismiss(); Vehicle.set(this, vs[w]); build()
            }.show()
    }

    private fun chooseTheme() {
        val opts = listOf("auto" to "Automatic (follows the phone)", "light" to "Light", "dark" to "Dark")
        Ui.dialog(this).setTitle("Theme")
            .setSingleChoiceItems(opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == Pal.choice(this) }) { d, w ->
                d.dismiss(); Pal.setChoice(this, opts[w].first)          // AppCompat recreates the screens
            }.show()
    }
}
