// ThemeManager.kt
package cn.yzjtiantian.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

object ThemeManager {
    private val _themeMode = MutableStateFlow(AppThemeMode.DARK)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    fun setTheme(mode: AppThemeMode) {
        _themeMode.value = mode
    }

    // 修复：移除错误的 ACCENT_COLOR 引用，使用简单判断
    fun isDarkTheme(mode: AppThemeMode = _themeMode.value): Boolean {
        return when (mode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM -> false // 简化版本，实际应使用 isSystemInDarkTheme()
        }
    }
}

@Composable
fun rememberThemeMode(): AppThemeMode {
    val mode by ThemeManager.themeMode.collectAsState()
    return mode
}