package nl.bromnav

/**
 * Orders delivery stops for a round trip that starts at node 0 (where you are now / the
 * restaurant) and ends at node [endNode] (the restaurant).
 *
 * Runs typically have 1-6 stops. Up to [EXACT_MAX] stops the exact best order is found by a
 * depth-first search over all orders that abandons a partial order as soon as it already costs more
 * than the best complete one (every cost term only grows along a route). Above that: nearest
 * neighbour + 2-opt.
 *
 * Objective = total travel seconds
 *           + mode.late   * seconds late                       (summed over stops)
 *           + mode.margin * seconds inside the safety margin   (arriving < MARGIN_S before the deadline)
 *           + mode.hot    * sum of heat x arrival time          (hot = little time left: visit early)
 * The weights come from the [OrderMode] chosen in the menu (shortest / hottest first / on time).
 */
/** How "Auto order" weighs distance against deadlines (switchable in the menu). */
enum class OrderMode(val code: Int, val label: String, val late: Double, val margin: Double, val hot: Double) {
    /** total time as small as possible; deadlines only break ties */
    SHORTEST(0, "Shortest total route", 0.5, 0.0, 0.0),
    /** orders with the least time left first, but distance still counts */
    HOTTEST(1, "Hottest orders first", 10.0, 2.0, 3.0),
    /** on time versus the promised time: avoid being late, keep a safety margin (default) */
    ON_TIME(2, "On time (promised times)", 10.0, 2.0, 0.0);

    companion object {
        @Volatile var current = ON_TIME
        fun load(ctx: android.content.Context) {
            val c = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getInt("orderMode", ON_TIME.code)
            current = entries.firstOrNull { it.code == c } ?: ON_TIME
        }
        fun set(ctx: android.content.Context, m: OrderMode) {
            current = m
            ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putInt("orderMode", m.code).apply()
        }
    }
}

object TspSolver {
    const val EXACT_MAX = 10
    const val MARGIN_S = 300.0

    data class Result(val order: List<Int>, val totalS: Double, val lateS: Double, val arrivals: DoubleArray, val marginS: Double = 0.0,
                      val hotS: Double = 0.0, val mode: OrderMode = OrderMode.ON_TIME)

    /**
     * How hot an order is: 1 = due now (or overdue), 0 = an hour or more left (squared: rises steeply near the deadline); used by [OrderMode.HOTTEST]
     * as weight on its arrival time, so hot orders are visited early.
     */
    fun heat(deadline: Double?): Double = if (deadline == null) 0.0 else (1.0 - deadline / 3600.0).coerceIn(0.0, 1.0).let { it * it }

    /**
     * @param t travel time matrix in seconds, t[i][j] from i to j (already scaled by your speed factor)
     * @param stops node indices to visit
     * @param deadlines seconds-from-now deadline per node index (null = no deadline)
     * @param serviceS handover time spent at each stop
     */
    fun solve(
        t: Array<DoubleArray>, stops: List<Int>, endNode: Int,
        deadlines: Map<Int, Double?>, serviceS: Double = 60.0,
        earliest: Map<Int, Double?> = emptyMap(), mode: OrderMode = OrderMode.ON_TIME,
    ): Result {
        if (stops.isEmpty()) return Result(emptyList(), t[0][endNode], 0.0, DoubleArray(0), mode = mode)
        val ev = { order: List<Int> -> evaluate(t, order, endNode, deadlines, serviceS, earliest, mode) }
        return if (stops.size <= EXACT_MAX) exact(t, stops, endNode, deadlines, serviceS, earliest, mode) else heuristic(t, stops, ev)
    }

    /** [earliest]: "not before" per node (seconds from now) for exact-time orders; arriving earlier = waiting. */
    fun evaluate(t: Array<DoubleArray>, order: List<Int>, endNode: Int, deadlines: Map<Int, Double?>, serviceS: Double,
                 earliest: Map<Int, Double?> = emptyMap(), mode: OrderMode = OrderMode.ON_TIME): Result {
        var now = 0.0; var late = 0.0; var margin = 0.0; var hot = 0.0; var prev = 0
        val arr = DoubleArray(order.size)
        for ((k, s) in order.withIndex()) {
            now += t[prev][s]
            val e = earliest[s]
            if (e != null && now < e) now = e                 // exact-time order: wait until the requested time
            arr[k] = now
            hot += heat(deadlines[s]) * now
            deadlines[s]?.let { if (now > it) late += now - it }
            if (e != null) {
                // exact-time order: the requested time is the target, every second after it costs
                if (now > e) margin += now - e
            } else deadlines[s]?.let {
                // ASAP order: keep a safety margin before the promised time
                if (now > it - MARGIN_S) margin += minOf(now - (it - MARGIN_S), MARGIN_S)
            }
            now += serviceS
            prev = s
        }
        now += t[prev][endNode]
        return Result(order, now, late, arr, margin, hot, mode)
    }

    /** Exact optimum (same objective as [evaluate]) by branch and bound; no allocation per order. */
    private fun exact(t: Array<DoubleArray>, stops: List<Int>, endNode: Int, deadlines: Map<Int, Double?>, serviceS: Double,
                      earliest: Map<Int, Double?>, mode: OrderMode): Result {
        val lw = mode.late; val mw = mode.margin; val hw = mode.hot
        val n = stops.size
        val st = stops.toIntArray()
        val dl = DoubleArray(n) { deadlines[st[it]] ?: Double.NaN }
        val ea = DoubleArray(n) { earliest[st[it]] ?: Double.NaN }
        val heat = DoubleArray(n) { heat(deadlines[st[it]]) }
        val used = BooleanArray(n); val cur = IntArray(n); val bestOrd = IntArray(n)
        // lower bound for what is still to come: every unvisited stop is reached over at least its
        // cheapest incoming edge and costs one handover; the way home costs at least the cheapest return
        val minIn = DoubleArray(n) { k -> (listOf(0) + st.filterIndexed { i, _ -> i != k }).minOf { t[it][st[k]] } }
        val minEnd = st.minOf { t[it][endNode] }
        var restIn = minIn.sum()
        var best = Double.MAX_VALUE
        fun dfs(depth: Int, prev: Int, now: Double, pen: Double) {
            val bound = now + pen + restIn + (n - depth) * serviceS + (if (depth < n) minEnd else 0.0)
            if (bound >= best - 1e-9) return                                 // can't beat the best order: cut
            if (depth == n) {
                val c = now + t[prev][endNode] + pen
                if (c < best) { best = c; cur.copyInto(bestOrd) }
                return
            }
            for (k in 0 until n) {
                if (used[k]) continue
                var tm = now + t[prev][st[k]]
                val e = ea[k]; val d = dl[k]
                if (!e.isNaN() && tm < e) tm = e                              // exact-time order: wait
                var p = pen
                if (!d.isNaN() && tm > d) p += lw * (tm - d)
                if (!e.isNaN()) { if (tm > e) p += mw * (tm - e) }
                else if (!d.isNaN() && tm > d - MARGIN_S) p += mw * minOf(tm - (d - MARGIN_S), MARGIN_S)
                p += hw * heat[k] * tm
                used[k] = true; cur[depth] = k; restIn -= minIn[k]
                dfs(depth + 1, st[k], tm + serviceS, p)
                used[k] = false; restIn += minIn[k]
            }
        }
        dfs(0, 0, 0.0, 0.0)
        return evaluate(t, bestOrd.map { st[it] }, endNode, deadlines, serviceS, earliest, mode)
    }

    /** objective value of a result (what the solver minimises) */
    fun cost(r: Result) = r.totalS + r.mode.late * r.lateS + r.mode.margin * r.marginS + r.mode.hot * r.hotS

    private fun heuristic(t: Array<DoubleArray>, stops: List<Int>, ev: (List<Int>) -> Result): Result {
        val left = stops.toMutableList(); val order = ArrayList<Int>(); var cur = 0
        while (left.isNotEmpty()) { val n = left.minBy { t[cur][it] }; order.add(n); left.remove(n); cur = n }
        var best = ev(order)
        var improved = true
        while (improved) {
            improved = false
            for (i in 0 until order.size - 1) for (j in i + 1 until order.size) {
                val cand = ArrayList(best.order); cand.subList(i, j + 1).reverse()
                val r = ev(cand)
                if (cost(r) < cost(best) - 1e-6) { best = r; improved = true }
            }
        }
        return best
    }
}
