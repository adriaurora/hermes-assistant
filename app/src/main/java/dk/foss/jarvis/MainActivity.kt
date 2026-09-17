package dk.foss.jarvis

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dk.foss.jarvis.hermes.originIdentity
import dk.foss.jarvis.receivers.PushIngress
import dk.foss.jarvis.push.FcmLifecycle
import dk.foss.jarvis.notifications.HERMES_NOTIFICATION_TAP
import dk.foss.jarvis.notifications.NotificationTapStore
import dk.foss.jarvis.notifications.TapResult
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
    private var tapRequest by mutableStateOf<TapRequest?>(null)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        // MainActivity is deliberately an ordinary app entry point. In
        // particular, neither ACTION_ASSIST nor notification extras are trust
        // signals here.
        lifecycleScope.launch { tapRequest = consumeTap(intent) }
        setContent { JarvisApp(this@MainActivity, startInConversation = false, onEnablePush = { requestPushEnable() }, tapRequest = tapRequest) }
        lifecycleScope.launch { runCatching { PushIngress.scheduleStartupWork(applicationContext) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { consumeTap(intent)?.let { tapRequest = it } }
    }

    /**
     * Consume a notification tap token (non-destructive check only — the
     * token value is returned so the caller can decide what to do).  Uses
     * [originIdentity] from the current settings to validate the origin.
     */
    private suspend fun consumeTap(intent: android.content.Intent?): TapRequest? {
        if (intent?.action != HERMES_NOTIFICATION_TAP || intent.`package` != packageName) return null
        val token = intent.getStringExtra("tap_token") ?: return null
            val origin = runCatching { dk.foss.jarvis.data.SettingsStore(this).settings.first() }
                .getOrNull()?.let { s -> if (s.isConfigured) originIdentity(s.baseUrl, s.apiKey) else "" } ?: ""
        return TapRequest(token, NotificationTapStore.consume(this, token, origin))
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

/**
 * Main UI composable.  Handles the notification tap result:
 * - Valid → navigate to History and open the session (with origin check).
 * - StaleOrigin → show a visible notice.
 * - NotFound → silently ignore (already consumed).
 */
@Composable
internal fun JarvisApp(activity: ComponentActivity, startInConversation: Boolean, onEnablePush: () -> Unit = {}, tapRequest: TapRequest? = null) {
    val hvm: HistoryViewModel = viewModel()
    val chatVm: ChatViewModel = viewModel()
    var screen by remember { mutableStateOf(if (startInConversation) Screen.Conversation else Screen.Chat) }

    // Process notification tap on launch.  No runBlocking; we rely on
    // HistoryViewModel's suspendible openNotificationSession to validate
    // the origin asynchronously.
    LaunchedEffect(tapRequest?.token) {
        tapRequest?.let { request ->
            when (val route = routeTap(request)) {
                TapRoute.Stale -> {
                    // Stale tap: show a visible notice so the user understands
                    // why the notification was ignored.
                    hvm.notice.value = "This notification belongs to a previous Hermes connection. It has been ignored."
                    screen = Screen.History
}

                is TapRoute.Import -> {
                    // Valid tap: open the session.  HistoryViewModel validates
                    // the origin again and shows a notice if it has changed.
                    screen = Screen.History
                    hvm.openNotificationSession(route.sessionId, route.origin) { screen = Screen.Chat }
                }
                TapRoute.Ignore -> {
                    // Token already consumed — nothing to do.
                }
            }
        }
    }

    JarvisTheme {
        when (screen) {
                    Screen.Chat -> {
                        ChatScreen(
                            vm = chatVm,
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
                        HistoryScreen(
                            vm = hvm,
                            onOpen = { screen = Screen.Chat },
                            onBack = { screen = Screen.Chat },
                        )
                    }
                }
        }
    }
internal data class TapRequest(val token: String, val result: TapResult)

internal sealed interface TapRoute {
    data class Import(val sessionId: String, val origin: String) : TapRoute
    data object Stale : TapRoute
    data object Ignore : TapRoute
}

internal fun routeTap(request: TapRequest, onImport: () -> Unit = {}): TapRoute = when (val result = request.result) {
    is TapResult.Valid -> { onImport(); TapRoute.Import(result.sessionId, result.notificationOrigin) }
    TapResult.StaleOrigin -> TapRoute.Stale
    TapResult.NotFound -> TapRoute.Ignore
}
