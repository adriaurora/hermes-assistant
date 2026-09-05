package dk.foss.jarvis.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import dk.foss.jarvis.AssistantActivity

/**
 * Invoked when the user triggers the assistant (assist gesture / long-press
 * power). We don't render our own session window — we launch the private
 * conversation activity through the assistant API.
 */
class JarvisInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        // No session window → no flashing overlay.
        runCatching { setUiEnabled(false) }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "onShow flags=$showFlags — launching AssistantActivity")
        val intent = Intent(context, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // startAssistantActivity() is the assistant-sanctioned launch; it is exempt
        // from background-activity-launch limits (plain context.startActivity is not,
        // which is why the app didn't come forward — only the overlay flashed).
        try {
            startAssistantActivity(intent)
            Log.d(TAG, "startAssistantActivity dispatched")
        } catch (e: Exception) {
            Log.e(TAG, "startAssistantActivity failed", e)
        }
        hide()
    }

    private companion object {
        const val TAG = "JarvisAssist"
    }
}
