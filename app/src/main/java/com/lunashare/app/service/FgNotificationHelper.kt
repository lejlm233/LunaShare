package com.lunashare.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lunashare.app.MainActivity
import com.lunashare.app.R

/**
 * 前台服务通知协调器：共享（ShareService）与组网（LunaVpnService）共用
 * 一条常驻通知（同 channel + 同 notificationId），状态文本聚合显示。
 *
 * Android 允许多个前台服务 startForeground 同一个通知 ID——系统只显示一条，
 * 两个服务都能因此获得前台豁免；任一服务更新时全量重绘聚合文本。
 *
 * 注意：服务退出时用 STOP_FOREGROUND_DETACH（不 remove），否则会把对方
 * 还在用的通知一并撤掉；detach 后再由 [refresh] 按剩余状态重绘。
 */
object FgNotificationHelper {

    const val CHANNEL_ID = "lunashare_service_channel"
    const val NOTIFICATION_ID = 1001

    /** 共享侧状态文本（ShareService 维护，如 "HTTP: 1 | FTP: 2"，空 = 无）。 */
    @Volatile var shareText: String = ""
        private set

    /** 组网侧状态文本（LunaVpnService 维护，如 "运行中 10.126.126.3/24"，空 = 无）。 */
    @Volatile var vpnText: String = ""
        private set

    /** 连接共享侧状态文本（RemoteConnectionManager 维护，如 "远程: 2 个连接"，空 = 无）。 */
    @Volatile var remoteText: String = ""
        private set

    fun updateShare(text: String) {
        shareText = text
    }

    fun updateVpn(text: String) {
        vpnText = text
    }

    fun clearVpn() {
        vpnText = ""
    }

    fun updateRemote(text: String) {
        remoteText = text
    }

    fun clearRemote() {
        remoteText = ""
    }

    /** 聚合标题：多方都有时逐段显示，只有一方时显示该方。 */
    fun combinedText(): String = buildString {
        if (shareText.isNotBlank()) append(shareText)
        if (vpnText.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append("组网: ").append(vpnText)
        }
        if (remoteText.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(remoteText)
        }
        if (isEmpty()) append("已停止")
    }

    fun buildNotification(context: Context): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.service_name))
            .setContentText(combinedText())
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 按当前聚合状态重绘通知；两侧都空时撤掉通知（避免残留「已停止」常驻）。 */
    fun refresh(context: Context) {
        runCatching {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (shareText.isBlank() && vpnText.isBlank()) {
                nm.cancel(NOTIFICATION_ID)
            } else {
                nm.notify(NOTIFICATION_ID, buildNotification(context))
            }
        }
    }

    /** 确保通知渠道存在（幂等；两个服务 onCreate 时都调用）。 */
    fun ensureChannel(context: Context) {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.service_channel_name),
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.service_channel_desc)
            setShowBadge(false)
        }
        context.getSystemService(android.app.NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
