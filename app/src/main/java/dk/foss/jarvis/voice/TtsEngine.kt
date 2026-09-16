package dk.foss.jarvis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speech output via the Android-selected engine and voice.
 */
interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun shutdown()
}

/** Android TextToSpeech; network requirements depend on the selected engine/voice. */
class AndroidTts(context: Context, private val languageTag: String?) : TtsEngine {

    private var ready = false
    private var initFailed = false
    private var closed = false
    @Volatile private var generation = 0L
    private var pending: Triple<String, () -> Unit, (String) -> Unit>? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = !closed && status == TextToSpeech.SUCCESS
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
        if (closed || initFailed) { onError("TTS unavailable"); return }
        if (!ready) { pending = Triple(text, onDone, onError); return }
        val request = ++generation
        val id = "hermes-$request"
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (generation == request && utteranceId == id && finished.compareAndSet(false, true)) onDone()
            }
            @Deprecated("deprecated") override fun onError(utteranceId: String?) {
                if (generation == request && utteranceId == id && finished.compareAndSet(false, true)) onError("Speech output unavailable")
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (generation == request && utteranceId == id && finished.compareAndSet(false, true)) onError("Speech output unavailable")
            }
        })
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (res == TextToSpeech.ERROR && finished.compareAndSet(false, true)) onError("Speech output unavailable")
    }

    override fun stop() {
        generation++
        pending = null
        runCatching { tts.stop() }
    }
    override fun shutdown() {
        closed = true
        stop()
        runCatching { tts.shutdown() }
    }
}
