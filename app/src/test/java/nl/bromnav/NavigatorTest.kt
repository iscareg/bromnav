package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NavigatorTest {
    private val o = LatLon(53.2182, 6.5734)
    private val kLon = 111_195.0 * Math.cos(Math.toRadians(o.lat))
    private fun at(northM: Double, eastM: Double) = LatLon(o.lat + northM / 111_195.0, o.lon + eastM / kLon)

    /** 1000 m north on a road, then turn right and 500 m east on a moped path; points every 10 m */
    private fun route(): Route {
        val pts = (0..100).map { at(it * 10.0, 0.0) } + (1..50).map { at(1000.0, it * 10.0) }
        val cum = DoubleArray(pts.size); for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.dist(pts[i - 1], pts[i])
        val times = FloatArray(pts.size) { (cum[it] / 10).toFloat() }
        val segs = listOf(Segment(0, 100, mapOf("highway" to "residential", "name" to "Northstreet")),
                          Segment(100, pts.size - 1, mapOf("highway" to "cycleway", "moped" to "designated")))
        return Route(pts, times, cum, segs, listOf(Hint(100, 5, 0)), cum.last().toInt(), times.last().toInt())
    }

    @Test fun progressAlongTheRoute() {
        val n = Navigator(route())
        val p0 = n.update(at(0.0, 0.0))
        assertEquals(0.0, p0.alongM, 1.0); assertEquals(1500.0, p0.remainingM, 2.0)
        assertEquals(100, p0.nextHint!!.idx); assertEquals(1000.0, p0.distToHintM, 2.0)
        assertEquals(WayClass.MOPED_PATH, p0.nextTransition!!.to); assertEquals(WayClass.ROAD, p0.currentClass)
        val p1 = n.update(at(500.0, 3.0))                      // 3 m beside the road
        assertEquals(500.0, p1.alongM, 1.5); assertEquals(3.0, p1.offRouteM, 0.5)
        assertEquals(100.0, p1.remainingS, 1.0)                // 1000 m left at 10 m/s
        assertFalse(n.offRoute)
        val p2 = n.update(at(1000.0, 250.0))                   // on the path after the turn
        assertEquals(1250.0, p2.alongM, 2.0); assertNull(p2.nextHint); assertNull(p2.nextTransition)
        assertEquals(WayClass.MOPED_PATH, p2.currentClass)
    }

    @Test fun offRouteNeedsThreeFixes() {
        val n = Navigator(route())
        n.update(at(200.0, 0.0))
        n.update(at(210.0, 50.0)); assertFalse(n.offRoute)
        n.update(at(220.0, 50.0)); assertFalse(n.offRoute)
        n.update(at(230.0, 50.0)); assertTrue(n.offRoute)
        n.update(at(240.0, 2.0)); assertFalse("back on the route", n.offRoute)
    }

    @Test fun jumpAheadAfterGpsGap() {
        val n = Navigator(route())
        n.update(at(0.0, 0.0))
        val p = n.update(at(1000.0, 400.0))                    // far beyond the search window
        assertEquals(1400.0, p.alongM, 2.0); assertTrue(p.offRouteM < 1)
    }

    @Test fun singlePointRoute() {
        val r = Route(listOf(o), floatArrayOf(0f), doubleArrayOf(0.0), emptyList(), emptyList(), 0, 0)
        val p = Navigator(r).update(at(5.0, 0.0))
        assertEquals(0.0, p.remainingM, 0.0); assertEquals(5.0, p.offRouteM, 0.5)
    }

    @Test fun destinationSide() {
        val n = Navigator(route())                             // ends riding east
        assertEquals("left", n.destinationSide(at(1020.0, 500.0)))    // north of the end
        assertEquals("right", n.destinationSide(at(980.0, 500.0)))    // south of the end
        assertNull(n.destinationSide(at(1000.0, 530.0)))              // straight ahead
        assertNull(n.destinationSide(at(1000.0, 501.0)))              // at the end
    }

    @Test fun texts() {
        assertEquals("Turn right", Navigator.describe(Hint(0, 5, 0)).second)
        assertEquals("At the roundabout, take the second exit", Navigator.describe(Hint(0, 13, 2)).second)
        assertEquals("7th", Navigator.ordinal(7))
        val prev = Vehicle.current
        try {
            Vehicle.current = Vehicle.YELLOW
            assertEquals("move onto the moped path on the right", Navigator.describeTransition(Transition(0, WayClass.MOPED_PATH, "right")))
            Vehicle.current = Vehicle.EBIKE
            assertEquals("leave the cycle path and join the road", Navigator.describeTransition(Transition(0, WayClass.ROAD, null)))
        } finally { Vehicle.current = prev }
    }

    @Test fun distancesAreSpokenWithADot() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale("nl", "NL"))              // "1,5 km" would be read as "one, five"
            assertEquals("1.5 km", Navigator.fmtDist(1534.0))
            assertEquals("250 m", Navigator.fmtDist(274.0))
            assertEquals("40 m", Navigator.fmtDist(47.0))
        } finally { Locale.setDefault(prev) }
    }

    @Test fun transitionSide() {
        val tr = route().transitions
        assertEquals(1, tr.size)
        assertEquals(100, tr[0].idx); assertEquals("right", tr[0].side)
        assertNotNull(route().segmentAt(120))
    }
}
