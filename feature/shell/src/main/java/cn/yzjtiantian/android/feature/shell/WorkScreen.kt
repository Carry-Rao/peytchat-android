package cn.yzjtiantian.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.data.dto.CardDto
import cn.yzjtiantian.android.data.dto.ChannelDto
import cn.yzjtiantian.android.data.repository.PeytRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STATUS_LABELS = mapOf(
    "todo" to "Todo",
    "in_progress" to "In Progress",
    "done" to "Done",
)

/** Kanban board for a card-space channel. */
@Composable
fun WorkScreen(
    repository: PeytRepository,
    channel: ChannelDto,
) {
    var cards by remember { mutableStateOf<List<CardDto>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        withContext(Dispatchers.IO) {
            cards = repository.listCards(channel.workspaceId, channel.chatId)
        }
    }

    LaunchedEffect(channel.chatId) {
        load()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "协作看板",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${cards.size} 个卡片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建卡片")
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(listOf("todo", "in_progress", "done")) { status ->
                    KanbanColumn(
                        title = STATUS_LABELS[status] ?: status,
                        cards = cards.filter { it.status == status },
                        onStatusChange = { card, newStatus ->
                            scope.launch(Dispatchers.IO) {
                                runCatching { repository.updateCard(card.id, null, null, newStatus, null, null) }
                                load()
                            }
                        },
                        onCreate = { title ->
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    repository.createCard(
                                        channel.workspaceId, channel.chatId,
                                        "task", title, null, null, null,
                                    )
                                }
                                load()
                            }
                        },
                        modifier = Modifier.width(280.dp),
                    )
                }
            }
            if (creating) {
                AddCardSheet(
                    onDismiss = { creating = false },
                    onCreate = { title ->
                        creating = false
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                repository.createCard(
                                    channel.workspaceId, channel.chatId,
                                    "task", title, null, null, null,
                                )
                            }
                            load()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun KanbanColumn(
    title: String,
    cards: List<CardDto>,
    onStatusChange: (CardDto, String) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = cards.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cards.forEach { card ->
                    KanbanCard(card = card, onStatusChange = onStatusChange)
                }
                AddCardButton(onClick = { onCreate("") })
            }
        }
    }
}

@Composable
private fun KanbanCard(
    card: CardDto,
    onStatusChange: (CardDto, String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            card.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(10.dp))
            StatusSwitcher(card = card, onStatusChange = onStatusChange)
        }
    }
}

@Composable
private fun StatusSwitcher(
    card: CardDto,
    onStatusChange: (CardDto, String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf("todo", "in_progress", "done").forEach { status ->
            val active = card.status == status
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { if (!active) onStatusChange(card, status) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = STATUS_LABELS[status]?.split(" ")?.first() ?: status,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun AddCardButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "添加卡片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddCardSheet(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "新建卡片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入卡片标题") },
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { if (title.isNotBlank()) onCreate(title.trim()) },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "创建")
                    }
                }
            }
        }
    }
}
