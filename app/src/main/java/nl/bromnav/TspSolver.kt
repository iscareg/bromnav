package nl.bromnav

/**
 * Orders delivery stops for a round trip that starts at node 0 (where you are now / the
 * restaurant) and ends at node [endNode] (the restaurant).
 *
 * Runs typically have 1-6 stops, so we try every order (6! = 720) and keep the exact best.
 * Above [EXACT_MAX] stops we fall back to nearest neighbour + 2-opt.
 *
 * Objective = total travel seconds + LATE_WEIGHT * seconds late (summed over stops).
 */
object TspSolver {
    const val EXACT_MAX = 8
    const val LATE_WEIGHT = 10.0

    data class Result(val order: List<Int>, val totalS: Double, val lateS: Double, val arrivals: DoubleArray)

    /**
     * @param t travel time matrix in seconds, t[i][j] from i to j (already scaled by your speed factor)
     * @param stops node indices to visit
     * @param deadlines seconds-from-now deadline per node index (null = no deadline)
     * @param serviceS handover time spent at each stop
     */
    fun solve(
        t: Array<DoubleArray>, stops: List<Int>, endNode: Int,
        deadlines: Map<Int, Double?>, serviceS: Double = 60.0,
    ): Result {
        if (stops.isEmpty()) return Result(emptyList(), t[0][endNode], 0.0, DoubleArray(0))
        return if (stops.size <= EXACT_MAX) exact(t, stops, endNode, deadlines, serviceS)
        else heuristic(t, stops, endNode, deadlines, serviceS)
    }

    fun evaluate(t: Array<DoubleArray>, order: List<Int>, endNode: Int, deadlines: Map<Int, Double?>, serviceS: Double): Result {
        var now = 0.0; var late = 0.0; var prev = 0
        val arr = DoubleArray(order.size)
        for ((k, s) in order.withIndex()) {
            now += t[prev][s]
            arr[k] = now
            deadlines[s]?.let { if (now > it) late += now - it }
            now += serviceS
            prev = s
        }
        now += t[prev][endNode]
        return Result(order, now, late, arr)
    }

    private fun cost(r: Result) = r.totalS + LATE_WEIGHT * r.lateS

    private fun exact(t: Array<DoubleArray>, stops: List<Int>, end: Int, dl: Map<Int, Double?>, svc: Double): Result {
        var best: Result? = null
        permute(stops.toMutableList(), 0) { perm ->
            val r = evaluate(t, perm, end, dl, svc)
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

    private fun heuristic(t: Array<DoubleArray>, stops: List<Int>, end: Int, dl: Map<Int, Double?>, svc: Double): Result {
        val left = stops.toMutableList(); val order = ArrayList<Int>(); var cur = 0
        while (left.isNotEmpty()) { val n = left.minBy { t[cur][it] }; order.add(n); left.remove(n); cur = n }
        var best = evaluate(t, order, end, dl, svc)
        var improved = true
        while (improved) {
            improved = false
            for (i in 0 until order.size - 1) for (j in i + 1 until order.size) {
                val cand = ArrayList(best.order); cand.subList(i, j + 1).reverse()
                val r = evaluate(t, cand, end, dl, svc)
                if (cost(r) < cost(best) - 1e-6) { best = r; improved = true }
            }
        }
        return best
    }
}
