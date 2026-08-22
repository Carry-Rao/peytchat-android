package cn.yzjtiantian.android.patch

import android.content.Context
import cn.yzjtiantian.android.core.ModuleManager

/**
 * chat 模块热更新补丁入口（约定类名 `cn.yzjtiantian.android.patch.ChatPatch`）。
 *
 * 客户端 HotUpdateManager 按约定反射调用：
 * `module()` / `version()` / `description()` / `apply(Context)`。
 *
 * `apply()` 在进程启动、补丁加载时于后台线程执行，这里把聊天界面实现
 * [ChatUiV2] 注册进 [ModuleManager]，基座 `ShellScreen` 渲染聊天区时
 * 检测到注册即改用补丁界面。
 */
class ChatPatch {

    fun module(): String = "chat"

    fun version(): String = "1.0.1"

    fun description(): String = "补丁版聊天界面（示例：ChatUiV2）"

    fun apply(context: Context): Boolean {
        ModuleManager.registerUiProvider("chat", ChatUiV2())
        return true
    }
}
