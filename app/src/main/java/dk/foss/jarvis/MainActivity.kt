package dk.foss.jarvis

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dk.foss.jarvis.receivers.PushIngress
import dk.foss.jarvis.push.FcmLifecycle
import dk.foss.jarvis.ui.ChatScreen
import dk.foss.jarvis.ui.ChatViewModel
import dk.foss.jarvis.ui.ConversationScreen
import dk.foss.jarvis.ui.ConversationViewModel
import dk.foss.jarvis.ui.HistoryScreen
import dk.foss.jarvis.ui.HistoryViewModel
import dk.foss.jarvis.ui.JarvisTheme
import dk.foss.jarvis.ui.SettingsScreen

private enum class Screen { Chat, Settings, Conversation, History }

class MainActivity : ComponentActivity() {
    private var awaitingPushPermission = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        // MainActivity is deliberately an ordinary app entry point. In
        // particular, neither ACTION_ASSIST nor notification extras are trust
        // signals here.
        setContent { JarvisApp(this@MainActivity, startInConversation = false, onEnablePush = { requestPushEnable() }) }
        lifecycleScope.launch { runCatching { PushIngress.scheduleStartupWork(applicationContext) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Do not interpret actions or extras from an external intent as an
        // assistant authorization or a request to start listening.
    }

    private fun requestPushEnable() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            awaitingPushPermission = true
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 4101)
        } else lifecycleScope.launch { FcmLifecycle.enable(applicationContext) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4101 && awaitingPushPermission) {
            awaitingPushPermission = false
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
                lifecycleScope.launch { FcmLifecycle.enable(applicationContext) }
        }
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        window.setBackgroundDrawableResource(android.R.color.black)
    }
}

@Composable
internal fun JarvisApp(activity: ComponentActivity, startInConversation: Boolean, onEnablePush: () -> Unit = {}) {
    JarvisTheme {
        var screen by remember { mutableStateOf(if (startInConversation) Screen.Conversation else Screen.Chat) }
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
                        SettingsScreen(onBack = { screen = Screen.Chat }, onEnablePush = onEnablePush)
                    }
                    Screen.Conversation -> {
                        BackHandler { screen = Screen.Chat }
                        val cvm: ConversationViewModel = viewModel()
                        ConversationScreen(
                            vm = cvm,
                            assistTrigger = if (startInConversation) 1 else 0,
                            onExit = {
                                // Only the voice entry point owns the keep-awake
                                // flag; leaving voice must release it immediately.
                                if (activity is AssistantActivity) activity.setVoiceInteractionActive(false)
                                screen = Screen.Chat
                            },
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
