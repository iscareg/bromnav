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

    fun route(points: List<LatLon>, nogos: List<NoGo> = emptyList(), maxMs: Long = 30_000): Route {
        require(points.size >= 2)
        val rc = RoutingContext()
        rc.localFunction = profileFile.absolutePath
        val pc = RoutingParamCollector()
        val wps = pc.getWayPointList(points.joinToString("|") { it.lonLat() })
        val params = HashMap<String, String>()
        params["timode"] = "1"
        params["alternativeidx"] = "0"
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
