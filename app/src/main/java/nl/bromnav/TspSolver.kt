package nl.bromnav

/**
 * Orders delivery stops for a round trip that starts at node 0 (where you are now / the
 * restaurant) and ends at node [endNode] (the restaurant).
 *
 * Runs typically have 1-6 stops, so we try every order (6! = 720) and keep the exact best.
 * Above [EXACT_MAX] stops we fall back to nearest neighbour + 2-opt.
 *
 * Objective = total travel seconds
 *           + LATE_WEIGHT   * seconds late                      (summed over stops)
 *           + MARGIN_WEIGHT * seconds inside the safety margin  (arriving < MARGIN_S before the deadline)
 * The margin term makes the plan keep a buffer for tight orders instead of only minimising time.
 */
object TspSolver {
    const val EXACT_MAX = 8
    const val LATE_WEIGHT = 10.0
    const val MARGIN_WEIGHT = 2.0
    const val MARGIN_S = 300.0

    data class Result(val order: List<Int>, val totalS: Double, val lateS: Double, val arrivals: DoubleArray, val marginS: Double = 0.0)

    /**
     * @param t travel time matrix in seconds, t[i][j] from i to j (already scaled by your speed factor)
     * @param stops node indices to visit
     * @param deadlines seconds-from-now deadline per node index (null = no deadline)
     * @param serviceS handover time spent at each stop
     */
    fun solve(
        t: Array<DoubleArray>, stops: List<Int>, endNode: Int,
        deadlines: Map<Int, Double?>, serviceS: Double = 60.0,
        earliest: Map<Int, Double?> = emptyMap(),
    ): Result {
        if (stops.isEmpty()) return Result(emptyList(), t[0][endNode], 0.0, DoubleArray(0))
        val ev = { order: List<Int> -> evaluate(t, order, endNode, deadlines, serviceS, earliest) }
        return if (stops.size <= EXACT_MAX) exact(stops, ev) else heuristic(t, stops, ev)
    }

    /** [earliest]: "not before" per node (seconds from now) for exact-time orders; arriving earlier = waiting. */
    fun evaluate(t: Array<DoubleArray>, order: List<Int>, endNode: Int, deadlines: Map<Int, Double?>, serviceS: Double,
                 earliest: Map<Int, Double?> = emptyMap()): Result {
        var now = 0.0; var late = 0.0; var margin = 0.0; var prev = 0
        val arr = DoubleArray(order.size)
        for ((k, s) in order.withIndex()) {
            now += t[prev][s]
            val e = earliest[s]
            if (e != null && now < e) now = e                 // exact-time order: wait until the requested time
            arr[k] = now
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
        return Result(order, now, late, arr, margin)
    }

    private fun cost(r: Result) = r.totalS + LATE_WEIGHT * r.lateS + MARGIN_WEIGHT * r.marginS

    private fun exact(stops: List<Int>, ev: (List<Int>) -> Result): Result {
        var best: Result? = null
        permute(stops.toMutableList(), 0) { perm ->
            val r = ev(perm)
            if (best == null || cost(r) < cost(best!!)) best = r
        }
        return best!!
    }

    private fun permute(a: MutableList<Int>, k: Int, visit: (List<Int>) -> Unit) {
        if (k == a.size) { visit(ArrayList(a)); return }
        for (i in k until a.size) {
            a[k] = a[i].also { a[i] = a[k] }
            permute(a, k + 1, visit)
            a[k] = a[i].also { a[i] = a[k] }
        }
    }

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
