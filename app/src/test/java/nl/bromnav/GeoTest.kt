package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    private val a = LatLon(53.2182, 6.5734)

    @Test fun distance() {
        assertEquals(111_195.0, Geo.dist(LatLon(53.0, 6.5), LatLon(54.0, 6.5)), 50.0)
        assertEquals(0.0, Geo.dist(a, a), 0.0)
    }

    @Test fun offsetAndBearingAgree() {
        for (b in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 333.0)) for (d in listOf(10.0, 150.0, 2000.0)) {
            val p = Geo.offset(a, b, d)
            assertEquals("dist $b/$d", d, Geo.dist(a, p), d * 0.003 + 0.05)
            val back = Geo.bearing(a, p)
            assertEquals("bearing $b/$d", 0.0, ((back - b + 540) % 360) - 180, 0.5)
        }
    }

    @Test fun projection() {
        val b = Geo.offset(a, 90.0, 100.0)
        val mid = Geo.offset(Geo.offset(a, 90.0, 50.0), 0.0, 8.0)          // 8 m north of the middle
        val (d, t) = Geo.project(mid, a, b)
        assertEquals(8.0, d, 0.2); assertEquals(0.5, t, 0.01)
        val (d2, t2) = Geo.project(Geo.offset(a, 270.0, 30.0), a, b)       // before the start: clamped
        assertEquals(30.0, d2, 0.2); assertEquals(0.0, t2, 0.0)
    }

    @Test fun lonLatFormat() = assertEquals("6.573400,53.218200", a.lonLat())
}
