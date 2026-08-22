package cn.yzjtiantian.android.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知链路诊断日志：logcat + 文件双写。
 *
 * 文件在 `filesDir/notify.log`，调试包可用
 * `adb exec-out run-as cn.yzjtiantian.android cat files/notify.log` 拉取；
 * 设置页「消息通知」对话框可直接展示最近记录，无需 adb。
 */
object NotifyLog {

    private const val TAG = "PEYT-notify"
    private const val FILE_NAME = "notify.log"
    private const val MAX_FILE_BYTES = 128 * 1024
    private val lock = Any()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** 记录一行（logcat + 文件）。 */
    fun log(context: Context, message: String) {
        Log.d(TAG, message)
        synchronized(lock) {
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.length() > MAX_FILE_BYTES) {
                    // 只保留尾部，防止无限膨胀
                    val lines = file.readLines().takeLast(300)
                    file.writeText(lines.joinToString("\n") + "\n")
                }
                file.appendText("[${timeFmt.format(Date())}] $message\n")
            } catch (_: Exception) {
            }
        }
    }

    /** 最近日志（文件尾），供设置页展示。 */
    fun readTail(context: Context, maxLines: Int = 60): String {
        synchronized(lock) {
            return try {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return "（暂无日志）"
                file.readLines().takeLast(maxLines).joinToString("\n")
            } catch (_: Exception) {
                "（读取失败）"
            }
        }
    }

    /** 清空诊断日志。 */
    fun clear(context: Context) {
        synchronized(lock) {
            try {
                File(context.filesDir, FILE_NAME).delete()
            } catch (_: Exception) {
            }
        }
    }
}
