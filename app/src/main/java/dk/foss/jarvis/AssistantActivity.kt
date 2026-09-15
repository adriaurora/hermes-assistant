package dk.foss.jarvis

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Private conversation entry point launched by the voice-interaction session. */
class AssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setVoiceInteractionActive(true)
        showOverLockScreen()
        setContent { JarvisApp(this@AssistantActivity, startInConversation = true) }
    }

    internal fun setVoiceInteractionActive(active: Boolean) {
        if (active) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        setVoiceInteractionActive(false)
        super.onDestroy()
    }

    /** Only the voice conversation flow is allowed to appear over keyguard. */
    private fun showOverLockScreen() {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        keyguard?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {}
            override fun onDismissCancelled() {}
            override fun onDismissError() {}
        })
    }
}
