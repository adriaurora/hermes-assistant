package dk.foss.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.foss.jarvis.data.UiMessage

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
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                JarvisMark()
                                Text(
                                    "Hermes Assistant",
                                    fontFamily = SpaceGrotesk,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = JarvisColors.TextPrimary,
                            actionIconContentColor = JarvisColors.Cyan,
                            navigationIconContentColor = JarvisColors.Cyan,
                        ),
                        actions = {
                            IconButton(onClick = onOpenVoice) {
                                Icon(Icons.Default.Mic, contentDescription = "Voice conversation")
                            }
                            IconButton(onClick = onOpenHistory) {
                                Icon(Icons.Default.History, contentDescription = "History")
                            }
                            IconButton(onClick = { vm.newConversation() }) {
                                Icon(Icons.Default.Add, contentDescription = "New conversation")
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    if (messages.isEmpty()) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Ask Hermes anything",
                                fontFamily = SpaceGrotesk,
                                fontWeight = FontWeight.Medium,
                                fontSize = 18.sp,
                                color = JarvisColors.Muted,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(messages) { msg -> MessageBubble(msg) }
                        }
                    }

                    val activity by vm.activity
                    activity?.let { label ->
                        Text(
                            "\u2022 $label",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, bottom = 4.dp),
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Cyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val modelLabel by vm.modelLabel
                    ModelChip(
                        label = modelLabel,
                        onClick = { vm.modelPickerOpen.value = true },
                        modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                    )

                    InputBar(
                        value = input,
                        onValueChange = { input = it },
                        streaming = streaming,
                        onSend = {
                            vm.send(input)
                            input = ""
                        },
                        onStop = { vm.cancel() },
                    )
                }
            }

            if (vm.modelPickerOpen.value) {
                ModelPickerSheet(
                    options = vm.modelOptions.value,
                    selectedLabel = vm.modelLabel.value,
                    loading = vm.modelLoading.value,
                    error = vm.modelError.value,
                    onPick = { option -> vm.chooseModel(option) },
                    onDismiss = { vm.closeModelPicker() },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    val isUser = msg.role == "user"
    val isError = msg.isError

    val bubbleColor = when {
        isError -> JarvisColors.ErrorOrange.copy(alpha = 0.16f)
        isUser -> JarvisColors.Cyan.copy(alpha = 0.16f)
        else -> JarvisColors.GlassBg
    }
    val borderColor = when {
        isError -> JarvisColors.ErrorOrange.copy(alpha = 0.25f)
        isUser -> JarvisColors.Cyan.copy(alpha = 0.25f)
        else -> JarvisColors.GlassBorder
    }
    val textColor = when {
        isError -> JarvisColors.ErrorOrange
        isUser -> JarvisColors.TextPrimaryAlpha
        else -> JarvisColors.TextPrimary.copy(alpha = 0.9f)
    }
    val shape = when {
        isUser -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
        else -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(bubbleColor, shape)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = msg.text.ifEmpty { "\u2026" },
                color = textColor,
                fontFamily = DmSans,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val shape = RoundedCornerShape(99.dp)
    Surface(
        color = JarvisColors.GlassBg,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.2f), shape)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Message Hermes",
                        fontFamily = DmSans,
                        color = JarvisColors.Muted,
                    )
                },
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = JarvisColors.Cyan,
                    focusedTextColor = JarvisColors.TextPrimary,
                    unfocusedTextColor = JarvisColors.TextPrimary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (!streaming) onSend() }),
            )
            if (streaming) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = JarvisColors.Cyan,
                    )
                }
            } else {
                IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank()) JarvisColors.Cyan else JarvisColors.Muted,
                    )
                }
            }
            if (streaming) {
                CircularProgressIndicator(
                    Modifier.padding(start = 4.dp).size(20.dp),
                    strokeWidth = 2.dp,
                    color = JarvisColors.Cyan,
                )
            }
        }
    }
}

/** Compact, tappable indicator of the current model selection (server-authoritative). */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(99.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(JarvisColors.GlassBg, shape)
            .border(1.dp, JarvisColors.CyanBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = JarvisColors.CyanText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = " ▾",
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = JarvisColors.Muted,
        )
    }
}

/** Bottom sheet listing Automatic plus the models Hermes advertises. */
@Composable
private fun ModelPickerSheet(
    options: List<ModelOption>,
    selectedLabel: String,
    loading: Boolean,
    error: String?,
    onPick: (ModelOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss),
        ) {}
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(JarvisColors.WindowBg, sheetShape)
                .border(1.dp, JarvisColors.GlassBorder, sheetShape)
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Model",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = JarvisColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        if (loading) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(26.dp),
                    strokeWidth = 2.dp,
                    color = JarvisColors.Cyan,
                )
            }
        } else {
            error?.let { msg ->
                Text(
                    msg,
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.ErrorOrange,
                )
            }

            ModelOptionRow(
                label = "Automatic",
                caption = "Let Hermes choose",
                selected = selectedLabel.startsWith("Automatic"),
                onClick = { onPick(null) },
            )
            HorizontalDivider(color = JarvisColors.Cyan.copy(alpha = 0.08f))

            if (options.isEmpty() && error == null) {
                Text(
                    "No models available",
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
            } else {
                for (opt in options) {
                    ModelOptionRow(
                        label = opt.label,
                        caption = opt.providerSlug,
                        selected = selectedLabel == opt.label,
                        onClick = { onPick(opt) },
                    )
                    HorizontalDivider(color = JarvisColors.Cyan.copy(alpha = 0.08f))
                }
            }
        }
    }
    }
}

@Composable
private fun ModelOptionRow(label: String, caption: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                fontFamily = DmSans,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 15.sp,
                color = if (selected) JarvisColors.Cyan else JarvisColors.TextPrimary,
            )
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )
            }
        }
        if (selected) {
            Text(
                "✓",
                fontFamily = DmSans,
                fontSize = 16.sp,
                color = JarvisColors.Cyan,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
