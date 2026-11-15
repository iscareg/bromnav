package nl.bromnav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double) {
    /** BRouter "lon,lat" notation. */
    fun lonLat() = "%.6f,%.6f".format(java.util.Locale.US, lon, lat)
}

object Geo {
    private const val R = 6_371_000.0

    fun dist(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Point at distance d (m) and bearing (deg) from p. */
    fun offset(p: LatLon, bearingDeg: Double, d: Double): LatLon {
        val b = Math.toRadians(bearingDeg)
        return LatLon(p.lat + d * cos(b) / 111_195.0, p.lon + d * sin(b) / (111_195.0 * cos(Math.toRadians(p.lat))))
    }

    /** Initial bearing a -> b in degrees 0..360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /**
     * Projects p onto segment a-b (local equirectangular approximation, fine for < 1 km).
     * Returns (distance p->projection in m, t in 0..1).
     */
    fun project(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val k = cos(Math.toRadians(p.lat))
        val ax = a.lon * k; val ay = a.lat
        val bx = b.lon * k; val by = b.lat
        val px = p.lon * k; val py = p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        val q = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
        return dist(p, q) to t
    }
}
