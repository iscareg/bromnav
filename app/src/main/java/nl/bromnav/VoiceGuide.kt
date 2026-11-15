package nl.bromnav

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * English spoken guidance. Speaks each manoeuvre twice (early + at the turn) and also announces
 * every change between the car road and a bromfietspad.
 */
class VoiceGuide(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false
    var enabled = true
    private val spoken = HashSet<String>()
    private var route: Route? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.UK
            tts.setSpeechRate(1.05f)
            ready = true
        }
    }

    fun newRoute(r: Route) { route = r; spoken.clear() }

    fun say(text: String) {
        if (enabled && ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun onProgress(p: Navigator.Progress, speedMps: Double) {
        val r = route ?: return
        // lead distance grows with speed: ~11 s ahead, at least 120 m
        val early = maxOf(120.0, speedMps * 11)
        val now = maxOf(25.0, speedMps * 3)

        val h = p.nextHint
        val t = p.nextTransition
        // transition that happens at (almost) the same place as the turn is merged into it
        val merged = h != null && t != null && Math.abs(r.cum[t.idx] - r.cum[h.idx.coerceAtMost(r.pts.size - 1)]) < 20

        if (h != null) {
            val (_, text0) = Navigator.describe(h)
            val name = r.segmentAt(h.idx)?.name
            val text = text0 + (if (h.cmd !in 13..14 && name != null) " onto $name" else "") +
                (if (merged) ", then ${Navigator.describeTransition(t!!)}" else "")
            if (p.distToHintM in now..early && spoken.add("h${h.idx}e") && p.distToHintM > now + 40)
                say("In ${Navigator.fmtDist(p.distToHintM)}, ${text.replaceFirstChar { it.lowercase() }}")
            if (p.distToHintM < now && spoken.add("h${h.idx}n")) say(text0 + if (merged) ", onto the ${if (t!!.to == WayClass.MOPED_PATH) "moped path" else "road"}" else "")
        }
        if (t != null && !merged) {
            val text = Navigator.describeTransition(t)
            if (p.distToTransitionM in now..early && spoken.add("t${t.idx}e") && p.distToTransitionM > now + 40)
                say("In ${Navigator.fmtDist(p.distToTransitionM)}, $text")
            if (p.distToTransitionM < now && spoken.add("t${t.idx}n")) say(text.replaceFirstChar { it.uppercase() })
        }
    }

    fun shutdown() = tts.shutdown()
}
