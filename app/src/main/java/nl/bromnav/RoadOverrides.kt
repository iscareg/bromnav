package nl.bromnav

import btools.expressions.BExpressionContextWay
import btools.mapaccess.WayOverride
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Your own corrections of the map data: single road sections (BRouter links, junction to junction)
 * forced to "allowed" or "forbidden". Applied inside BRouter while the routing data is decoded
 * (patched BRouter, see tools/brouter-patch), so they work for every vehicle and every route.
 * A link is identified by its two end nodes, which stay the same across map updates unless the
 * road itself is re-drawn in OpenStreetMap.
 */
object RoadOverrides {
    const val ALLOW = 1
    const val FORBID = 2
    /** allowed, and in both directions (one-way does not apply, e.g. "uitgezonderd bromfietsers") */
    const val ALLOW_BOTH = 3

    class Entry(val a: Long, val b: Long, val mode: Int, val geom: List<LatLon>, val tags: String)

    @Volatile private var map: Map<Long, Entry> = emptyMap()
    /** end nodes of allowed sections: barriers there (gates, posts) are passable too */
    @Volatile private var openNodes: Set<Long> = emptySet()
    /** bumps on every change (route caches use it) */
    @Volatile var version = 0; private set
    /** set on the road-layer thread: see the raw data, no overrides, keep forbidden ways */
    val inspect = ThreadLocal<Boolean>()
    private val allowCache = ConcurrentHashMap<ByteBuffer, ByteArray>()
    private var file: File? = null

    fun id(ilon: Int, ilat: Int): Long = (ilon.toLong() shl 32) or (ilat.toLong() and 0xffffffffL)
    fun idToPos(id: Long) = LatLon((id and 0xffffffffL).toInt() / 1e6 - 90, (id ushr 32).toInt() / 1e6 - 180)
    fun posToI(p: LatLon) = ((p.lon + 180) * 1e6).toInt() to ((p.lat + 90) * 1e6).toInt()

    fun pairKey(a: Long, b: Long): Long {
        val lo = minOf(a, b); val hi = maxOf(a, b)
        return lo * -7046029254386353131L xor java.lang.Long.rotateLeft(hi, 29)
    }

    fun get(a: Long, b: Long): Entry? = map[pairKey(a, b)]?.takeIf { (it.a == a && it.b == b) || (it.a == b && it.b == a) }
    fun all(): List<Entry> = map.values.toList()

    fun set(e: Entry) { update { it[pairKey(e.a, e.b)] = e } }
    fun remove(a: Long, b: Long) { update { it.remove(pairKey(a, b)) } }
    fun clear() { update { it.clear() } }

    @Synchronized private fun update(f: (HashMap<Long, Entry>) -> Unit) {
        val m = HashMap(map); f(m); setMap(m); version++; save()
    }

    fun init(filesDir: File) {
        file = File(filesDir, "road_overrides.json")
        runCatching {
            val a = JSONArray(file!!.readText()); val m = HashMap<Long, Entry>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val g = o.getJSONArray("g")
                val e = Entry(o.getString("a").toLong(), o.getString("b").toLong(), o.getInt("m"),
                    (0 until g.length()).map { g.getJSONArray(it).let { p -> LatLon(p.getDouble(0), p.getDouble(1)) } }, o.optString("t"))
                m[pairKey(e.a, e.b)] = e
            }
            setMap(m)
        }
        WayOverride.install(hook)
        WayOverride.nodeHook = nodeHook
    }

    private fun setMap(m: Map<Long, Entry>) {
        map = m
        openNodes = m.values.filter { it.mode != FORBID }.flatMap { listOf(it.a, it.b) }.toHashSet()
    }

    val nodeHook = WayOverride.NodeHook { data, lon, lat ->
        if (inspect.get() != true && openNodes.isNotEmpty() && id(lon, lat) in openNodes) null else data
    }

    private fun save() {
        val f = file ?: return
        val a = JSONArray()
        for (e in map.values) a.put(JSONObject().put("a", e.a.toString()).put("b", e.b.toString()).put("m", e.mode).put("t", e.tags)
            .put("g", JSONArray().apply { e.geom.forEach { put(JSONArray().put(it.lat).put(it.lon)) } }))
        runCatching { val tmp = File(f.path + ".new"); tmp.writeText(a.toString()); tmp.renameTo(f) }
    }

    /** Called by the patched BRouter decoder for every link. */
    val hook = WayOverride.Hook { data, accessType, lon1, lat1, lon2, lat2, validator ->
        if (inspect.get() == true) return@Hook data
        val m = map
        val e = if (m.isEmpty()) null else get(id(lon1, lat1), id(lon2, lat2))
        when {
            e == null -> if (accessType == 0) null else data
            e.mode == FORBID -> null
            else -> allowCache.getOrPut(ByteBuffer.wrap(data.copyOf() + e.mode.toByte())) { allowed(data, validator as BExpressionContextWay, e.mode == ALLOW_BOTH) }
        }
    }

    /** Same way, but legal for every vehicle: moped/mofa/bicycle designated, generic restrictions removed. */
    private fun allowed(data: ByteArray, ctx: BExpressionContextWay, bothWays: Boolean): ByteArray {
        val ld = ctx.createNewLookupData()
        ctx.decode(ld, false, data)
        for ((k, v) in listOf("moped" to "designated", "mofa" to "designated", "bicycle" to "designated")) ctx.addLookupValue(k, v, ld)
        val clear = listOf("access", "vehicle", "motor_vehicle", "motorroad", "motorcar") +
            (if (bothWays) listOf("oneway", "oneway:bicycle", "junction") else emptyList())
        for (k in clear) {
            val i = ctx.getLookupNameIdx(k); if (i >= 0) ld[i] = 0
        }
        return ctx.encode(ld).copyOf()
    }
}
