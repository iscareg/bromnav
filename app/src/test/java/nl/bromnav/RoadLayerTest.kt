package nl.bromnav

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Uses the real routing data on this PC (skipped if absent). */
class RoadLayerTest {
    private val home = File(System.getProperty("user.home"), "bromnav")
    private val seg = File(home, "data/segments")
    private val profile = File(home, "app/src/main/assets/profiles/bromfiets.brf")
    private val restaurant = LatLon(53.2182246, 6.5734317)
    private val zernike = LatLon(53.2405, 6.5365)

    @Test fun layerAndOverrides() {
        assumeTrue(File(seg, "E5_N50.rd5").exists())
        RoadOverrides.init(createTempDir())
        Vehicle.current = Vehicle.YELLOW
        val layer = RoadLayer(seg, profile)
        val t0 = System.currentTimeMillis()
        val links = layer.links(53.205, 6.54, 53.235, 6.59)
        val ms = System.currentTimeMillis() - t0
        val byCls = links.groupingBy { it.cls }.eachCount()
        val shown = RoadLayer.visible(links, 53.21, 6.55, 53.225, 6.58, withFoot = false)
        println("links ${links.size} in $ms ms (cells load ${layer.lastLoadMs} ms): $byCls; city-centre view ${shown.size} -> geojson ${RoadLayer.geoJson(shown).length / 1024} kB")
        assertTrue(byCls[RoadLayer.FORBIDDEN]!! > 100 && byCls[RoadLayer.ROAD]!! > 100 && byCls[RoadLayer.PATH]!! > 10)

        val engine = BRouterEngine(seg, profile)
        val before = engine.route(listOf(restaurant, zernike))
        // force-allow every forbidden untagged cycleway along the bike route -> must get shorter
        val bike = run { Vehicle.current = Vehicle.EBIKE; engine.route(listOf(restaurant, zernike)) }
        Vehicle.current = Vehicle.YELLOW
        var n = 0
        // every link that lies on the e-bike route (all its segment midpoints within 2 m of it)
        val segs = bike.pts.zipWithNext()
        fun onRoute(p: LatLon) = segs.any { (a, b) -> Geo.project(p, a, b).first < 2.0 }
        for (l in layer.links(53.21, 6.53, 53.245, 6.58)) {
            if (!(l.cls == RoadLayer.FORBIDDEN || l.oneway)) continue
            if (l.geom.zipWithNext().all { (p, q) -> onRoute(LatLon((p.lat + q.lat) / 2, (p.lon + q.lon) / 2)) }) {
                RoadOverrides.set(RoadOverrides.Entry(l.a, l.b, RoadOverrides.ALLOW_BOTH, l.geom, l.tags)); n++
            }
        }
        val after = engine.route(listOf(restaurant, zernike))
        if (after.lengthM > before.lengthM - 500) {
            val ps = bike.pts.filterIndexed { i, _ -> i % 8 == 0 } + bike.pts.last()
            for ((p, q) in ps.zipWithNext()) {
                val d = Geo.dist(p, q); val r = runCatching { engine.route(listOf(p, q)).lengthM }.getOrDefault(-1)
                if (r < 0 || r > d * 2 + 50) println("  gap ${p.lat},${p.lon} -> ${q.lat},${q.lon}: beeline ${d.toInt()} m, bromfiets $r m; near: " +
                    (layer.nearest(p, 15.0)?.let { "${it.cls} fw=${it.fw} bw=${it.bw} ${it.tags} ovr=${RoadOverrides.get(it.a, it.b)?.mode}" }))
            }
        }
        println("bromfiets ${before.lengthM} m -> ${after.lengthM} m after allowing $n links (e-bike ${bike.lengthM} m)")
        assertTrue(after.lengthM < before.lengthM - 500)
        // forbid them again -> back to the long way
        for (e in RoadOverrides.all()) RoadOverrides.set(RoadOverrides.Entry(e.a, e.b, RoadOverrides.FORBID, e.geom, e.tags))
        val forb = engine.route(listOf(restaurant, zernike))
        println("forbidden again: ${forb.lengthM} m")
        assertTrue(forb.lengthM >= before.lengthM - 50)
        RoadOverrides.clear()
    }
}
