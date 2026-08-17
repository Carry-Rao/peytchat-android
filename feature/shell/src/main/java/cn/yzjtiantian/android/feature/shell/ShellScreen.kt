package cn.yzjtiantian.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.core.AccountManager
import cn.yzjtiantian.android.core.ModuleManager
import cn.yzjtiantian.android.data.dto.ChannelDto
import cn.yzjtiantian.android.data.dto.WorkspaceDto
import cn.yzjtiantian.android.data.repository.PeytRepository
import cn.yzjtiantian.android.patchapi.ChatUiProvider
import cn.yzjtiantian.android.ui.theme.AppThemeMode
import cn.yzjtiantian.android.ui.theme.TdesignIcons
import cn.yzjtiantian.android.ui.theme.ThemeManager
import cn.yzjtiantian.android.ui.theme.ThemeSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import cn.yzjtiantian.android.ui.theme.AppThemeMode.*

private enum class Tab(val label: String) {
    Messages("消息"),
    Work("协作"),
    Inbox("通知"),
    Settings("设置"),
}

/** Actions of the top-right "+" menu, mirroring the desktop messagesPage. */
private enum class AddAction {
    SelectContact,
    AddByEmail,
    AddByLink,
    NewGroup,
    ShareInvite,
}

/** A 1:1 / group chat opened directly from the "+" menu (not a workspace channel). */
private data class DirectChat(val chatId: Long, val name: String)

/**
 * Post-login shell with bottom navigation bar + full-width pages.
 * Channels open as full-screen chat screens with a back button.
 */
@Composable
fun ShellScreen(
    repository: PeytRepository,
    onLoggedOut: () -> Unit,
    accountManager: AccountManager,
    deepLink: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    var workspaces by remember { mutableStateOf<List<WorkspaceDto>>(emptyList()) }
    var currentWorkspace by remember { mutableStateOf<WorkspaceDto?>(null) }
    var channels by remember { mutableStateOf<List<ChannelDto>>(emptyList()) }
    var dmChats by remember { mutableStateOf<List<ChannelDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentTab by remember { mutableStateOf(Tab.Messages) }
    var openChannel by remember { mutableStateOf<ChannelDto?>(null) }
    var openDirectChat by remember { mutableStateOf<DirectChat?>(null) }
    var expandMenu by remember { mutableStateOf(false) }
    var addAction by remember { mutableStateOf<AddAction?>(null) }
    var showAccountPage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshChannels(ws: WorkspaceDto) {
        currentWorkspace = ws
        scope.launch(Dispatchers.IO) {
            runCatching { repository.listChannels(ws.id) }
                .onSuccess { channels = it }
                .onFailure { error = it.message }
        }
    }

    /** 刷新 workspace 频道 + 直接消息(轮询用,不重置 currentWorkspace)。 */
    fun refreshMessages() {
        scope.launch(Dispatchers.IO) {
            currentWorkspace?.let { ws ->
                runCatching { repository.listChannels(ws.id) }
                    .onSuccess { channels = it }
                    .onFailure { error = it.message }
            }
            runCatching { repository.listDirectChats() }
                .onSuccess { dmChats = it }
                .onFailure { error = it.message }
        }
    }

    fun openDirectChatById(chatId: Long, name: String) {
        addAction = null
        openDirectChat = DirectChat(chatId, name)
    }

    /** 处理深链(`peytchat://` 等)：建单聊/securejoin 后直接跳到会话。 */
    fun openDeepLink(raw: String) {
        scope.launch {
            val chatId = withContext(Dispatchers.IO) {
                runCatching { repository.addFriend(raw) }
                    .onFailure { error = it.message }
                    .getOrNull()
            }
            if (chatId != null) {
                val name = withContext(Dispatchers.IO) {
                    repository.getChatName(chatId).ifBlank { "新会话" }
                }
                openDirectChatById(chatId, name)
            }
        }
    }

    // 深链:登录后自动处理一次,处理完回调消费掉。
    LaunchedEffect(deepLink) {
        val link = deepLink?.takeIf { it.isNotBlank() }
        if (link != null) {
            openDeepLink(link)
            onDeepLinkConsumed()
        }
    }

    fun addByEmail(address: String) {
        scope.launch {
            val chatId = withContext(Dispatchers.IO) {
                runCatching { repository.createChatByEmail(address) }
                    .onFailure { error = it.message }
                    .getOrNull()
            }
            if (chatId != null) openDirectChatById(chatId, address)
        }
    }

    fun addByLink(input: String) {
        scope.launch {
            val chatId = withContext(Dispatchers.IO) {
                runCatching { repository.addFriend(input) }
                    .onFailure { error = it.message }
                    .getOrNull()
            }
            if (chatId != null) openDirectChatById(chatId, input)
        }
    }

    fun addGroup(name: String) {
        scope.launch {
            val chatId = withContext(Dispatchers.IO) {
                runCatching { repository.createGroup(name) }
                    .onFailure { error = it.message }
                    .getOrNull()
            }
            if (chatId != null) openDirectChatById(chatId, name)
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
        // 轮询刷新:收到新消息(陌生人单聊/请求)时直接消息区能及时出现。
        while (true) {
            delay(3000)
            refreshMessages()
        }
    }

    // 系统返回:逐层收起(弹窗→菜单→账号页→聊天→非消息 Tab→退出)。
    BackHandler {
        when {
            addAction != null -> addAction = null
            expandMenu -> expandMenu = false
            showAccountPage -> showAccountPage = false
            openDirectChat != null -> openDirectChat = null
            openChannel != null -> openChannel = null
            currentTab != Tab.Messages -> currentTab = Tab.Messages
            else -> Unit // 消息页根层,交给系统退出
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

    // A direct chat (created via the "+" menu) is open -> full-screen chat.
    val direct = openDirectChat
    if (direct != null) {
        ChannelScreen(
            repository = repository,
            channel = ChannelDto(
                id = -1,
                workspaceId = -1,
                chatId = direct.chatId,
                name = direct.name,
                category = "",
                position = 0,
                topic = null,
                unread = 0,
                spaceType = "chat",
            ),
            onBack = { openDirectChat = null },
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
                            .padding(horizontal = 16.dp, vertical = 6.dp),
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
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { expandMenu = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "新建",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = expandMenu,
                                onDismissRequest = { expandMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("选择联系人") },
                                    onClick = {
                                        expandMenu = false
                                        addAction = AddAction.SelectContact
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("通过邮箱添加") },
                                    onClick = {
                                        expandMenu = false
                                        addAction = AddAction.AddByEmail
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("通过链接添加") },
                                    onClick = {
                                        expandMenu = false
                                        addAction = AddAction.AddByLink
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("新建群聊") },
                                    onClick = {
                                        expandMenu = false
                                        addAction = AddAction.NewGroup
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("分享我的邀请链接") },
                                    onClick = {
                                        expandMenu = false
                                        addAction = AddAction.ShareInvite
                                    }
                                )
                            }
                        }
                        //IconButton(onClick = { onLoggedOut() }) {
                        //    Icon(
                        //        TdesignIcons.LogOut,
                        //        contentDescription = "退出登录",
                        //        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        //    )
                        //}
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
                            onBack = { showAccountPage = false },
                            accountManager = accountManager,
                            onLoggedOut = onLoggedOut,
                        )
                    }
                    else -> {
                        when (currentTab) {
                            Tab.Messages -> MessagesList(
                                dmChats = dmChats,
                                channels = channels.filter { it.spaceType != "card" },
                                onOpenDm = { openChannel = it },
                                onOpenChannel = { openChannel = it },
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

    // "+" menu dialogs, mirroring the desktop messagesPage actions.
    when (addAction) {
        AddAction.SelectContact -> SelectContactDialog(
            repository = repository,
            onPick = { c ->
                addByEmail(c.address)
            },
            onDismiss = { addAction = null },
        )
        AddAction.AddByEmail -> InputDialog(
            title = "添加好友",
            placeholder = "输入对方邮箱地址",
            confirmLabel = "添加",
            keyboardType = KeyboardType.Email,
            onConfirm = { addByEmail(it) },
            onDismiss = { addAction = null },
        )
        AddAction.AddByLink -> InputDialog(
            title = "添加好友",
            placeholder = "粘贴邮箱 / peyt:// 邀请链接 / 链接",
            confirmLabel = "加入",
            onConfirm = { addByLink(it) },
            onDismiss = { addAction = null },
        )
        AddAction.NewGroup -> InputDialog(
            title = "创建群",
            placeholder = "输入群名称",
            confirmLabel = "创建",
            onConfirm = { addGroup(it) },
            onDismiss = { addAction = null },
        )
        AddAction.ShareInvite -> ShareInviteDialog(
            repository = repository,
            onDismiss = { addAction = null },
        )
        null -> {}
    }
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
    accountManager: AccountManager,
    onLoggedOut: () -> Unit,
) {
    val account = remember {
        accountManager.getAllAccounts().firstOrNull { it.configured }
    }

    ScrollContent(
        paddingValues = PaddingValues(0.dp),
        accountManager = accountManager,
        accountId = account?.id,
        onLoggedOut = onLoggedOut,
    )
}

/** Full-screen page for an open channel (chat or kanban) with a back button. */
@Composable
private fun ChannelScreen(
    repository: PeytRepository,
    channel: ChannelDto,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 直接聊天（好友）由 ShellScreen 包装成 id=-1 的 ChannelDto
    val isDirectChat = channel.id == -1L
    val deleteLabel = if (isDirectChat) "好友" else "群组"

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
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
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.weight(1f))
                // 删除好友/群组
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除$deleteLabel",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (channel.spaceType == "card") {
            WorkScreen(repository = repository, channel = channel)
        } else {
            // 热更新扩展点：补丁注册了 ChatUiProvider 就用补丁界面，否则回退内置 ChatScreen
            val chatProvider = remember { ModuleManager.getUiProvider("chat") as? ChatUiProvider }
            if (chatProvider != null) {
                chatProvider.ChatContent(repository = repository, channel = channel)
            } else {
                ChatScreen(repository = repository, channel = channel)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除$deleteLabel") },
            text = { Text("确定要删除「${channel.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                if (isDirectChat) repository.deleteFriend(channel.chatId)
                                else repository.deleteChat(channel.chatId)
                            }
                            onBack()
                        }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 消息页:顶部「直接消息」区 + workspace 频道。 */
@Composable
private fun MessagesList(
    dmChats: List<ChannelDto>,
    channels: List<ChannelDto>,
    onOpenDm: (ChannelDto) -> Unit,
    onOpenChannel: (ChannelDto) -> Unit,
) {
    if (dmChats.isEmpty() && channels.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无会话",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (dmChats.isNotEmpty()) {
            item(key = "dm-header") { SectionHeader(text = "直接消息") }
            itemsIndexed(dmChats) { index, ch ->
                ChannelRow(ch = ch, onClick = { onOpenDm(ch) })
                if (index < dmChats.lastIndex) {
                    DividerRow()
                }
            }
        }
        if (channels.isNotEmpty()) {
            item(key = "channel-header") { SectionHeader(text = "频道") }
            itemsIndexed(channels) { index, ch ->
                ChannelRow(ch = ch, onClick = { onOpenChannel(ch) })
                if (index < channels.lastIndex) {
                    DividerRow()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun DividerRow() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 76.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
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
            ChannelRow(ch = ch, onClick = { onSelect(ch) })
            if (index < channels.lastIndex) {
                DividerRow()
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: ChannelDto,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
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
}

/** 设置页面 */
@Composable
private fun SettingsPage(
    onLoggedOut: () -> Unit,
    onNavigateToAccount: () -> Unit,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
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

                // 检查更新行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showUpdateDialog = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "检查更新",
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

                // 退出登录行
//                Row(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .clickable { onLoggedOut() }
//                        .padding(16.dp),
//                    verticalAlignment = Alignment.CenterVertically,
//                ) {
//                    Icon(
//                        TdesignIcons.LogOut,
//                        contentDescription = null,
//                        tint = MaterialTheme.colorScheme.error,
//                    )
//                    Text(
//                        text = "退出登录",
//                        style = MaterialTheme.typography.bodyLarge,
//                        color = MaterialTheme.colorScheme.error,
//                        modifier = Modifier.padding(start = 12.dp),
//                    )
//                }
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

    // 检查更新对话框
    if (showUpdateDialog) {
        UpdateDialog(
            onDismiss = { showUpdateDialog = false }
        )
    }
}
