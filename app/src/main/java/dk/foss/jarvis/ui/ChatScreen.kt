package dk.foss.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.foss.jarvis.data.UiMessage
import dk.foss.jarvis.R

// ─── Helmcode color tokens ───────────────────────────────────────────────────

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
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val messages = vm.messages
    val streaming by vm.isStreaming

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // Prime the model catalog and current session model once per screen visit.
    LaunchedEffect(Unit) { vm.refreshModel() }

    DeepSpaceBackground(active = false) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = HelmCanvas,
                topBar = {
                    HelmTopBar(
                        modelLabel     = vm.modelLabel.value,
                        onNewClick     = { vm.newConversation() },
                        onHistoryClick = onOpenHistory,
                        onVoiceClick   = onOpenVoice,
                        onSettingsClick = onOpenSettings,
                    )
                },
            ) { padding ->
                HelmChatContent(
                    listState   = listState,
                    modifier    = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .imePadding(),
                    messages    = messages,
                    input       = input,
                    onInput     = { input = it },
                    streaming   = streaming,
                    activity    = vm.activity.value,
                    modelLabel  = vm.modelLabel.value,
                    onSend      = {
                        vm.send(input)
                        input = ""
                    },
                    onStop      = { vm.cancel() },
                    onPickModel = { vm.modelPickerOpen.value = true },
                )
            }

            if (vm.modelPickerOpen.value) {
                HelmModelSheet(
                    options       = vm.modelOptions.value,
                    selectedLabel = vm.modelLabel.value,
                    loading       = vm.modelLoading.value,
                    error         = vm.modelError.value,
                    onPick        = { option -> vm.chooseModel(option) },
                    onDismiss     = { vm.closeModelPicker() },
                )
            }
        }
    }
}

// ─── Top app bar ─────────────────────────────────────────────────────────────
// Flat, #0A background, hairline bottom.  Title "Hermes" + model-id mono.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelmTopBar(
    modelLabel: String,
    onNewClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "Hermes",
                        modifier = Modifier.size(32.dp),
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            "Hermes",
                            fontFamily = RobotoSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            color = HelmWhite100,
                        )
                        Text(
                            modelLabel,
                            fontFamily = RobotoMono,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            color = HelmWhite55,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = onVoiceClick, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice conversation", tint = HelmWhite55)
                }
                IconButton(onClick = onHistoryClick, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.Default.History, contentDescription = "History", tint = HelmWhite55)
                }
                IconButton(onClick = onNewClick, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "New conversation", tint = HelmWhite55)
                }
                IconButton(onClick = onSettingsClick, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = HelmWhite55)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = HelmCanvas,
                titleContentColor = HelmWhite100,
                navigationIconContentColor = HelmWhite55,
                actionIconContentColor = HelmWhite55,
            ),
        )
        HorizontalDivider(color = HelmBorder08, thickness = 0.5.dp)
    }
}

// ─── Main content ────────────────────────────────────────────────────────────

@Composable
private fun HelmChatContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
    messages: List<UiMessage>,
    input: String,
    onInput: (String) -> Unit,
    streaming: Boolean,
    activity: String?,
    modelLabel: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickModel: () -> Unit,
) {
    Column(modifier) {
        if (messages.isEmpty()) {
            // ── Empty / new-chat screen ──
            // design-nan.md §13: // HERMES eyebrow + "What do you need?"
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "// HERMES",
                    fontFamily = RobotoMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    letterSpacing = 0.1.sp,
                    color = HelmAccentTx,
                )
                Text(
                    "What do you need?",
                    fontFamily = RobotoSans,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = HelmWhite55,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            // ── Conversation list ──
            LazyColumn(
                state       = listState,
                modifier    = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(messages) { _, msg -> HelmMessage(msg) }
            }
        }

        // Activity / processing label
        activity?.let { label ->
            Text(
                "● $label",
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                color = HelmWhite55,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // Model selector row (flat, technical)
        HelmModelRow(
            label   = modelLabel,
            onClick = onPickModel,
        )

        // Composer
        HelmComposer(
            value       = input,
            onValueChange = onInput,
            streaming   = streaming,
            onSend      = onSend,
            onStop      = onStop,
        )
    }
}

// ─── Messages ────────────────────────────────────────────────────────────────
// design-nan.md §14: assistant = no bubble (transparent canvas),
//                    user = square #111 surface, hairline border

@Composable
private fun HelmMessage(msg: UiMessage) {
    val isUser = msg.role == "user"
    val isError = msg.isError

    if (isUser) {
        // ── User message: square #111 surface ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(HelmSurface)
                    .border(0.5.dp, HelmBorder08)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = msg.text.ifEmpty { "\u2026" },
                    fontFamily = RobotoSans,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = HelmWhite100,
                )
            }
        }
    } else {
        // ── Assistant: plain text on canvas ──
        Column(modifier = Modifier.fillMaxWidth()) {
            val color = if (isError) Color(0xFFFF5F56) else HelmWhite100.copy(alpha = 0.9f)
            Text(
                text = msg.text.ifEmpty { "\u2026" },
                fontFamily = RobotoSans,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = color,
            )
        }
    }
}

// ─── Composer ────────────────────────────────────────────────────────────────
// design-nan.md §15: #11 surface, square, 52dp min-height, max 144dp,
//                    send 40dp indigo.  Single source of truth: parent `value`.

@Composable
private fun HelmComposer(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .background(HelmSurface)
                .border(0.5.dp, HelmBorder12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Text field — multi-line, max 144dp ≈ 9 lines at 16dp line-height
            val maxLines = 8  // ~144dp / 16dp line-height
            androidx.compose.foundation.text.BasicTextField(
                value     = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = RobotoSans,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = HelmWhite100,
                ),
                maxLines = maxLines,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "Message Hermes",
                            fontFamily = RobotoSans,
                            fontSize = 16.sp,
                            color = HelmWhite35,
                        )
                    }
                    inner()
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                    if (!streaming && value.isNotBlank()) {
                        onSend()
                        onValueChange("")
                    }
                }),
            )

            // Send / Stop button
            if (streaming) {
                IconButton(onClick = onStop, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = HelmAccentTx,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // Minimal spinner in the same surface
                CircularProgressIndicator(
                    Modifier.padding(end = 4.dp).size(20.dp),
                    strokeWidth = 1.5.dp,
                    color = HelmAccentTx,
                )
            } else {
                // Square indigo send button, 40x40 visual, 44 hit area
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank() && !streaming,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(end = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = if (value.isNotBlank()) HelmAccent else HelmRaised,
                                shape = RoundedCornerShape(0.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (value.isNotBlank()) HelmWhite100 else HelmWhite35,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

// ─── Model selector row ─────────────────────────────────────────────────────
// design-nan.md §19: flat technical row, Roboto Mono model-id

@Composable
private fun HelmModelRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            color = HelmWhite55,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "▾",
            fontFamily = RobotoMono,
            fontSize = 12.sp,
            color = HelmWhite35,
        )
    }
}

// ─── Model picker sheet ──────────────────────────────────────────────────────
// design-nan.md §17: square, #16 background, hairline border, 0 radius

@Composable
private fun HelmModelSheet(
    options: List<ModelOption>,
    selectedLabel: String,
    loading: Boolean,
    error: String?,
    onPick: (ModelOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss),
        ) {}

        // Sheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(HelmRaised)
                .border(0.5.dp, HelmBorder12)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "// MODEL",
                    fontFamily = RobotoMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    letterSpacing = 0.1.sp,
                    color = HelmAccentTx,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = HelmWhite55)
                }
            }

            HorizontalDivider(color = HelmBorder08, thickness = 0.5.dp)

            if (loading) {
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 16.dp)
                        .size(26.dp),
                    strokeWidth = 1.5.dp,
                    color = HelmAccentTx,
                )
            } else {
                error?.let { msg ->
                    Text(
                        msg,
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = RobotoSans,
                        fontSize = 14.sp,
                        color = Color(0xFFFF5F56),
                    )
                }

                ModelOptionItem(
                    label      = "Automatic",
                    caption    = "Let Hermes choose",
                    selected   = selectedLabel.startsWith("Automatic"),
                    onClick    = { onPick(null) },
                )
                HorizontalDivider(color = HelmBorder08, thickness = 0.5.dp)

                if (options.isEmpty() && error == null) {
                    Text(
                        "No models available",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        fontFamily = RobotoSans,
                        fontSize = 14.sp,
                        color = HelmWhite35,
                    )
                } else {
                    for (opt in options) {
                        ModelOptionItem(
                            label      = opt.label,
                            caption    = opt.providerSlug,
                            selected   = selectedLabel == opt.label,
                            onClick    = { onPick(opt) },
                        )
                        HorizontalDivider(color = HelmBorder08, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelOptionItem(label: String, caption: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                fontFamily = RobotoMono,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 13.sp,
                color = if (selected) HelmWhite100 else HelmWhite55,
            )
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    fontFamily = RobotoMono,
                    fontSize = 12.sp,
                    color = HelmWhite35,
                )
            }
        }
        if (selected) {
            Text(
                "✓",
                fontFamily = RobotoMono,
                fontSize = 14.sp,
                color = HelmAccentTx,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
