package cn.yzjtiantian.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.data.dto.ChannelDto
import cn.yzjtiantian.android.data.dto.WorkspaceDto
import cn.yzjtiantian.android.data.repository.PeytRepository
import cn.yzjtiantian.android.ui.theme.AppThemeMode
import cn.yzjtiantian.android.ui.theme.TdesignIcons
import cn.yzjtiantian.android.ui.theme.ThemeManager
import cn.yzjtiantian.android.ui.theme.ThemeSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) {
    Messages("消息"),
    Work("协作"),
    Inbox("通知"),
    Settings("设置"),
}

/**
 * Post-login shell with bottom navigation bar + full-width pages.
 * Channels open as full-screen chat screens with a back button.
 */
@Composable
fun ShellScreen(
    repository: PeytRepository,
    onLoggedOut: () -> Unit,
) {
    var workspaces by remember { mutableStateOf<List<WorkspaceDto>>(emptyList()) }
    var currentWorkspace by remember { mutableStateOf<WorkspaceDto?>(null) }
    var channels by remember { mutableStateOf<List<ChannelDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentTab by remember { mutableStateOf(Tab.Messages) }
    var openChannel by remember { mutableStateOf<ChannelDto?>(null) }
    var showAccountPage by remember { mutableStateOf(false) }  // 控制账号页面显示
    val scope = rememberCoroutineScope()

    fun refreshChannels(ws: WorkspaceDto) {
        currentWorkspace = ws
        scope.launch(Dispatchers.IO) {
            runCatching { repository.listChannels(ws.id) }
                .onSuccess { channels = it }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                repository.ensurePeytStudio()
                val ws = repository.listWorkspaces()
                workspaces = ws
                ws.firstOrNull()?.let { refreshChannels(it) }
            }.onFailure { error = it.message }
        }
    }

    // 频道打开 -> 全屏聊天/看板
    val open = openChannel
    if (open != null) {
        ChannelScreen(
            repository = repository,
            channel = open,
            onBack = { openChannel = null },
        )
        return
    }

    Scaffold(
        topBar = {
            // 如果是账号页面，显示不同的 TopBar
            if (showAccountPage) {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showAccountPage = false }) {
                            Icon(
                                TdesignIcons.ChevronLeft,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = "账号",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTab.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = currentWorkspace?.name ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onLoggedOut() }) {
                            Icon(
                                TdesignIcons.LogOut,
                                contentDescription = "退出登录",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // 账号页面隐藏底部导航栏
            if (!showAccountPage) {
                BottomNavBar(
                    current = currentTab,
                    onSelect = { currentTab = it },
                )
            }
        },
        content = { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when {
                    showAccountPage -> {
                        // 显示账号页面
                        AccountPage(
                            onBack = { showAccountPage = false }
                        )
                    }
                    else -> {
                        when (currentTab) {
                            Tab.Messages -> ChannelList(
                                channels = channels.filter { it.spaceType != "card" },
                                onSelect = { openChannel = it },
                            )
                            Tab.Work -> ChannelList(
                                channels = channels.filter { it.spaceType == "card" },
                                onSelect = { openChannel = it },
                            )
                            Tab.Inbox -> InboxScreen(
                                repository = repository,
                                onOpenChannel = { chatId ->
                                    val ch = channels.firstOrNull { it.chatId == chatId }
                                    if (ch != null) openChannel = ch
                                    else error = "频道未找到"
                                },
                            )
                            Tab.Settings -> SettingsPage(
                                onLoggedOut = onLoggedOut,
                                onNavigateToAccount = { showAccountPage = true }
                            )
                        }
                    }
                }

                if (error != null) {
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun BottomNavBar(
    current: Tab,
    onSelect: (Tab) -> Unit,
) {
    NavigationBar {
        listOf(
            Tab.Messages to TdesignIcons.MessageCircle,
            Tab.Work to TdesignIcons.LayoutGrid,
            Tab.Inbox to TdesignIcons.Inbox,
            Tab.Settings to TdesignIcons.Settings,
        ).forEach { (tab, icon) ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

/** 账号页面 */
@Composable
private fun AccountPage(
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "账号信息",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.padding(16.dp))
        // 这里可以添加账号信息内容
        Text(
            text = "用户信息待实现",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Full-screen page for an open channel (chat or kanban) with a back button. */
@Composable
private fun ChannelScreen(
    repository: PeytRepository,
    channel: ChannelDto,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        TdesignIcons.ChevronLeft,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "# ${channel.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (channel.spaceType == "card") {
            WorkScreen(repository = repository, channel = channel)
        } else {
            ChatScreen(repository = repository, channel = channel)
        }
    }
}

/** Channel list: iMessage-style rows with avatar, name, preview and time. */
@Composable
private fun ChannelList(
    channels: List<ChannelDto>,
    onSelect: (ChannelDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无频道",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        itemsIndexed(channels) { index, ch ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(ch) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ch.name.firstOrNull()?.uppercase() ?: "#",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = ch.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (ch.unread > 0) {
                        Text(
                            text = "${ch.unread} 条未读",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    } else {
                        ch.topic?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (index < channels.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/** 设置页面 */
@Composable
private fun SettingsPage(
    onLoggedOut: () -> Unit,
    onNavigateToAccount: () -> Unit,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    val currentTheme by ThemeManager.themeMode.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column {
                // 账号行 - 点击跳转到账号页面
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToAccount() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "账号",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        TdesignIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // 主题切换行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showThemeDialog = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        TdesignIcons.Theme,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "主题",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 显示当前主题
                    Text(
                        text = when (currentTheme) {
                            AppThemeMode.LIGHT -> "浅色"
                            AppThemeMode.DARK -> "深色"
                            AppThemeMode.SYSTEM -> "跟随系统"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        TdesignIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // 退出登录行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLoggedOut() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        TdesignIcons.LogOut,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "退出登录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }

    // 主题切换对话框
    if (showThemeDialog) {
        ThemeSelectionDialog(
            onDismiss = { showThemeDialog = false },
            currentTheme = currentTheme,
            onThemeSelected = { newTheme ->
                ThemeManager.setTheme(newTheme)
                showThemeDialog = false
            }
        )
    }
}