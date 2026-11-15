package nl.bromnav

import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the travel-time matrix with BRouter (cached per point pair) and orders the stops.
 * All methods block: call them on the routing thread.
 */
class Planner(private val engine: BRouterEngine, private val speed: SpeedModel) {

    data class Leg(val timeS: Double, val distM: Double)

    private val cache = ConcurrentHashMap<String, Leg>()
    private var nogoKey = ""

    private fun key(a: LatLon, b: LatLon) = "${a.lonLat()}>${b.lonLat()}"

    fun leg(a: LatLon, b: LatLon, nogos: List<NoGo>): Leg {
        val nk = nogos.joinToString { it.p.lonLat() }
        if (nk != nogoKey) { cache.clear(); nogoKey = nk }
        cache[key(a, b)]?.let { return it }
        val l = if (Geo.dist(a, b) < 15) Leg(0.0, 0.0) else try {
            val r = engine.route(listOf(a, b), nogos, 20_000)
            Leg(r.timeS.toDouble(), r.lengthM.toDouble())
        } catch (e: Exception) {
            // unreachable -> beeline at 15 km/h * 1.5 so the solver still works but avoids it
            val d = Geo.dist(a, b) * 1.5; Leg(d / 4.2, d)
        }
        cache[key(a, b)] = l
        return l
    }

    /** Pre-compute legs between all stops and to/from the restaurant (run when a stop is added). */
    fun warm(stops: List<Stop>, nogos: List<NoGo>) {
        val pts = listOf(Config.RESTAURANT) + stops.map { it.pos }
        for (a in pts) for (b in pts) if (a != b) leg(a, b, nogos)
    }

    data class Plan(val order: List<Stop>, val arrivalsS: DoubleArray, val totalS: Double, val lateS: Double)

    /** Best order of [stops] starting at [start], ending at the restaurant. Times include your speed factor. */
    fun optimise(start: LatLon, stops: List<Stop>, nogos: List<NoGo>, nowMs: Long = System.currentTimeMillis()): Plan {
        val nodes = listOf(start) + stops.map { it.pos } + listOf(Config.RESTAURANT)
        val n = nodes.size
        val t = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0 until n) if (i != j && j != 0 && !(i == n - 1)) {
            val l = leg(nodes[i], nodes[j], nogos)
            t[i][j] = l.timeS * speed.factor(l.distM)
        }
        val deadlines = HashMap<Int, Double?>()
        stops.forEachIndexed { k, s -> deadlines[k + 1] = s.deadline?.let { (it - nowMs) / 1000.0 } }
        val r = TspSolver.solve(t, (1..stops.size).toList(), n - 1, deadlines, Config.SERVICE_S)
        return Plan(r.order.map { stops[it - 1] }, r.arrivals, r.totalS, r.lateS)
    }

    /**
     * Personal ETAs (epoch ms) for [order] when the first stop is [firstLegRemainingS] away
     * (already speed-corrected).
     */
    fun etas(order: List<Stop>, firstLegRemainingS: Double, nogos: List<NoGo>, nowMs: Long = System.currentTimeMillis()): List<Long> {
        val out = ArrayList<Long>()
        var tS = firstLegRemainingS
        for ((k, s) in order.withIndex()) {
            if (k > 0) {
                val l = leg(order[k - 1].pos, s.pos, nogos)
                tS += Config.SERVICE_S + l.timeS * speed.factor(l.distM)
            }
            out.add(nowMs + (tS * 1000).toLong())
        }
        return out
    }
}
