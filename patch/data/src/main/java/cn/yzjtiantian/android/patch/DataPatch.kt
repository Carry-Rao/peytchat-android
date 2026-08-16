package cn.yzjtiantian.android.patch

import android.content.Context
import cn.yzjtiantian.android.core.ModuleManager
import cn.yzjtiantian.android.core.TextSendHook

/**
 * data 模块热更新补丁入口（约定类名 `cn.yzjtiantian.android.patch.DataPatch`）。
 *
 * 客户端 HotUpdateManager 按约定反射调用：
 * `module()` / `version()` / `description()` / `apply(Context)`。
 *
 * `apply()` 在进程启动、补丁加载时于后台线程执行，这里注册一个
 * [TextSendHook]（发送文本钩子）：之后基座 `PeytRepository.sendMessage`
 * 发出的每条用户文本都会被自动追加后缀——**数据层行为真实被补丁改变**，
 * 用于验证热更新全链路（下载→校验→加载→apply→生效）。
 */
class DataPatch {

    fun module(): String = "data"

    fun version(): String = "0.0.1"

    fun description(): String = "数据层补丁：发送文本自动加后缀"

    fun apply(context: Context): Boolean {
        ModuleManager.registerPatchService(
            TextSendHook.SERVICE_KEY,
            DataTextHook(),
        )
        return true
    }
}
