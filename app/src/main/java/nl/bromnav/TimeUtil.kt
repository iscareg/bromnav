package nl.bromnav

import java.util.Calendar
import java.util.TimeZone

object TimeUtil {
    /**
     * "17:30", "17.30", "1730", "7:05" -> epoch ms of that time today, or tomorrow when it lies more
     * than 6 h in the past (an order after midnight). null for anything that is not a valid time.
     */
    fun parseHm(s: String, nowMs: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Long? {
        val m = Regex("""^\s*(\d{1,2})[:.]?(\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        val c = Calendar.getInstance(tz)
        c.timeInMillis = nowMs
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis < nowMs - 6 * 3600_000L) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /**
     * Time window of an order from its receipt: [promised] = printed delivery time ("" = none),
     * [notBefore] = requested exact time ("" = ASAP). Exact-time orders may not be delivered early
     * and are still on time up to [Config.SCHEDULED_GRACE_MIN] after the later of both times.
     * @return (earliest, deadline) in epoch ms, either may be null
     */
    fun orderWindow(promised: String, notBefore: String, nowMs: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Pair<Long?, Long?> {
        val earliest = parseHm(notBefore, nowMs, tz)
        val due = parseHm(promised, nowMs, tz)
        val deadline = if (earliest != null) maxOf(earliest, due ?: earliest) + Config.SCHEDULED_GRACE_MIN * 60_000L else due
        return earliest to deadline
    }
}
