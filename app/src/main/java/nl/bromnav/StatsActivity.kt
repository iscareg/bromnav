package nl.bromnav

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** History & statistics: laps, time per order, average speed, and every order. */
class StatsActivity : AppCompatActivity() {

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private val dayFmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.UK)
    private val hm = SimpleDateFormat("HH:mm", Locale.UK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val h = History(this)
        val speed = SpeedModel(this)
        // centred column with wide side margins: nothing important in the corners
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(28), dp(32), dp(48)) }

        fun title(s: String) = col.addView(TextView(this).apply {
            text = s; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#14335e")); setPadding(0, dp(18), 0, dp(6))
        })
        fun line(s: String, size: Float = 15f, color: String = "#222222") = col.addView(TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(3), 0, dp(3))
        })
        fun mmss(s: Double) = "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())

        title("Overview")
        val o = h.orders; val l = h.laps
        if (o.isEmpty()) line("No deliveries recorded yet. Stats appear after your first \"Delivered ✓\".")
        else {
            val totalDist = o.sumOf { it.distM }; val totalTime = o.sumOf { it.actualS }
            line("Orders delivered: ${o.size}")
            line("Average time per order: ${mmss(o.map { it.actualS }.average())} min")
            line("Average leg distance: %.1f km".format(o.map { it.distM }.average() / 1000))
            line("Average speed: %.1f km/h".format(if (totalTime > 0) totalDist / totalTime * 3.6 else 0.0))
            val withDl = o.filter { it.deadline != null }
            if (withDl.isNotEmpty()) {
                val onTime = withDl.count { (it.lateMin ?: 0) <= 0 }
                line("On time: $onTime / ${withDl.size} (%d%%)".format(onTime * 100 / withDl.size))
            }
            line("You vs app estimate: ×%.2f".format(o.sumOf { it.actualS } / o.sumOf { it.predictedS }.coerceAtLeast(1.0)))
        }
        if (l.isNotEmpty()) {
            line("Laps (restaurant → restaurant): ${l.size}")
            line("Average lap time: ${mmss(l.map { it.durationS }.average())} min")
            line("Average orders per lap: %.1f".format(l.map { it.orders }.average()))
            line("Average lap distance: %.1f km".format(l.map { it.distM }.average() / 1000))
        }
        line("Your speed multiplier (time vs app): short legs ×%.2f · medium ×%.2f · long ×%.2f"
            .format(speed.factor(500.0), speed.factor(2500.0), speed.factor(6000.0)), 13f, "#777777")

        if (l.isNotEmpty()) {
            title("Laps")
            l.asReversed().take(50).forEach { lap ->
                line("${dayFmt.format(Date(lap.startMs))} · ${mmss(lap.durationS)} min · ${lap.orders} orders · %.1f km".format(lap.distM / 1000))
            }
        }
        if (o.isNotEmpty()) {
            title("Orders")
            o.asReversed().take(200).forEach { r ->
                val late = r.lateMin?.let { if (it > 0) " · ${it} min LATE" else " · on time (${-it} min early)" } ?: ""
                line((if (r.orderNo.isNotEmpty()) "#${r.orderNo}  " else "") + r.label, 15f, "#111111")
                line("   ${dayFmt.format(Date(r.deliveredAt))} · ${mmss(r.actualS)} min (app ${mmss(r.predictedS)}) · %.1f km · %.0f km/h$late"
                    .format(r.distM / 1000, r.avgKmh), 13f, if ((r.lateMin ?: 0) > 0) "#c62828" else "#555555")
            }
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(col) })
    }
}
