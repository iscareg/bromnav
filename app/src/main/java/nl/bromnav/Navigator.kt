package nl.bromnav

/**
 * Follows a [Route] with GPS fixes: snaps the position onto the route, reports progress, the next
 * manoeuvre, the next road<->path change, and detects when you left the route.
 */
class Navigator(val route: Route) {

    data class Progress(
        val snapped: LatLon,
        val alongM: Double,
        val remainingM: Double,
        val remainingS: Double,
        val offRouteM: Double,
        val nextHint: Hint?,
        val distToHintM: Double,
        val nextTransition: Transition?,
        val distToTransitionM: Double,
        val currentClass: WayClass?,
    )

    private var lastIdx = 0
    private var offCount = 0
    var offRoute = false; private set

    fun update(p: LatLon): Progress {
        val pts = route.pts
        var bestD = Double.MAX_VALUE; var bestI = lastIdx; var bestT = 0.0
        val from = (lastIdx - 3).coerceAtLeast(0)
        val to = (lastIdx + 80).coerceAtMost(pts.size - 2)
        for (i in from..to) {
            val (d, t) = Geo.project(p, pts[i], pts[i + 1])
            if (d < bestD) { bestD = d; bestI = i; bestT = t }
        }
        if (pts.size < 2) { bestI = 0; bestT = 0.0; bestD = Geo.dist(p, pts[0]) }
        // if we jumped far (e.g. after a GPS gap), search the whole route
        if (bestD > 60) {
            for (i in 0 until pts.size - 1) {
                val (d, t) = Geo.project(p, pts[i], pts[i + 1])
                if (d < bestD) { bestD = d; bestI = i; bestT = t }
            }
        }
        if (bestD > OFF_ROUTE_M) offCount++ else { offCount = 0; lastIdx = bestI }
        offRoute = offCount >= OFF_ROUTE_FIXES

        val i = bestI; val j = minOf(i + 1, pts.size - 1)
        val along = route.cum[i] + bestT * (route.cum[j] - route.cum[i])
        val timeAt = route.times[i] + bestT * (route.times[j] - route.times[i])
        val snapped = LatLon(pts[i].lat + bestT * (pts[j].lat - pts[i].lat), pts[i].lon + bestT * (pts[j].lon - pts[i].lon))
        val total = route.cum.last()

        val hint = route.hints.firstOrNull { route.cum[it.idx.coerceAtMost(pts.size - 1)] > along + 3 }
        val tr = route.transitions.firstOrNull { route.cum[it.idx] > along + 3 }
        return Progress(
            snapped, along, total - along,
            (route.times.last() - timeAt).toDouble().coerceAtLeast(0.0),
            bestD,
            hint, hint?.let { route.cum[it.idx.coerceAtMost(pts.size - 1)] - along } ?: 0.0,
            tr, tr?.let { route.cum[it.idx] - along } ?: 0.0,
            route.segmentAt(i)?.cls,
        )
    }

    /**
     * Which side of the road the destination [dest] (building) lies on, judged from the direction
     * of travel over the last ~25 m of the route: "left", "right" or null (ahead / unclear).
     */
    fun destinationSide(dest: LatLon): String? {
        val pts = route.pts
        if (pts.size < 2) return null
        val end = pts.last()
        if (Geo.dist(end, dest) < 4) return null
        var k = pts.size - 2
        while (k > 0 && route.cum.last() - route.cum[k] < 25) k--
        val travel = Geo.bearing(pts[k], end)
        val d = ((Geo.bearing(end, dest) - travel + 540) % 360) - 180
        return when { d > 25 -> "right"; d < -25 -> "left"; else -> null }
    }

    companion object {
        const val OFF_ROUTE_M = 35.0
        const val OFF_ROUTE_FIXES = 3

        /** Text and arrow for a BRouter JSON command index. */
        fun describe(h: Hint): Pair<String, String> = when (h.cmd) {
            1 -> "↑" to "Continue straight"
            2 -> "←" to "Turn left"
            3 -> "↖" to "Bear left"
            4 -> "↙" to "Sharp left"
            5 -> "→" to "Turn right"
            6 -> "↗" to "Bear right"
            7 -> "↘" to "Sharp right"
            8 -> "↖" to "Keep left"
            9 -> "↗" to "Keep right"
            10, 11, 15 -> "↶" to "Make a U-turn"
            13, 14 -> "⟳" to "At the roundabout, take the ${ordinal(h.exit)} exit"
            17 -> "↖" to "Take the exit on the left"
            18 -> "↗" to "Take the exit on the right"
            else -> "•" to "Continue"
        }

        fun ordinal(n: Int) = when (n) {
            1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; else -> "${n}th"
        }

        fun describeTransition(t: Transition): String = when (t.to) {
            WayClass.MOPED_PATH -> "move onto the ${Vehicle.current.pathName}" + (t.side?.let { " on the $it" } ?: "")
            WayClass.ROAD -> "leave the ${Vehicle.current.pathName} and join the road"
        }

        fun fmtDist(m: Double): String = when {
            m >= 1000 -> "%.1f km".format(java.util.Locale.US, m / 1000)
            m >= 100 -> "${(m / 50).toInt() * 50} m"
            else -> "${(m / 10).toInt() * 10} m"
        }
    }
}
