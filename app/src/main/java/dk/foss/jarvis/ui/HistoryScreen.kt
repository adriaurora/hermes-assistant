package dk.foss.jarvis.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    DeepSpaceBackground(active = false) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "History",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = JarvisColors.Cyan,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.startNew(onOpen) }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New conversation",
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
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (notice != null) {
                    Text(
                        notice.orEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        color = JarvisColors.Muted,
                    )
                }
                if (items.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "No conversations yet",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
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
                            HorizontalDivider(color = JarvisColors.Cyan.copy(alpha = 0.08f))
                        }
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
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "Conversation" },
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = JarvisColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontFamily = DmSans,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                color = JarvisColors.Muted,
            )
        }
        if (deletable) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = JarvisColors.Muted,
                )
            }
        }
    }
}
