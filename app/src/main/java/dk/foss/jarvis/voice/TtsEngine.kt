package dk.foss.jarvis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speech output. The MVP uses [AndroidTts] only: free, offline, no account.
 */
interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun shutdown()
}

/** Android's built-in TextToSpeech. Free, offline, available everywhere. */
class AndroidTts(context: Context, private val languageTag: String?) : TtsEngine {

    private var ready = false
    private var initFailed = false
    private var pending: Triple<String, () -> Unit, (String) -> Unit>? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        initFailed = !ready
        if (ready && !languageTag.isNullOrEmpty()) {
            runCatching { engine?.language = Locale.forLanguageTag(languageTag) }
        }
        val p = pending; pending = null
        if (p != null) {
            if (ready) speak(p.first, p.second, p.third)
            else p.third("TTS unavailable") // don't re-queue forever -> would stall the pump
        }
    }

    private val engine: TextToSpeech? get() = tts

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        if (initFailed) { onError("TTS unavailable"); return }
        if (!ready) { pending = Triple(text, onDone, onError); return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onDone()
            @Deprecated("deprecated") override fun onError(utteranceId: String?) = onError("TTS error")
            override fun onError(utteranceId: String?, errorCode: Int) = onError("TTS error $errorCode")
        })
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        if (res == TextToSpeech.ERROR) onError("TTS failed to start")
    }

    override fun stop() { runCatching { tts.stop() } }
    override fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}
