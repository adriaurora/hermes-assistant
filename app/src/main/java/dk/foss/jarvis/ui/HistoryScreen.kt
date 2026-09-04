package dk.foss.jarvis.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpen: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refresh() }
    val items by vm.items
    val notice by vm.notice

    Scaffold(
        containerColor = JarvisColors.Canvas,
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                "// HISTORY",
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                letterSpacing = 1.2.sp,
                                color = JarvisColors.AccentText,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = JarvisColors.TextSecondary,
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { vm.startNew(onOpen) }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "New conversation",
                                    tint = JarvisColors.TextPrimary,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = JarvisColors.Canvas,
                            titleContentColor = JarvisColors.TextPrimary,
                            navigationIconContentColor = JarvisColors.TextSecondary,
                            actionIconContentColor = JarvisColors.TextPrimary,
                        ),
                    )
                    HorizontalDivider(
                        color = JarvisColors.BorderSubtle,
                        thickness = 0.5.dp,
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "// CONVERSATIONS",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp,
                    color = JarvisColors.AccentText,
                )
                if (notice != null) {
                    Text(
                        notice.orEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        fontFamily = RobotoSans,
                        fontSize = 12.sp,
                        color = JarvisColors.TextSecondary,
                    )
                }
                if (items.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "No conversations yet",
                            fontFamily = RobotoSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            color = JarvisColors.TextSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        items(items, key = { it.key }) { entry ->
                            Row2(
                                title = entry.title,
                                subtitle = buildString {
                                    append(DateUtils.getRelativeTimeSpanString(entry.updatedAt))
                                    append(" \u00b7 ${entry.messageCount} msgs")
                                    entry.originTag?.let { append("  \u00b7  $it") }
                                },
                                deletable = entry.origin == HistoryOrigin.LOCAL,
                                onClick = {
                                    when (entry.origin) {
                                        HistoryOrigin.LOCAL ->
                                            vm.open(entry.localId!!) { onOpen() }
                                        HistoryOrigin.SERVER_PHONE, HistoryOrigin.SERVER_OTHER ->
                                            vm.openServer(
                                                entry.serverSessionId!!,
                                                entry.title,
                                                entry.updatedAt,
                                            ) { onOpen() }
                                    }
                                },
                                onDelete = { vm.delete(entry.localId!!) },
                            )
                            HorizontalDivider(
                                color = JarvisColors.BorderSubtle,
                                thickness = 0.5.dp,
                            )
                        }
                    }
                }
            }
        }
}

@Composable
private fun Row2(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deletable: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "Conversation" },
                fontFamily = RobotoSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = JarvisColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                color = JarvisColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (deletable) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = JarvisColors.TextTertiary,
                )
            }
        }
    }
}
