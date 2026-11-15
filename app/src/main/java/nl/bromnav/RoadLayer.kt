package nl.bromnav

import btools.mapaccess.NodesCache
import btools.mapaccess.OsmNode
import btools.mapaccess.WayOverride
import btools.router.ProfileCache
import btools.router.RoutingContext
import java.io.File

/**
 * "Which roads may I use?" map layer, computed from the routing data with the routing profile
 * itself, so it shows exactly what the router believes (for the selected vehicle).
 * Works per routing-data cell (1/32 degree, about 2 x 3.5 km). Not thread safe: use the routing thread.
 */
class RoadLayer(private val segDir: File, private val profile: File) {

    /** foot = forbidden foot/service path (many, obvious): only drawn when zoomed in close */
    class Link(val a: Long, val b: Long, val geom: List<LatLon>, val cls: Int, val tags: String, val foot: Boolean = false,
               /** usable a->b / b->a for the selected vehicle (one false = one-way) */
               val fw: Boolean = true, val bw: Boolean = true) {
        val oneway get() = fw != bw
        val lengthM get() = geom.zipWithNext { p, q -> Geo.dist(p, q) }.sum()
    }

    companion object {
        const val FORBIDDEN = 0; const val ROAD = 1; const val PATH = 2
        const val ALLOWED_OVR = 3; const val FORBIDDEN_OVR = 4
        const val CELL = 1_000_000 / 32
        private val SKIP = setOf("steps", "corridor", "platform", "elevator", "proposed", "construction", "raceway", "bus_stop")
        private val FOOT = setOf("footway", "pedestrian", "bridleway", "path", "track", "service")
        private val PATHLIKE = setOf("cycleway", "path", "footway", "pedestrian", "bridleway")

        fun classOf(l: Link): Int = when (RoadOverrides.get(l.a, l.b)?.mode) {
            RoadOverrides.ALLOW, RoadOverrides.ALLOW_BOTH -> ALLOWED_OVR; RoadOverrides.FORBID -> FORBIDDEN_OVR; else -> l.cls
        }

        /** Only links touching the box; forbidden foot paths only if withFoot. */
        fun visible(links: List<Link>, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, withFoot: Boolean) =
            links.filter { l -> (withFoot || !l.foot) && l.geom.any { it.lat in minLat..maxLat && it.lon in minLon..maxLon } }

        fun geoJson(links: Collection<Link>, cls: (Link) -> Int = ::classOf): String {
            val sb = StringBuilder(links.size * 120 + 64)
            sb.append("""{"type":"FeatureCollection","features":[""")
            var first = true
            for (l in links) {
                if (!first) sb.append(','); first = false
                sb.append("""{"type":"Feature","properties":{"c":""").append(cls(l)).append("""},"geometry":{"type":"LineString","coordinates":[""")
                l.geom.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append('[').append(r5(p.lon)).append(',').append(r5(p.lat)).append(']') }
                sb.append("]}}")
            }
            return sb.append("]}").toString()
        }
        private fun r5(v: Double) = Math.round(v * 1e5) / 1e5
    }

    private val cells = object : LinkedHashMap<Long, List<Link>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<Link>>) = size > 40
    }
    private var vehicleCode = -1
    var lastLoadMs = 0L; private set

    fun clear() = cells.clear()

    /** All links in the area (loads missing cells; blocking). */
    fun links(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, maxCells: Int = 12): List<Link> {
        if (vehicleCode != Vehicle.current.code) { cells.clear(); vehicleCode = Vehicle.current.code }
        val x0 = ((minLon + 180) * 1e6).toInt() / CELL; val x1 = ((maxLon + 180) * 1e6).toInt() / CELL
        val y0 = ((minLat + 90) * 1e6).toInt() / CELL; val y1 = ((maxLat + 90) * 1e6).toInt() / CELL
        val want = ArrayList<Pair<Int, Int>>()
        for (x in x0..x1) for (y in y0..y1) want.add(x to y)
        if (want.size > maxCells) return emptyList()
        val missing = want.filter { cellKey(it.first, it.second) !in cells }
        if (missing.isNotEmpty()) load(missing)
        val seen = HashSet<Long>(); val out = ArrayList<Link>()
        for ((x, y) in want) for (l in cells[cellKey(x, y)] ?: emptyList()) if (seen.add(RoadOverrides.pairKey(l.a, l.b))) out.add(l)
        return out
    }

    /** Nearest link to p (within maxM metres), or null. */
    fun nearest(p: LatLon, maxM: Double = 25.0): Link? {
        val d = 0.0006
        var best: Link? = null; var bestD = maxM
        for (l in links(p.lat - d, p.lon - d, p.lat + d, p.lon + d)) {
            for ((a, b) in l.geom.zipWithNext()) {
                val dd = Geo.project(p, a, b).first
                if (dd < bestD) { bestD = dd; best = l }
            }
        }
        return best
    }

    private fun cellKey(x: Int, y: Int) = (x.toLong() shl 32) or y.toLong()

    private fun load(missing: List<Pair<Int, Int>>) {
        val t0 = System.currentTimeMillis()
        val rc = RoutingContext()
        rc.localFunction = profile.absolutePath
        rc.keyValues = hashMapOf("vehicle" to Vehicle.current.code.toString())
        ProfileCache.parseProfile(rc)
        val ctx = rc.expctxWay
        val nc = NodesCache(segDir, ctx, false, 48L shl 20, null, false)
        try {
            RoadOverrides.inspect.set(true)
            for ((x, y) in missing) {
                val got = ArrayList<OsmNode>()
                WayOverride.tileListener.set(WayOverride.TileListener { got.addAll(it) })
                nc.getSegmentFor(x * CELL + CELL / 2, y * CELL + CELL / 2)
                WayOverride.tileListener.remove()
                val out = ArrayList<Link>()
                for (n in got) {
                    var l = n.firstlink
                    while (l != null) {
                        val t = l.getTarget(n)
                        val d = l.descriptionBitmap
                        if (t != null && d != null && !l.isReverse(n)) {
                            val kv = ctx.getKeyValueDescription(false, d)
                            val hw = kv.split(' ').firstOrNull { it.startsWith("highway=") }?.substring(8) ?: ""
                            if (hw !in SKIP && (hw.isNotEmpty() || kv.contains("route=ferry"))) {
                                ctx.evaluate(false, d); val fw = ctx.costfactor
                                ctx.evaluate(true, d); val bw = ctx.costfactor
                                val cls = if (minOf(fw, bw) >= 9999f) FORBIDDEN else if (hw in PATHLIKE) PATH else ROAD
                                // forbidden foot paths are obvious and very many: leave them out (cycle paths stay)
                                val footOnly = cls == FORBIDDEN && hw in FOOT && !kv.contains("bicycle=designated")
                                val g = ArrayList<LatLon>()
                                g.add(pos(n.ilon, n.ilat))
                                var tn = rc.geometryDecoder.decodeGeometry(l.geometry, n, t, false)
                                while (tn != null) { g.add(pos(tn.ilon, tn.ilat)); tn = tn.next }
                                g.add(pos(t.ilon, t.ilat))
                                out.add(Link(RoadOverrides.id(n.ilon, n.ilat), RoadOverrides.id(t.ilon, t.ilat), g, cls, kv, footOnly, fw < 9999f, bw < 9999f))
                            }
                        }
                        l = l.getNext(n)
                    }
                }
                cells[cellKey(x, y)] = out
            }
        } finally {
            WayOverride.tileListener.remove(); RoadOverrides.inspect.remove()
            nc.close(); ProfileCache.releaseProfile(rc)
            lastLoadMs = System.currentTimeMillis() - t0
        }
    }

    private fun pos(ilon: Int, ilat: Int) = LatLon(ilat / 1e6 - 90, ilon / 1e6 - 180)
}
