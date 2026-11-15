package nl.bromnav

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import java.util.concurrent.Executors

/**
 * Search dialog shared by the map screen and the scanner. Combines:
 *  - recent searches (while the field is empty)
 *  - offline place search (shops, restaurants, landmarks... from OpenStreetMap) – instant, as you type
 *  - Dutch addresses (PDOK / BAG, online)
 *  - OSM name search (Photon, online) when the local index finds little
 * Queries that look like an address (contain a number) list addresses first.
 */
object AddressSearch {
    private val local = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()

    fun show(a: Activity, title: String = "Add stop", initial: String = "", history: List<PdokGeocoder.Address> = emptyList(),
             pois: PoiIndex? = null, near: LatLon = Config.RESTAURANT, onPick: (PdokGeocoder.Address) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        val dp = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0) }
        val et = EditText(a).apply {
            hint = if (pois != null) "Name, place or address (e.g. Jumbo, pizza, Vismarkt 10)" else "Street + number, or postcode + number"
            isSingleLine = true; textSize = 18f
        }
        val results = ArrayList<PdokGeocoder.Address>()
        val la = ArrayAdapter(a, android.R.layout.simple_list_item_1, ArrayList<String>())
        val lv = ListView(a).apply { adapter = la }
        box.addView(et); box.addView(lv, LinearLayout.LayoutParams(-1, (340 * dp).toInt()))
        val dlg = AlertDialog.Builder(a).setTitle(title).setView(box).setNegativeButton("Close", null).create()

        var localHits: List<PdokGeocoder.Address> = emptyList()
        var onlineHits: List<PdokGeocoder.Address> = emptyList()
        var addressFirst = false
        fun showList(items: List<PdokGeocoder.Address>, labels: List<String>) {
            results.clear(); results.addAll(items); la.clear(); la.addAll(labels); la.notifyDataSetChanged()
        }
        fun render() {
            val items = if (addressFirst) onlineHits + localHits else localHits + onlineHits
            showList(items, items.map { it.label.removeSuffix(" Groningen") })
        }
        fun showHistory() = showList(history, history.map { "🕘 " + it.label.removeSuffix(" Groningen") })
        showHistory()

        var seq = 0
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""; val my = ++seq
                if (q.isEmpty()) { localHits = emptyList(); onlineHits = emptyList(); showHistory(); return }
                addressFirst = q.any { it.isDigit() }
                // offline places: immediately (background thread, stale queries dropped)
                if (pois != null && q.length >= 2) local.execute {
                    if (my != seq) return@execute
                    val hits = pois.search(q, near, 8).map { h ->
                        val p = h.poi
                        val dist = if (h.distM < 1000) "${(h.distM / 10).toInt() * 10} m" else "%.1f km".format(h.distM / 1000)
                        val addr = p.address.ifEmpty { p.kindLabel }
                        PdokGeocoder.Address("${p.icon} ${p.name} · $addr · $dist", p.pos, p.name, p.houseNo)
                    }
                    ui.post { if (my == seq) { localHits = hits; render() } }
                }
                if (q.length < 3) return
                // online: addresses (PDOK) + OSM names (Photon) when the local index has little
                ui.postDelayed({
                    if (my != seq) return@postDelayed
                    net.execute {
                        val addr = runCatching { PdokGeocoder.search(q, near, 6) }.onFailure { Log.w("BromNav", "pdok", it) }.getOrNull()
                        val names = if (!addressFirst && (pois == null || localHits.size < 3))
                            runCatching { PhotonGeocoder.search(q, near) }.getOrNull() ?: emptyList() else emptyList()
                        ui.post {
                            if (my != seq) return@post
                            onlineHits = (addr ?: emptyList()).map { it.copy(label = "🏠 " + it.label) } + names
                            render()
                        }
                    }
                }, 400)
            }
        })
        lv.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            val r = results[pos]
            onPick(r.copy(label = r.label.removePrefix("🕘 ").removePrefix("🏠 ")))
        }
        dlg.show()
        if (initial.isNotEmpty()) et.setText(initial)
        et.requestFocus()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
}
