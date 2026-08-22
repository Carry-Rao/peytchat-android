package cn.yzjtiantian.android.patch

import android.content.Context
import cn.yzjtiantian.android.core.MessageNotificationHook
import cn.yzjtiantian.android.core.ModuleManager

/**
 * notification 模块热更新补丁入口（约定类名 `cn.yzjtiantian.android.patch.NotificationPatch`）。
 *
 * 客户端 HotUpdateManager 按约定反射调用：
 * `module()` / `version()` / `description()` / `apply(Context)`。
 *
 * `apply()` 在进程启动、补丁加载时于后台线程执行，这里注册一个
 * [MessageNotificationHook]（消息通知定制钩子），让「通知行为」无需发版即可热更新。
 */
class NotificationPatch {

    fun module(): String = "notification"

    fun version(): String = "0.0.1"

    fun description(): String = "通知补丁（示例：消息通知正文加前缀）"

    fun apply(context: Context): Boolean {
        ModuleManager.registerPatchService(
            MessageNotificationHook.SERVICE_KEY,
            NotificationTextHook(),
        )
        return true
    }
}
