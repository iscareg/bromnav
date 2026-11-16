package nl.bromnav

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** History & statistics: KPI tiles, charts (MPAndroidChart), laps and every order. */
class StatsActivity : AppCompatActivity() {

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private val dayFmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.UK)
    private lateinit var col: LinearLayout

    companion object { const val EXTRA_SIMPLE = "simple" }
    private var simpleTab = false
    private fun mmss(s: Double) = "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.init(this)
        simpleTab = intent.getBooleanExtra(EXTRA_SIMPLE, false)
        build()
    }

    private fun build() {
        val h = History(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(32)) }
        col.addView(Ui.text(this, 24f, bold = true, center = true).apply { text = "📊  Stats & history"; setPadding(0, dp(8), 0, dp(10)) })
        // tabs: delivery / simple navigation
        val tabs = Ui.flex(this, 8)
        tabs.addView(Ui.chip(this, "🛵 Delivery", if (!simpleTab) Ui.Tone.BLUE else Ui.Tone.NEUTRAL, big = true) { if (simpleTab) { simpleTab = false; build() } })
        tabs.addView(Ui.chip(this, "🧭 Simple navigation", if (simpleTab) Ui.Tone.BLUE else Ui.Tone.NEUTRAL, big = true) { if (!simpleTab) { simpleTab = true; build() } })
        col.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (simpleTab) simpleStats(h) else deliveryStats(h)
        col.addView(Ui.pill(this, "← Back") { finish() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(20); leftMargin = dp(20); rightMargin = dp(20) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.page); addView(col) })
    }

    private fun kpi(value: String, label: String, tone: Ui.Tone) = Ui.card(this, 18, 0, Ui.toneBg(tone)).apply {
        val b = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10)); minimumWidth = dp(140) }
        b.addView(Ui.text(context, 24f, Ui.toneFg(tone), bold = true, center = true).apply { text = value })
        b.addView(Ui.text(context, 13f, Ui.toneFg(tone), center = true).apply { text = label })
        addView(b)
    }

    /** simple navigation: averages, most visited addresses, and your "runs" (same start -> finish more than 3 times) */
    private fun simpleStats(h: History) {
        val t = h.trips
        if (t.isEmpty()) {
            col.addView(card { it.addView(Ui.text(this, 16f, Ui.c(Pal.sub), center = true).apply {
                text = "No trips yet.\nEvery simple-navigation trip (Go → arrived or End) is recorded here." }) })
            return
        }
        val kpis = Ui.flex(this, 8)
        kpis.addView(kpi("${t.size}", "trips", Ui.Tone.BLUE))
        kpis.addView(kpi("%.1f".format(t.sumOf { it.distM } / t.sumOf { it.durationS }.coerceAtLeast(1.0) * 3.6), "km/h average", Ui.Tone.NEUTRAL))
        kpis.addView(kpi("%.1f km".format(t.map { it.distM }.average() / 1000), "average trip", Ui.Tone.NEUTRAL))
        kpis.addView(kpi(mmss(t.map { it.durationS }.average()), "min per trip", Ui.Tone.NEUTRAL))
        col.addView(kpis, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        section("Most visited")
        col.addView(card { c ->
            for (p in TripStats.places(t).take(8)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(Ui.text(this, 16f, bold = true).apply { text = p.label })
                texts.addView(Ui.text(this, 14f, Ui.c(Pal.sub)).apply { text = "about ${mmss(p.avgDurationS)} min · last ${dayFmt.format(Date(p.lastMs))}" })
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(Ui.chip(this, "${p.count}×", Ui.Tone.BLUE, big = true))
                c.addView(row)
            }
        })

        val routes = TripStats.routes(t)
        section("Your runs (same start and finish, more than 3 times)")
        if (routes.isEmpty()) col.addView(card { it.addView(Ui.text(this, 15f, Ui.c(Pal.sub)).apply {
            text = "A run appears here once you rode exactly the same start → finish more than 3 times." }) })
        for (r in routes) col.addView(card { c ->
            c.addView(Ui.text(this, 16f, bold = true).apply { text = "${r.from}  →  ${r.to}" })
            val chips = Ui.flex(this, 6, center = false).apply { setPadding(0, dp(6), 0, dp(6)) }
            chips.addView(Ui.chip(this, "${r.count} times", Ui.Tone.BLUE))
            chips.addView(Ui.chip(this, "avg ${mmss(r.avgDurationS)}", Ui.Tone.NEUTRAL))
            chips.addView(Ui.chip(this, "best ${mmss(r.bestDurationS)}", Ui.Tone.GREEN))
            chips.addView(Ui.chip(this, "%.1f km/h".format(r.avgKmh), Ui.Tone.NEUTRAL))
            c.addView(chips)
            // every run's time, oldest first
            val line = LineChart(this).apply {
                data = LineData(LineDataSet(r.trips.mapIndexed { i, x -> Entry(i.toFloat(), (x.durationS / 60).toFloat()) }, "minutes").apply {
                    color = Ui.action; setCircleColor(Ui.action); lineWidth = 2.5f; circleRadius = 3.5f; setDrawValues(false)
                })
                styleChart(this); xAxis.setDrawLabels(false)
            }
            c.addView(line, LinearLayout.LayoutParams(-1, dp(150)))
            for (x in r.trips.asReversed().take(5)) c.addView(Ui.text(this, 14f, Ui.c(Pal.sub)).apply {
                text = "${dayFmt.format(Date(x.startMs))} · ${mmss(x.durationS)} min · %.1f km/h".format(x.avgKmh) })
        })
    }

    private fun deliveryStats(h: History) {
        val speed = SpeedModel(this)

        val o = h.orders; val l = h.laps
        if (o.isEmpty()) {
            col.addView(card { it.addView(Ui.text(this, 16f, Ui.c(Pal.sub), center = true).apply {
                text = "No deliveries recorded yet.\nStats appear after your first \"Delivered ✓\"." }) })
        } else {
            val totalDist = o.sumOf { it.distM }; val totalTime = o.sumOf { it.actualS }
            val withDl = o.filter { it.deadline != null }
            val onTime = withDl.count { (it.lateMin ?: 0) <= 0 }
            // KPI tiles (wrapping)
            val kpis = Ui.flex(this, 8)
            kpis.addView(kpi("${o.size}", "orders", Ui.Tone.BLUE))
            kpis.addView(kpi(mmss(o.map { it.actualS }.average()), "min per order", Ui.Tone.NEUTRAL))
            kpis.addView(kpi("%.1f".format(if (totalTime > 0) totalDist / totalTime * 3.6 else 0.0), "km/h average", Ui.Tone.NEUTRAL))
            if (withDl.isNotEmpty()) kpis.addView(kpi("${onTime * 100 / withDl.size}%", "on time", if (onTime * 100 / withDl.size >= 90) Ui.Tone.GREEN else Ui.Tone.ORANGE))
            kpis.addView(kpi("×%.2f".format(o.sumOf { it.actualS } / o.sumOf { it.predictedS }.coerceAtLeast(1.0)), "you vs app", Ui.Tone.PURPLE))
            if (l.isNotEmpty()) kpis.addView(kpi(mmss(l.map { it.durationS }.average()), "min per lap", Ui.Tone.NEUTRAL))
            col.addView(kpis, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

            // chart 1: last 20 orders, your time vs the app estimate (minutes)
            val last = o.takeLast(20)
            section("Last ${last.size} orders: your time vs app estimate (min)")
            val bars = BarChart(this).apply {
                val actual = BarDataSet(last.mapIndexed { i, r -> BarEntry(i.toFloat(), (r.actualS / 60).toFloat()) }, "you").apply { color = Ui.action; valueTextColor = Ui.c(Pal.text) }
                val app = BarDataSet(last.mapIndexed { i, r -> BarEntry(i.toFloat(), (r.predictedS / 60).toFloat()) }, "app").apply { color = Ui.c("#9AA4B1"); setDrawValues(false) }
                data = BarData(actual, app).apply { barWidth = 0.4f; setValueTextSize(9f) }
                if (last.size > 1) groupBars(-0.5f, 0.1f, 0.05f)
                styleChart(this); xAxis.setDrawLabels(false)
            }
            col.addView(card { it.addView(bars, LinearLayout.LayoutParams(-1, dp(220))) })

            // chart 2: minutes late (negative = early) per order
            if (withDl.size >= 2) {
                val lw = withDl.takeLast(30)
                section("Minutes late (below 0 = early)")
                val line = LineChart(this).apply {
                    data = LineData(LineDataSet(lw.mapIndexed { i, r -> Entry(i.toFloat(), (r.lateMin ?: 0).toFloat()) }, "late").apply {
                        color = Ui.warning; setCircleColor(Ui.warning); lineWidth = 2.5f; circleRadius = 3.5f; setDrawValues(false)
                        mode = LineDataSet.Mode.CUBIC_BEZIER
                    })
                    styleChart(this); xAxis.setDrawLabels(false)
                    axisLeft.addLimitLine(com.github.mikephil.charting.components.LimitLine(0f).apply { lineColor = Ui.success; lineWidth = 1.5f })
                }
                col.addView(card { it.addView(line, LinearLayout.LayoutParams(-1, dp(200))) })
            }
        }
        section("Your speed multiplier (time vs app)")
        col.addView(card { c ->
            val f = Ui.flex(this, 8)
            for ((label, d) in listOf("short legs" to 500.0, "medium" to 2500.0, "long" to 6000.0))
                f.addView(Ui.chip(this, "$label ×%.2f".format(speed.factor(d)), Ui.Tone.NEUTRAL, big = true))
            c.addView(f)
        })

        if (l.isNotEmpty()) {
            section("Laps")
            col.addView(card { c ->
                l.asReversed().take(50).forEach { lap ->
                    c.addView(Ui.text(this, 15f).apply {
                        text = "${dayFmt.format(Date(lap.startMs))} · ${mmss(lap.durationS)} min · ${lap.orders} orders · %.1f km".format(lap.distM / 1000)
                        setPadding(0, dp(4), 0, dp(4)) })
                }
            })
        }
        if (o.isNotEmpty()) {
            section("Orders")
            col.addView(card { c ->
                o.asReversed().take(200).forEach { r ->
                    val late = r.lateMin?.let { if (it > 0) "${it} min LATE" else "on time (${-it} min early)" }
                    c.addView(Ui.text(this, 16f, bold = true).apply { text = (if (r.orderNo.isNotEmpty()) "#${Stop.shortOrder(r.orderNo)}  " else "") + r.label; setPadding(0, dp(8), 0, 0) })
                    c.addView(Ui.text(this, 14f, if ((r.lateMin ?: 0) > 0) Ui.danger else Ui.c(Pal.sub)).apply {
                        text = "${dayFmt.format(Date(r.deliveredAt))} · ${mmss(r.actualS)} min (app ${mmss(r.predictedS)}) · %.1f km · %.0f km/h".format(r.distM / 1000, r.avgKmh) +
                            (late?.let { " · $it" } ?: "") })
                }
            })
        }
    }

    private fun section(t: String) = col.addView(Ui.text(this, 14f, Ui.primary, bold = true).apply { text = t.uppercase(); letterSpacing = 0.05f; setPadding(dp(4), dp(18), 0, dp(6)) })

    private fun card(fill: (LinearLayout) -> Unit) = Ui.card(this, 20, 2).apply {
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        fill(inner); addView(inner)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun styleChart(c: com.github.mikephil.charting.charts.BarLineChartBase<*>) {
        val fg = Ui.c(Pal.sub)
        c.description.isEnabled = false; c.setTouchEnabled(false); c.setDrawGridBackground(false)
        c.legend.textColor = fg; c.legend.textSize = 13f
        c.axisRight.isEnabled = false
        c.axisLeft.textColor = fg; c.axisLeft.gridColor = Ui.c(if (Pal.dark) "#2A2F37" else "#E9ECF1"); c.axisLeft.textSize = 12f
        c.xAxis.position = XAxis.XAxisPosition.BOTTOM; c.xAxis.setDrawGridLines(false); c.xAxis.textColor = fg
        c.setNoDataTextColor(fg); c.setBackgroundColor(Color.TRANSPARENT)
    }
}
