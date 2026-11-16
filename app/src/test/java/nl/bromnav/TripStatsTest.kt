package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simple-navigation statistics with made-up places. */
class TripStatsTest {
    private val home = LatLon(53.2300, 6.5500)
    private val work = LatLon(53.2100, 6.5700)
    private val shop = LatLon(53.2200, 6.5900)
    private var t0 = 1_800_000_000_000L
    private fun trip(from: LatLon, to: LatLon, min: Double, label: String, arrived: Boolean = true): TripRecord {
        t0 += 3_600_000
        val d = Geo.dist(from, to) * 1.3
        return TripRecord(t0, t0 + (min * 60_000).toLong(), "Start", from, label, to, d, arrived)
    }
    private fun near(p: LatLon, m: Double) = Geo.offset(p, 45.0, m)

    @Test fun mostVisitedFirst() {
        val trips = listOf(trip(home, work, 10.0, "Work"), trip(home, shop, 8.0, "Shop"), trip(home, near(work, 60.0), 11.0, "Work"),
            trip(shop, work, 6.0, "Work"))
        val p = TripStats.places(trips)
        assertEquals("Work", p[0].label); assertEquals(3, p[0].count)      // 60 m away is the same place
        assertEquals("Shop", p[1].label); assertEquals(1, p[1].count)
        assertEquals((10.0 + 11 + 6) / 3 * 60, p[0].avgDurationS, 1.0)
    }

    @Test fun runNeedsMoreThanThreeSameTrips() {
        val three = List(3) { trip(home, work, 10.0 + it, "Work") }
        assertTrue(TripStats.routes(three).isEmpty())
        val four = three + trip(near(home, 40.0), near(work, 40.0), 9.0, "Work")
        val r = TripStats.routes(four)
        assertEquals(1, r.size); assertEquals(4, r[0].count)
        assertEquals(9.0 * 60, r[0].bestDurationS, 1.0)
        assertEquals((10.0 + 11 + 12 + 9) / 4 * 60, r[0].avgDurationS, 1.0)
    }

    @Test fun otherStartOrUnfinishedTripsAreNotTheSameRun() {
        val trips = List(3) { trip(home, work, 10.0, "Work") } + trip(shop, work, 6.0, "Work") + trip(home, work, 3.0, "Work", arrived = false)
        assertTrue(TripStats.routes(trips).isEmpty())
        val back = List(4) { trip(work, home, 10.0, "Home") }               // the way back is its own run
        assertEquals(listOf("Home"), TripStats.routes(trips + back).map { it.to })
    }

    @Test fun jsonRoundTrip() {
        val t = trip(home, work, 12.5, "Work")
        assertEquals(t, TripRecord.fromJson(org.json.JSONObject(t.toJson().toString())))
        assertEquals(t.distM / t.durationS * 3.6, t.avgKmh, 1e-9)
    }
}
