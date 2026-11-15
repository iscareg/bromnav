package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimeUtilTest {
    private val tz = TimeZone.getTimeZone("Europe/Amsterdam")
    private fun at(day: Int, h: Int, m: Int): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, h, m, 0)
    }.timeInMillis

    @Test fun formats() {
        val now = at(15, 17, 0)
        assertEquals(at(15, 17, 30), TimeUtil.parseHm("17:30", now, tz))
        assertEquals(at(15, 17, 30), TimeUtil.parseHm("17.30", now, tz))
        assertEquals(at(15, 17, 30), TimeUtil.parseHm(" 1730 ", now, tz))
        assertEquals(at(15, 19, 5), TimeUtil.parseHm("19:05", now, tz))
    }

    @Test fun invalid() {
        val now = at(15, 17, 0)
        for (s in listOf("", "abc", "24:00", "12:60", "7", "17:3", "17:300")) assertNull(s, TimeUtil.parseHm(s, now, tz))
    }

    @Test fun recentPastStaysToday() = assertEquals(at(15, 16, 30), TimeUtil.parseHm("16:30", at(15, 17, 0), tz))  // a late order

    @Test fun afterMidnightIsTomorrow() = assertEquals(at(16, 0, 20), TimeUtil.parseHm("00:20", at(15, 23, 50), tz))

    @Test fun asapOrderWindow() {
        val now = at(15, 17, 0)
        val (e, d) = TimeUtil.orderWindow("17:45", "", now, tz)
        assertNull(e); assertEquals(at(15, 17, 45), d)
    }

    @Test fun exactTimeOrderWindow() {
        val now = at(15, 17, 0)
        val (e, d) = TimeUtil.orderWindow("18:00", "18:00", now, tz)
        assertEquals(at(15, 18, 0), e)
        assertEquals(at(15, 18, Config.SCHEDULED_GRACE_MIN), d)
        // requested time without a printed delivery time: grace after the requested time
        assertEquals(at(15, 18, 30 + Config.SCHEDULED_GRACE_MIN), TimeUtil.orderWindow("", "18:30", now, tz).second)
    }

    @Test fun noTimes() = assertEquals(null to null, TimeUtil.orderWindow("", "", at(15, 17, 0), tz))
}
