package com.lunashare.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lunashare.app.MainActivity
import com.lunashare.app.R

/** 通知点击携带的 extra：要求 MainActivity 打开「文件」Tab（已接收的文件就在那里）。 */
const val TRANSFER_EXTRA_OPEN_FILES_TAB = "transfer_open_files_tab"

/**
 * WebDAV / 加密上传「文件传输完成」→ 系统通知。
 *
 * 触发点（见 [FileServer]）：
 *  - `PUT` 写入成功（201 Created）→ 标准 WebDAV 客户端上传完成
 *  - `POST /_encupload` 解密落盘成功（201）→ 加密上传脚本（luna-send）传完成
 *
 * 下载（GET）/ 列举（PROPFIND）/ 删除（DELETE）等不是「传输」，不发通知。
 * 批量上传由 ShareService 在 1.2s 窗口内合并为一条，避免刷屏。
 */
object TransferNotifier {

    private const val CHANNEL = "transfer_events"
    private const val CHANNEL_NAME = "文件传输完成"
    private const val NOTIF_ID = 0x7a01
    private const val TAG = "TransferNotifier"

    /** 创建通知渠道（Android 8+ 必需；无需运行时权限即可建渠道）。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(
                    CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "WebDAV / 加密上传文件传输完成时提醒"
                    setShowBadge(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 160, 80, 160)
                    enableLights(true)
                    lightColor = 0xFF0A84FF.toInt()
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            Log.w(TAG, "通知权限未授予，跳过传输通知")
            return false
        }
        return true
    }

    /** 通知点击 → 回到 MainActivity 并落到「文件」Tab（无论当前在哪个 Tab）。 */
    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(TRANSFER_EXTRA_OPEN_FILES_TAB, true)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 发送「文件已接收」通知（text 由 ShareService 合并后传入）。 */
    fun notifyTransferDone(context: Context, text: String) {
        try {
            val appCtx = context.applicationContext
            ensureChannel(appCtx)
            if (!canNotify(appCtx)) return
            val builder = NotificationCompat.Builder(appCtx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify_download)
                .setContentTitle("LunaShare · 文件已接收")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(appCtx))
            NotificationManagerCompat.from(appCtx).notify(NOTIF_ID, builder.build())
            Log.d(TAG, "notify transfer: $text")
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
        }
    }
}
