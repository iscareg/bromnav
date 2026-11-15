package nl.bromnav

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Your road corrections as a list: what kind of road, near which address, how long; tick one or
 * more to delete them, or "📍 Show" to jump to it on the map (returned as [EXTRA_GOTO]).
 */
class RoadCorrectionsActivity : AppCompatActivity() {

    companion object { const val EXTRA_GOTO = "gotoKey" }   // "a,b" node ids of the section to show

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private val selected = HashSet<Pair<Long, Long>>()
    private val near = HashMap<Pair<Long, Long>, String>()
    private lateinit var deleteBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.init(this)
        build()
        // nearest address of each section, for recognising it (address book scan: background)
        Thread {
            val book = PdokGeocoder.book ?: return@Thread
            for (e in RoadOverrides.all()) {
                val mid = e.geom.getOrNull(e.geom.size / 2) ?: continue
                book.nearest(mid)?.let { near[e.a to e.b] = it }
            }
            runOnUiThread { if (!isFinishing) build() }
        }.start()
    }

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        col.addView(TextView(this).apply {
            text = "🛣  My road corrections"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(Pal.text))
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4))
        })
        val all = RoadOverrides.all().sortedBy { near[it.a to it.b] ?: "~" }
        col.addView(TextView(this).apply {
            text = if (all.isEmpty()) "No corrections yet. Long-press a road on the map, or Settings → Edit road access."
                   else "Tick sections to delete them, or tap 📍 to see one on the map."
            textSize = 14f; setTextColor(Color.parseColor(Pal.sub)); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12))
        })
        selected.retainAll(all.map { it.a to it.b }.toSet())
        for (e in all) {
            val key = e.a to e.b
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(66)
                setPadding(dp(6), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply { setColor(Pal.card); cornerRadius = dp(14).toFloat() }
            }
            val cb = CheckBox(this).apply {
                isChecked = key in selected
                setOnCheckedChangeListener { _, c -> if (c) selected.add(key) else selected.remove(key); updateDelete() }
            }
            row.addView(cb)
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(6), 0) }
            val kind = when (e.mode) {
                RoadOverrides.ALLOW -> "✅ Allowed"; RoadOverrides.ALLOW_BOTH -> "✅ Allowed, both directions"; else -> "⛔ Forbidden"
            }
            val len = e.geom.zipWithNext { p, q -> Geo.dist(p, q) }.sum().toInt()
            val tags = e.tags.split(' ').filter { it.startsWith("highway=") || it.startsWith("moped=") || it.startsWith("access=") || it.startsWith("bicycle=") }
                .joinToString(" · ") { it.replace("highway=", "") }
            texts.addView(TextView(this).apply { text = kind; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(Pal.text)) })
            texts.addView(TextView(this).apply {
                text = (near[key]?.let { "near $it\n" } ?: "") + "$tags · $len m"; textSize = 14f; setTextColor(Color.parseColor(Pal.sub))
            })
            texts.setOnClickListener { cb.isChecked = !cb.isChecked }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "📍 Show"; isAllCaps = false; textSize = 15f
                setOnClickListener { setResult(RESULT_OK, Intent().putExtra(EXTRA_GOTO, "${e.a},${e.b}")); finish() }
            }, LinearLayout.LayoutParams(-2, dp(48)))
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        deleteBtn = button("🗑 Delete", "#d93025") {
            val n = selected.size
            AlertDialog.Builder(this).setMessage("Delete $n road correction${if (n == 1) "" else "s"}? The map data is used there again.")
                .setPositiveButton("Delete") { _, _ -> for ((a, b) in selected.toList()) RoadOverrides.remove(a, b); selected.clear(); build() }
                .setNegativeButton("Cancel", null).show()
        }
        btns.addView(deleteBtn)
        btns.addView(button(if (selected.size == all.size && all.isNotEmpty()) "Select none" else "Select all", "#6b7280") {
            if (selected.size == all.size) selected.clear() else selected.addAll(all.map { it.a to it.b }); build()
        }.apply { visibility = if (all.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE })
        col.addView(btns)
        col.addView(button("← Back", "#1f6fe5") { finish() }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12); leftMargin = dp(20); rightMargin = dp(20) })
        updateDelete()
        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.page); addView(col) })
    }

    private fun updateDelete() {
        deleteBtn.isEnabled = selected.isNotEmpty(); deleteBtn.alpha = if (selected.isEmpty()) 0.4f else 1f
        deleteBtn.text = if (selected.isEmpty()) "🗑 Delete" else "🗑 Delete ${selected.size}"
    }

    private fun button(t: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(24).toFloat() }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(5), 0, dp(5), 0) }
    }
}
