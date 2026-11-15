package nl.bromnav

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GpsFilterTest {
    private val base = LatLon(53.2182, 6.5734)

    @Test fun standingStillJitterIsDamped() {
        val f = GpsFilter(); val rnd = Random(1)
        var maxRaw = 0.0; var maxOut = 0.0
        for (i in 0 until 60) {
            // ±15 m noise around a fixed point
            val p = LatLon(base.lat + rnd.nextGaussian() * 15 / 111_320, base.lon + rnd.nextGaussian() * 15 / 66_700)
            val o = f.process(p.lat, p.lon, 15f, i * 1000L, 0f, null)
            if (i > 10) { maxRaw = maxOf(maxRaw, Geo.dist(p, base)); maxOut = maxOf(maxOut, Geo.dist(o, base)) }
        }
        assertTrue("filtered max $maxOut should be far below raw max $maxRaw", maxOut < maxRaw / 2)
    }

    @Test fun followsRidingWithoutLag() {
        val f = GpsFilter(); var out = base
        for (i in 0 until 30) {   // 10 m/s due north, accurate fixes
            val truth = LatLon(base.lat + i * 10 / 111_320.0, base.lon)
            out = f.process(truth.lat, truth.lon, 5f, i * 1000L, 10f, 0f)
            if (i > 3) assertTrue("lag ${Geo.dist(out, truth)} m", Geo.dist(out, truth) < 6)
        }
    }

    @Test fun glitchIsRejectedButRealJumpAccepted() {
        val f = GpsFilter()
        for (i in 0 until 5) f.process(base.lat, base.lon, 5f, i * 1000L, 0f, null)
        val far = LatLon(base.lat + 0.01, base.lon)   // ~1.1 km away, 1 s later
        val o1 = f.process(far.lat, far.lon, 10f, 5000L, null, null)
        assertTrue("glitch ignored", Geo.dist(o1, base) < 5)
        f.process(far.lat, far.lon, 10f, 6000L, null, null)
        f.process(far.lat, far.lon, 10f, 7000L, null, null)
        val o4 = f.process(far.lat, far.lon, 10f, 8000L, null, null)
        assertTrue("persistent jump accepted", Geo.dist(o4, far) < 20)
    }
}
