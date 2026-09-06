package dk.foss.jarvis.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import dk.foss.jarvis.BuildConfig
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.push.FcmLifecycle
import dk.foss.jarvis.push.FcmRegistrationState
import dk.foss.jarvis.push.FcmTokenRegistration
import dk.foss.jarvis.push.PushPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─── Helmcode tokens ─────────────────────────────────────────────────────────

private val HelmCanvas   = Color(0xFF0A0A0A)
private val HelmSurface  = Color(0xFF111111)
private val HelmRaised   = Color(0xFF161616)
private val HelmAccent   = Color(0xFF4934E1)
private val HelmAccentTx = Color(0xFF818CF8)
private val HelmWhite100 = Color(0xFFFFFFFF)
private val HelmWhite55  = Color(0x8CFFFFFF)
private val HelmWhite35  = Color(0x59FFFFFF)
private val HelmBorder12 = Color(0x1FFFFFFF)
private val HelmBorder08 = Color(0x14FFFFFF)

// ─── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var savedBaseUrl by remember { mutableStateOf("") }
    var apiKey  by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf(false) }
    var loaded  by remember { mutableStateOf(false) }
    var status  by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var pushState by remember { mutableStateOf(FcmRegistrationState.DISABLED) }
    val pushPrefs = remember { PushPrefs(context) }

    suspend fun persist() {
        store.updateConnection(baseUrl, apiKey.ifBlank { null })
    }

    fun clearSavedKey() {
        scope.launch {
            store.updateConnection(savedBaseUrl, "")
            savedKey = false
            status = null
        }
    }

    LaunchedEffect(Unit) {
        val s = store.settings.first()
        savedBaseUrl = s.baseUrl
        baseUrl = s.baseUrl
        savedKey = s.apiKey.isNotEmpty()
        loaded = true
    }

    LaunchedEffect(Unit) {
        pushPrefs.registrationState.collect { pushState = it }
    }

    DeepSpaceBackground(active = false) {
        Scaffold(
            containerColor = HelmCanvas,
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                "// SETTINGS",
                                fontFamily = RobotoMono,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                letterSpacing = 0.1.sp,
                                color = HelmAccentTx,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                scope.launch { persist() }
                                onBack()
                            }, modifier = Modifier.padding(4.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = HelmWhite55,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = HelmCanvas,
                            titleContentColor = HelmAccentTx,
                            navigationIconContentColor = HelmWhite55,
                        ),
                    )
                    HorizontalDivider(color = HelmBorder08, thickness = 0.5.dp)
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // ── // CONNECTION ──────────────────────────────────────────
                SectionEyebrow("CONNECTION")

                HelmInput(
                    label         = "Base URL",
                    value         = baseUrl,
                    onValueChange = { baseUrl = it; status = null },
                    hint          = "http://100.x.x.x:8642",
                    keyboardType  = KeyboardType.Uri,
                )

                HelmInput(
                    label         = "API key (Bearer)",
                    value         = apiKey,
                    onValueChange = { apiKey = it; status = null },
                    hint          = if (savedKey) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022 (saved)" else "Paste your Hermes API key",
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (savedKey && apiKey.isBlank()) {
                        "A key is saved and encrypted on this device. Leave blank to keep it."
                    } else null,
                )

                if (savedKey) {
                    HelmFlatButton(
                        text   = "Clear saved key",
                        onClick = { clearSavedKey() },
                        accent = false,
                    )
                }

                Text(
                    "Model selection is controlled by Hermes (its session/default model).",
                    fontFamily = RobotoSans,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = HelmWhite35,
                )

                // ── Save & test ───────────────────────────────────────────
                HelmFlatButton(
                    text   = "Save & test connection",
                    onClick = {
                        scope.launch {
                            persist()
                            testing = true
                            status = "Testing\u2026"
                            val s = store.settings.first()
                            val result = HermesClient(s.baseUrl, s.apiKey).testConnection()
                            testing = false
                            status = result.fold(
                                onSuccess = { ids ->
                                    "\u2713 Connected. ${ids.size} model(s)" +
                                        if (ids.isNotEmpty()) ": ${ids.take(5).joinToString()}" else ""
                                },
                                onFailure = { "\u2717 ${it.message}" },
                            )
                        }
                    },
                    accent = true,
                    enabled = loaded && !testing && baseUrl.isNotBlank() &&
                        (apiKey.isNotBlank() || savedKey),
                )

                if (testing) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 1.5.dp,
                        color = HelmAccentTx,
                    )
                }

                status?.let {
                    HelmStatusText(it)
                }

                HorizontalDivider(color = HelmBorder08, thickness = 0.5.dp)
                SectionEyebrow("NOTIFICATIONS")
                if (!BuildConfig.FCM_AVAILABLE) {
                    Text(
                        "This build lacks Firebase Cloud Messaging configuration. Push notifications are unavailable.",
                        fontFamily = RobotoSans, fontSize = 14.sp, lineHeight = 20.sp, color = HelmWhite55,
                    )
                } else {
                    Text(
                        "Receive durable event reminders through Firebase Cloud Messaging. Message content is fetched from Hermes over your configured connection.",
                        fontFamily = RobotoSans, fontSize = 14.sp, lineHeight = 20.sp, color = HelmWhite55,
                    )
                    val pushLabel = when (pushState) {
                        FcmRegistrationState.DISABLED -> "Disabled"
                        FcmRegistrationState.REGISTERING -> "Registering"
                        FcmRegistrationState.ENABLED -> "Enabled"
                        FcmRegistrationState.ERROR -> "Registration error"
                        FcmRegistrationState.UNREGISTERING -> "Unregistering"
                    }
                    Text(pushLabel, fontFamily = RobotoMono, fontSize = 13.sp,
                        color = if (pushState == FcmRegistrationState.DISABLED) HelmWhite35 else HelmAccentTx)
                    if (pushState == FcmRegistrationState.DISABLED) {
                        HelmFlatButton("Enable notifications", { scope.launch { FcmLifecycle.enable(context) } }, accent = true)
                    } else {
                        HelmFlatButton("Re-register device", { FcmTokenRegistration.enqueueCurrent(context) }, accent = false)
                        HelmFlatButton("Disable notifications", { scope.launch { FcmLifecycle.disable(context) } }, accent = false)
                    }
                }

                // ── Divider ───────────────────────────────────────────────
                HorizontalDivider(color = HelmBorder08, thickness = 0.5.dp)

                // ── // ASSISTANT ───────────────────────────────────────────
                SectionEyebrow("ASSISTANT")

                Text(
                    "Set Hermes as your device's digital assistant to launch it with the assist gesture (long-press the power/home button).",
                    fontFamily = RobotoSans,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = HelmWhite55,
                )

                HelmFlatButton(
                    text   = "Set as default assistant",
                    onClick = { openAssistantSettings(context) },
                    accent = false,
                )

                // ── Version footer ────────────────────────────────────────
                Text(
                    text = "Hermes ${BuildConfig.VERSION_NAME}",
                    fontFamily = RobotoMono,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = HelmWhite35,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                )
            }
        }
    }
}

// ─── Eyebrow section header ──────────────────────────────────────────────────
// design-nan.md §4.4: // SECTION NAME  Roboto Mono 500 12  #818CF8

@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text = "// $text",
        fontFamily = RobotoMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
        color = HelmAccentTx,
    )
}

// ─── Flat input field ────────────────────────────────────────────────────────
// design-nan.md §17: square, #11 surface, hairline border, 48dp height

@Composable
private fun HelmInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    supportingText: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = HelmWhite55,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        BasicTextField(
            value     = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(HelmSurface, androidx.compose.foundation.shape.RoundedCornerShape(0.dp))
                .border(0.5.dp, HelmBorder12)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = RobotoSans,
                fontSize = 16.sp,
                color = HelmWhite100,
            ),
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(hint, fontFamily = RobotoSans, fontSize = 16.sp, color = HelmWhite35)
                }
                inner()
            },
        )
        supportingText?.let { txt ->
            Text(
                txt,
                fontFamily = RobotoSans,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = HelmWhite35,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ─── Flat button ─────────────────────────────────────────────────────────────
// design-nan.md §16: square, 44dp height, no radius

@Composable
private fun HelmFlatButton(
    text: String,
    onClick: () -> Unit,
    accent: Boolean,
    enabled: Boolean = true,
) {
    val bgColor = when {
        !enabled -> HelmRaised
        accent   -> HelmAccent
        else     -> HelmSurface
    }
    val borderColor = when {
        !enabled -> HelmBorder08
        accent   -> HelmAccent
        else     -> HelmBorder12
    }
    val textColor = when {
        !enabled -> HelmWhite35
        accent   -> HelmWhite100
        else     -> HelmWhite55
    }

    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .border(0.5.dp, borderColor)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = text,
            fontFamily = RobotoSans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = textColor,
        )
    }
}

// ─── Status text ─────────────────────────────────────────────────────────────

@Composable
private fun HelmStatusText(text: String) {
    val color = when {
        text.startsWith("\u2713") -> HelmAccentTx
        text.startsWith("Testing") -> HelmWhite55
        else -> Color(0xFFFF5F56)
    }
    Text(
        text = text,
        fontFamily = RobotoSans,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = color,
        modifier = Modifier.padding(top = 4.dp),
    )
}

// ─── System helper ───────────────────────────────────────────────────────────

private fun openAssistantSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
