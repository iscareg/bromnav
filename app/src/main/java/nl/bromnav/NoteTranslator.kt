package nl.bromnav

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * On-device Dutch -> English translation of customer messages (ML Kit). The ~30 MB model is
 * downloaded once (Wi-Fi by default) and then works fully offline.
 */
object NoteTranslator {
    private val translator by lazy {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.DUTCH).setTargetLanguage(TranslateLanguage.ENGLISH).build())
    }
    @Volatile var ready = false; private set

    /** Checks / starts the model download. [onReady] runs on the main thread when usable. */
    fun ensureModel(wifiOnly: Boolean = true, onReady: (() -> Unit)? = null) {
        RemoteModelManager.getInstance().isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.DUTCH).build())
            .addOnSuccessListener { have ->
                if (have) { ready = true; onReady?.invoke(); return@addOnSuccessListener }
                val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
                translator.downloadModelIfNeeded(cond).addOnSuccessListener { ready = true; onReady?.invoke() }
            }
    }

    private val NL = setOf("de", "het", "een", "en", "op", "niet", "ik", "je", "u", "aan", "bel", "bellen", "deur", "deurbel",
        "graag", "voor", "bij", "van", "is", "dan", "even", "maar", "wel", "kom", "bezorger", "alstublieft", "aub", "niet",
        "werkt", "drukken", "achter", "boven", "beneden", "verdieping", "trap", "naast", "hier", "geen", "altijd", "anders", "met")
    private val EN = setOf("the", "and", "please", "door", "ring", "call", "not", "at", "to", "bell", "floor", "doorbell",
        "upstairs", "downstairs", "thanks", "thank", "you", "leave", "front", "back", "next", "knock", "don't", "with")

    fun isDutch(text: String): Boolean {
        val w = text.lowercase().split(Regex("""[^a-zà-ÿ']+""")).filter { it.isNotEmpty() }
        return w.count { it in NL } > w.count { it in EN }
    }

    /** Callback gets the English text, or null if not Dutch / model not ready / failed. Main thread. */
    fun translate(text: String, cb: (String?) -> Unit) {
        if (!isDutch(text) || !ready) { cb(null); return }
        translator.translate(text).addOnSuccessListener { cb(it) }.addOnFailureListener { cb(null) }
    }
}
