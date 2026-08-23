package dk.foss.jarvis

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.foss.jarvis.ui.ChatScreen
import dk.foss.jarvis.ui.ChatViewModel
import dk.foss.jarvis.ui.ConversationScreen
import dk.foss.jarvis.ui.ConversationViewModel
import dk.foss.jarvis.ui.HistoryScreen
import dk.foss.jarvis.ui.HistoryViewModel
import dk.foss.jarvis.ui.JarvisTheme
import dk.foss.jarvis.ui.SettingsScreen
import dk.foss.jarvis.notifications.NotificationPermission

private enum class Screen { Chat, Settings, Conversation, History }

class MainActivity : ComponentActivity() {

    // Incremented each time the assistant is triggered; observed by Compose to
    // jump into conversation mode (works for both cold start and onNewIntent).
    private var assistEpoch by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen awake while Jarvis is in the foreground.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Edge-to-edge dark: transparent bars, dark background, light icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        window.setBackgroundDrawableResource(android.R.color.black)
        if (isAssistIntent(intent)) {
            assistEpoch++
            showOverLockScreen()
        }
        if (isNotificationIntent(intent)) assistEpoch++

        setContent {
            JarvisTheme {
                var screen by remember {
                    mutableStateOf(if (assistEpoch > 0) Screen.Conversation else Screen.Chat)
                }
                LaunchedEffect(assistEpoch) {
                    if (assistEpoch > 0) screen = Screen.Conversation
                }
                when (screen) {
                    Screen.Chat -> {
                        val vm: ChatViewModel = viewModel()
                        ChatScreen(
                            vm = vm,
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenVoice = { screen = Screen.Conversation },
                            onOpenHistory = { screen = Screen.History },
                        )
                    }
                    Screen.Settings -> {
                        BackHandler { screen = Screen.Chat }
                        LaunchedEffect(Unit) {
                            if (!NotificationPermission.ensure(this@MainActivity) &&
                                android.os.Build.VERSION.SDK_INT >= 33
                            ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 4101)
                        }
                        SettingsScreen(onBack = { screen = Screen.Chat })
                    }
                    Screen.Conversation -> {
                        BackHandler { screen = Screen.Chat }
                        val cvm: ConversationViewModel = viewModel()
                        ConversationScreen(
                            vm = cvm,
                            assistTrigger = assistEpoch,
                            onExit = { screen = Screen.Chat },
                        )
                    }
                    Screen.History -> {
                        BackHandler { screen = Screen.Chat }
                        val hvm: HistoryViewModel = viewModel()
                        HistoryScreen(
                            vm = hvm,
                            onOpen = { screen = Screen.Chat },
                            onBack = { screen = Screen.Chat },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAssistIntent(intent)) {
            assistEpoch++
            showOverLockScreen()
        }
        if (isNotificationIntent(intent)) assistEpoch++
    }

    /** Appear over the lock screen and turn the display on (wake-word / assist launch). */
    private fun showOverLockScreen() {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Actually dismiss the keyguard so the user can interact immediately.
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        km?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {}
            override fun onDismissCancelled() {}
            override fun onDismissError() {}
        })
    }

    private fun isAssistIntent(i: Intent?): Boolean =
        i?.getBooleanExtra(EXTRA_FROM_ASSIST, false) == true ||
            i?.action == Intent.ACTION_ASSIST

    private fun isNotificationIntent(i: Intent?): Boolean =
        i?.getBooleanExtra("from_notification", false) == true &&
            !i.getStringExtra("event_id").isNullOrBlank()

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
    }
}
