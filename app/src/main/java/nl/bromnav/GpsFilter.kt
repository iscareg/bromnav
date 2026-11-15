package nl.bromnav

import kotlin.math.cos
import kotlin.math.sin

/**
 * Smooths raw location fixes so the position marker doesn't jump around.
 *
 *  - 1-D Kalman filter per axis (in metres) with the fix's reported accuracy as measurement noise
 *    and a process noise that grows with time (we are moving at up to 45 km/h).
 *  - Prediction from GPS speed + bearing while riding, so the smoothed position doesn't lag.
 *  - Outlier rejection: a jump that implies an impossible speed is ignored (accepted after
 *    [MAX_REJECTS] in a row, so a genuine jump - e.g. after a tunnel - still gets through).
 *  - Standing still: fixes that move less than their own error barely move the estimate.
 */
class GpsFilter {
    companion object {
        const val Q_MPS = 2.5             // process noise: how fast the true position may wander (m/s)
        const val MAX_SPEED_MPS = 25.0    // ~90 km/h: anything faster is a GPS glitch on a bromfiets
        const val MAX_REJECTS = 3
        private const val M_PER_DEG_LAT = 111_320.0
    }

    private var lat = 0.0; private var lon = 0.0
    private var variance = -1.0            // m², < 0 = not initialised
    private var lastT = 0L
    private var rejects = 0

    fun reset() { variance = -1.0; rejects = 0 }

    /**
     * @param speed m/s or null, [bearing] degrees or null (only used when [speed] is set)
     * @return smoothed position
     */
    fun process(pLat: Double, pLon: Double, accuracy: Float, timeMs: Long, speed: Float?, bearing: Float?): LatLon {
        val acc = accuracy.toDouble().coerceAtLeast(3.0)
        if (variance < 0) { lat = pLat; lon = pLon; variance = acc * acc; lastT = timeMs; return LatLon(lat, lon) }

        val dt = ((timeMs - lastT) / 1000.0).coerceIn(0.0, 60.0)
        val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(lat))

        // predict: move along the GPS velocity when riding
        if (speed != null && bearing != null && speed > 1.5f && dt > 0) {
            val d = speed * dt
            lat += d * cos(Math.toRadians(bearing.toDouble())) / M_PER_DEG_LAT
            lon += d * sin(Math.toRadians(bearing.toDouble())) / mPerDegLon
        }
        variance += dt * Q_MPS * Q_MPS

        // outlier check against the prediction
        val dy = (pLat - lat) * M_PER_DEG_LAT
        val dx = (pLon - lon) * mPerDegLon
        val jump = Math.hypot(dx, dy)
        if (dt > 0 && jump > 30 + 3 * acc && jump / dt > MAX_SPEED_MPS && rejects < MAX_REJECTS) {
            rejects++
            lastT = timeMs
            return LatLon(lat, lon)
        }
        if (rejects >= MAX_REJECTS) { lat = pLat; lon = pLon; variance = acc * acc; rejects = 0; lastT = timeMs; return LatLon(lat, lon) }
        rejects = 0

        // update
        val k = variance / (variance + acc * acc)
        lat += k * (pLat - lat)
        lon += k * (pLon - lon)
        variance *= (1 - k)
        lastT = timeMs
        return LatLon(lat, lon)
    }
}
