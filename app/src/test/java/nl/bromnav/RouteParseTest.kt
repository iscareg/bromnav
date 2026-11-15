package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses a real BRouter answer (restaurant -> Zernike, bromfiets profile) kept as a test resource. */
class RouteParseTest {
    private val r = Route.fromGeoJson(javaClass.getResource("/route_zernike.json")!!.readText())

    @Test fun geometryAndTotals() {
        assertTrue(r.pts.size > 100)
        assertEquals(6295, r.lengthM)
        assertEquals(r.lengthM.toDouble(), r.cum.last(), r.lengthM * 0.02)
        for (i in 1 until r.times.size) assertTrue("times must not go back", r.times[i] >= r.times[i - 1])
        assertEquals(r.timeS.toDouble(), r.times.last().toDouble(), 2.0)
    }

    @Test fun segmentsCoverTheWholeTrack() {
        assertEquals(0, r.segs.first().startIdx)
        for (i in 1 until r.segs.size) assertEquals(r.segs[i - 1].endIdx, r.segs[i].startIdx)
        assertEquals(r.pts.size - 1, r.segs.last().endIdx)
        assertTrue(r.segs.all { it.tags.containsKey("highway") })
    }

    @Test fun hintsAndTransitions() {
        assertTrue(r.hints.isNotEmpty())
        assertTrue(r.hints.all { it.idx in r.pts.indices })
        for (t in r.transitions) {
            val before = r.segs.last { it.endIdx <= t.idx }
            val after = r.segs.first { it.startIdx == t.idx }
            assertTrue(before.cls != after.cls && after.cls == t.to)
        }
    }

    /** the stored route must itself be legal for a yellow-plate bromfiets */
    @Test fun routeIsLegalForBromfiets() {
        for (s in r.segs) {
            val t = s.tags
            assertFalse(t.toString(), t["moped"] in setOf("no", "use_sidepath"))
            assertFalse(t.toString(), t["motorroad"] == "yes" || t["highway"]?.startsWith("motorway") == true)
            if (t["highway"] in setOf("cycleway", "footway", "path", "pedestrian"))
                assertTrue(t.toString(), t["moped"] in setOf("yes", "designated"))
        }
    }
}
