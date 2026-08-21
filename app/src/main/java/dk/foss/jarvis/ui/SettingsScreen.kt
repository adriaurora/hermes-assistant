package dk.foss.jarvis.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    // Write-only: the saved token is never loaded back into UI state. Blank
    // field + [savedKey] means "keep the stored token" on save.
    var apiKey by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(SettingsStore.DEFAULT_MODEL) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    suspend fun persist() {
        store.updateConnection(baseUrl, apiKey.ifBlank { null }, model)
    }

    fun clearSavedKey() {
        scope.launch {
            store.updateConnection(baseUrl, "", model)
            savedKey = false
            status = null
        }
    }

    LaunchedEffect(Unit) {
        val s = store.settings.first()
        baseUrl = s.baseUrl
        savedKey = s.apiKey.isNotEmpty()
        model = s.model
        loaded = true
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JarvisColors.Cyan.copy(alpha = 0.5f),
        unfocusedBorderColor = JarvisColors.CyanBorder,
        focusedLabelColor = JarvisColors.Cyan,
        unfocusedLabelColor = JarvisColors.Muted,
        cursorColor = JarvisColors.Cyan,
        focusedTextColor = JarvisColors.TextPrimary,
        unfocusedTextColor = JarvisColors.TextPrimary,
        focusedPlaceholderColor = JarvisColors.Muted,
        unfocusedPlaceholderColor = JarvisColors.Muted,
    )

    DeepSpaceBackground(active = false) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Settings",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { persist() }
                            onBack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = JarvisColors.Cyan,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = JarvisColors.TextPrimary,
                    ),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader("Hermes connection")

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; status = null },
                    label = { Text("Base URL") },
                    placeholder = { Text("http://100.x.x.x:8642") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; status = null },
                    label = { Text("API key (Bearer)") },
                    placeholder = { Text(if (savedKey) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022 (saved)" else "Paste your Hermes API key") },
                    supportingText = {
                        if (savedKey && apiKey.isBlank()) {
                            Text(
                                "A key is saved and encrypted on this device. Leave blank to keep it.",
                                fontFamily = DmSans,
                                fontSize = 12.sp,
                                color = JarvisColors.Muted,
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                if (savedKey) {
                    NeutralButton("Clear saved key") { clearSavedKey() }
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                PillButton(
                    text = "Save & test connection",
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
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loaded && !testing && baseUrl.isNotBlank() &&
                        (apiKey.isNotBlank() || savedKey),
                )
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = JarvisColors.Cyan)
                }
                status?.let {
                    Text(
                        it,
                        fontFamily = DmSans,
                        fontSize = 14.sp,
                        color = when {
                            it.startsWith("\u2713") -> JarvisColors.Cyan
                            it.startsWith("Testing") -> JarvisColors.TextSecondary
                            else -> JarvisColors.ErrorOrange
                        },
                    )
                }

                SettingsDivider()

                Text(
                    "Set Hermes Assistant as your device's digital assistant to launch it with the assist gesture (long-press the power/home button).",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                NeutralButton("Set as default assistant") { openAssistantSettings(context) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = JarvisColors.CyanText,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = JarvisColors.Cyan.copy(alpha = 0.08f),
    )
}

@Composable
private fun NeutralButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.CyanBorder),
    ) {
        Text(
            text = text,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = JarvisColors.TextPrimary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

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
