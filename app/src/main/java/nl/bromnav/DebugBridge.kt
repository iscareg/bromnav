package nl.bromnav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Debug-only remote control, so the app can be tested from the PC without tapping:
 *
 *   adb shell am broadcast -a nl.bromnav.DBG --es cmd state
 *   adb shell am broadcast -a nl.bromnav.DBG --es cmd add --es q "Vismarkt 10" --es order 1201 --es due 02:30
 *   cmd = clear | add | move (--es from N --es to M, 1-based) | auto | undo | mode (--es m simple|delivery) | navto (--es q Q) | translate (--es text T) | ocr (--es path P [--es add 1]) | cancel (--es n N) | restore (--es n N) | go | resetstats | sim | simstop | delivered | skip | end | list | nolist | state
 *
 * Every command answers with "<cmd> -> <result/state JSON>" in logcat (tag BromNavDbg) and in
 * <external files>/dbg_reply.txt.
 * Use tools/dbg.sh to send a command and print the answer.
 */
object DebugBridge {
    const val ACTION = "nl.bromnav.DBG"
    const val TAG = "BromNavDbg"
    private var receiver: BroadcastReceiver? = null

    fun register(a: MainActivity) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val cmd = i.getStringExtra("cmd") ?: "state"
                val reply: (String) -> Unit = {
                    Log.i(TAG, "$cmd -> $it")
                    // logcat is filtered on some phones: also write the reply to a file adb can read
                    runCatching { java.io.File(a.getExternalFilesDir(null), "dbg_reply.txt").writeText("$cmd -> $it") }
                }
                try {
                    when (cmd) {
                        "clear" -> { a.dbgClear(); reply("ok") }
                        "add" -> a.dbgAdd(i.getStringExtra("q") ?: "", i.getStringExtra("order") ?: "", i.getStringExtra("due"), reply,
                            i.getStringExtra("notbefore"), i.getStringExtra("pay"))
                        "go" -> { a.dbgGo(); reply("ok") }
                        "move" -> { a.dbgMove(i.getStringExtra("from")!!.toInt(), i.getStringExtra("to")!!.toInt()); reply("ok") }
                        "auto" -> { a.dbgAuto(); reply("ok") }
                        "undo" -> reply(a.dbgUndo())
                        "search" -> reply(a.dbgSearch(i.getStringExtra("q") ?: ""))
                        "mode" -> { a.dbgMode(i.getStringExtra("m") ?: "delivery"); reply("ok") }
                        "navto" -> a.dbgNavTo(i.getStringExtra("q") ?: "", reply)
                        "translate" -> {
                            val t = i.getStringExtra("text") ?: ""
                            NoteTranslator.ensureModel(wifiOnly = true) {
                                NoteTranslator.translate(t) { en -> reply("dutch=${NoteTranslator.isDutch(t)} ready=${NoteTranslator.ready} -> $en") }
                            }
                        }
                        "ocr" -> a.dbgOcr(i.getStringExtra("path")!!, i.getStringExtra("add") == "1", reply)
                        "cancel" -> reply(a.dbgCancel(i.getStringExtra("n")!!.toInt()))
                        "restore" -> reply(a.dbgRestore(i.getStringExtra("n")!!.toInt()))
                        "resetstats" -> { a.dbgResetStats(); reply("ok") }
                        "sim" -> { a.dbgSim(true); reply("ok") }
                        "simstop" -> { a.dbgSim(false); reply("ok") }
                        "delivered" -> { a.dbgDelivered(); reply("ok") }
                        "skip" -> { a.dbgSkip(); reply("ok") }
                        "end" -> { a.dbgEnd(); reply("ok") }
                        "list" -> { a.dbgList(true); reply("ok") }
                        "nolist" -> { a.dbgList(false); reply("ok") }
                        else -> reply(a.dbgState().toString())
                    }
                } catch (e: Exception) { reply("ERROR $e") }
            }
        }
        ContextCompat.registerReceiver(a, r, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    fun unregister(a: MainActivity) { receiver?.let { runCatching { a.unregisterReceiver(it) } }; receiver = null }
}
