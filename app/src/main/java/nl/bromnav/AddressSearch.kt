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
import android.widget.Toast
import java.util.concurrent.Executors

/** Type-ahead Dutch address search dialog (PDOK), shared by the map screen and the scanner. */
object AddressSearch {
    private val net = Executors.newSingleThreadExecutor()

    /** [history]: recent addresses, listed (🕘) while the search field is empty. */
    fun show(a: Activity, title: String = "Add stop", initial: String = "", history: List<PdokGeocoder.Address> = emptyList(),
             onPick: (PdokGeocoder.Address) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        val dp = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0) }
        val et = EditText(a).apply { hint = "Street + number, or postcode + number"; isSingleLine = true; textSize = 18f }
        val results = ArrayList<PdokGeocoder.Address>()
        val la = ArrayAdapter(a, android.R.layout.simple_list_item_1, ArrayList<String>())
        val lv = ListView(a).apply { adapter = la }
        box.addView(et); box.addView(lv, LinearLayout.LayoutParams(-1, (320 * dp).toInt()))
        val dlg = AlertDialog.Builder(a).setTitle(title).setView(box).setNegativeButton("Close", null).create()
        fun showHistory() {
            results.clear(); results.addAll(history)
            la.clear(); la.addAll(history.map { "🕘 " + it.label.removeSuffix(" Groningen") }); la.notifyDataSetChanged()
        }
        showHistory()
        var seq = 0
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""; val my = ++seq
                if (q.isEmpty()) { showHistory(); return }
                if (q.length < 3) return
                ui.postDelayed({
                    if (my != seq) return@postDelayed
                    net.execute {
                        val res = try { PdokGeocoder.search(q) } catch (e: Exception) { Log.w("BromNav", "search", e); null }
                        ui.post {
                            if (my != seq) return@post
                            if (res == null) { Toast.makeText(a, "Address search needs mobile data", Toast.LENGTH_SHORT).show(); return@post }
                            results.clear(); results.addAll(res)
                            la.clear(); la.addAll(res.map { it.label.removeSuffix(" Groningen") }); la.notifyDataSetChanged()
                        }
                    }
                }, 350)
            }
        })
        lv.setOnItemClickListener { _, _, pos, _ -> dlg.dismiss(); onPick(results[pos]) }
        dlg.show()
        if (initial.isNotEmpty()) et.setText(initial)
        et.requestFocus()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
}
