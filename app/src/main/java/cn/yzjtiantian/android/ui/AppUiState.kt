package cn.yzjtiantian.android.ui

import cn.yzjtiantian.android.data.dto.WorkspaceDto
import cn.yzjtiantian.android.data.dto.ChannelDto

/** App-level navigation/selection state, persisted across recompositions. */
class AppUiState {
    var loggedIn: Boolean = false

    var currentWorkspace: WorkspaceDto? = null
    var workspaces: List<WorkspaceDto> = emptyList()

    var currentChannel: ChannelDto? = null
    var channels: List<ChannelDto> = emptyList()
}
