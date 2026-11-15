package nl.bromnav

import org.json.JSONObject

/** What kind of surface the bromfiets is on: the car road or a (brom)fietspad. */
enum class WayClass { ROAD, MOPED_PATH }

/** A stretch of the route with identical OSM way tags; covers points [startIdx, endIdx]. */
data class Segment(val startIdx: Int, val endIdx: Int, val tags: Map<String, String>) {
    val cls: WayClass get() = classify(tags)
    val name: String? get() = tags["name"]

    companion object {
        fun classify(tags: Map<String, String>): WayClass {
            val hw = tags["highway"] ?: ""
            return if (hw == "cycleway" || hw == "path" || hw == "footway") WayClass.MOPED_PATH else WayClass.ROAD
        }
    }
}

/** BRouter turn command (JSON command index) at track point [idx]. */
data class Hint(val idx: Int, val cmd: Int, val exit: Int)

/** A road<->path change at track point [idx]. */
data class Transition(val idx: Int, val to: WayClass, val side: String?)

class Route(
    val pts: List<LatLon>,
    /** seconds from start at each point */
    val times: FloatArray,
    /** metres from start at each point */
    val cum: DoubleArray,
    val segs: List<Segment>,
    val hints: List<Hint>,
    val lengthM: Int,
    val timeS: Int,
) {
    /** > 0: the destination itself is not reachable legally; the route ends this far from it (park and walk) */
    var walkM = 0
    /** > 0: the start is not on a legal road; the route starts this far away */
    var startWalkM = 0
    val transitions: List<Transition> by lazy {
        val out = ArrayList<Transition>()
        for (i in 1 until segs.size) {
            val a = segs[i - 1]; val b = segs[i]
            if (a.cls != b.cls) out.add(Transition(b.startIdx, b.cls, sideOf(b.startIdx)))
        }
        out
    }

    /** Which side the next stretch leaves to (left/right), from the turn angle at idx. */
    private fun sideOf(idx: Int): String? {
        if (idx <= 0 || idx + 1 >= pts.size) return null
        val b1 = Geo.bearing(pts[idx - 1], pts[idx])
        val b2 = Geo.bearing(pts[idx], pts[minOf(idx + 2, pts.size - 1)])
        val d = ((b2 - b1 + 540) % 360) - 180
        return when {
            d > 20 -> "right"
            d < -20 -> "left"
            else -> null
        }
    }

    fun segmentAt(idx: Int): Segment? = segs.firstOrNull { idx >= it.startIdx && idx < it.endIdx } ?: segs.lastOrNull()

    companion object {
        /** Parses BRouter's GeoJSON output (FormatJson). */
        fun fromGeoJson(json: String): Route {
            val f = JSONObject(json).getJSONArray("features").getJSONObject(0)
            val props = f.getJSONObject("properties")
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = ArrayList<LatLon>(coords.length())
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                pts.add(LatLon(c.getDouble(1), c.getDouble(0)))
            }
            val cum = DoubleArray(pts.size)
            for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.dist(pts[i - 1], pts[i])

            val times = FloatArray(pts.size)
            props.optJSONArray("times")?.let { t -> for (i in 0 until minOf(t.length(), pts.size)) times[i] = t.getDouble(i).toFloat() }

            // messages: header row, then one row per stretch; the row's lon/lat is the END of the stretch
            val segs = ArrayList<Segment>()
            props.optJSONArray("messages")?.let { m ->
                val header = m.getJSONArray(0)
                var iLon = 0; var iLat = 1; var iTags = 9
                for (i in 0 until header.length()) when (header.getString(i)) {
                    "Longitude" -> iLon = i; "Latitude" -> iLat = i; "WayTags" -> iTags = i
                }
                var start = 0
                var search = 0
                for (r in 1 until m.length()) {
                    val row = m.getJSONArray(r)
                    val lon = row.getString(iLon).toInt() / 1e6
                    val lat = row.getString(iLat).toInt() / 1e6
                    var end = search
                    var best = Double.MAX_VALUE
                    for (k in search until pts.size) {
                        val d = Math.abs(pts[k].lat - lat) + Math.abs(pts[k].lon - lon)
                        if (d < best) { best = d; end = k }
                        if (d < 1e-7) break
                    }
                    val tags = HashMap<String, String>()
                    row.getString(iTags).split(' ').forEach { kv ->
                        val e = kv.indexOf('='); if (e > 0) tags[kv.substring(0, e)] = kv.substring(e + 1)
                    }
                    if (end > start) segs.add(Segment(start, end, tags))
                    start = end; search = end
                }
            }
            val hints = ArrayList<Hint>()
            props.optJSONArray("voicehints")?.let { v ->
                for (i in 0 until v.length()) {
                    val h = v.getJSONArray(i)
                    hints.add(Hint(h.getInt(0), h.getInt(1), h.getInt(2)))
                }
            }
            return Route(
                pts, times, cum, segs, hints,
                props.optString("track-length", "0").toInt(),
                props.optString("total-time", "0").toInt(),
            )
        }
    }
}
