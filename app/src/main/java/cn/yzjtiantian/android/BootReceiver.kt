package cn.yzjtiantian.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机/升级后自启消息接收服务：
 * - `BOOT_COMPLETED`：设备重启后恢复常驻服务（Android 12+ 对 BOOT_COMPLETED
 *   接收器启动前台服务有豁免；Android 15 起 `dataSync` 型被禁，故服务用
 *   `specialUse` 类型，见 AndroidManifest.xml）；
 * - `MY_PACKAGE_REPLACED`：App 升级后进程被杀，需要重新拉起服务。
 *
 * 仅当存在已配置账号时才启动（未登录无需常驻）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        try {
            if (MessageNotificationService.shouldRun(context)) {
                MessageNotificationService.start(context)
                Log.d("PEYT", "[boot] message service restarted")
            }
        } catch (e: Exception) {
            Log.w("PEYT", "[boot] start message service failed", e)
        }
    }
}
