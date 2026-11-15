package nl.bromnav

import btools.router.FormatJson
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import java.io.File

/** A no-go circle (user's "not allowed here" report). */
data class NoGo(val p: LatLon, val radiusM: Int = 20)

/**
 * Thin wrapper around the embedded BRouter engine. Not thread safe: call from one worker thread.
 */
class BRouterEngine(private val segmentDir: File, private val profileFile: File) {

    class RoutingException(msg: String) : Exception(msg)

    /**
     * Routes through the points. If the destination (or start) lies on a piece of road that is cut off
     * from the legal network (e.g. a campus with access=private roads around it), routes to / from the
     * nearest reachable spot instead and reports the remaining distance in Route.walkM / startWalkM.
     */
    fun route(points: List<LatLon>, nogos: List<NoGo> = emptyList(), maxMs: Long = 30_000): Route = try {
        route0(points, nogos, maxMs)
    } catch (e: RoutingException) {
        val msg = e.message ?: ""
        val target = msg.contains("target island"); val start = msg.contains("start island")
        if (points.size != 2 || !(target || start)) throw e
        val fixed = if (target) points[1] else points[0]
        // nearest ring first; within a ring start with the side facing the other end (usually where the road is)
        val other = if (target) points[0] else points[1]
        val toward = Geo.bearing(fixed, other)
        var best: Route? = null
        search@ for (r in listOf(50.0, 100.0, 180.0, 300.0)) {
            for (k in listOf(0, 45, -45, 90, -90, 135, -135, 180)) {
                val p = Geo.offset(fixed, toward + k, r)
                best = runCatching { route0(if (target) listOf(points[0], p) else listOf(p, points[1]), nogos, maxMs) }.getOrNull()
                if (best != null) break@search
            }
        }
        val res = best ?: throw e
        if (target) res.walkM = Geo.dist(res.pts.last(), fixed).toInt() else res.startWalkM = Geo.dist(res.pts.first(), fixed).toInt()
        res
    }

    private fun route0(points: List<LatLon>, nogos: List<NoGo>, maxMs: Long): Route {
        require(points.size >= 2)
        val rc = RoutingContext()
        rc.localFunction = profileFile.absolutePath
        val pc = RoutingParamCollector()
        val wps = pc.getWayPointList(points.joinToString("|") { it.lonLat() })
        val params = HashMap<String, String>()
        params["timode"] = "1"
        params["alternativeidx"] = "0"
        params["profile:vehicle"] = Vehicle.current.code.toString()
        if (nogos.isNotEmpty()) params["nogos"] = nogos.joinToString("|") { "${it.p.lonLat()},${it.radiusM}" }
        pc.setParams(rc, wps, params)

        val engine = RoutingEngine(null, null, segmentDir, wps, rc)
        engine.quite = true
        engine.doRun(maxMs)
        engine.errorMessage?.let { throw RoutingException(it) }
        val track = engine.foundTrack ?: throw RoutingException("no route found")
        return Route.fromGeoJson(FormatJson(rc).format(track))
    }
}
