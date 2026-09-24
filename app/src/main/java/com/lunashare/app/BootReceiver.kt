package com.lunashare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lunashare.app.adb.AdbTunnelStore
import com.lunashare.app.config.ShareConfigStore
import com.lunashare.app.service.ShareService

/**
 * Boot receiver — auto-starts LunaShare after device boot when the app-level
 * "boot auto-start" switch (Settings → 启动设置 → 开机自启) is enabled.
 *
 * Strategy (double insurance against OS restrictions):
 *   1. Start the foreground [ShareService] first — foreground services are
 *      exempt from background-start limits. Shares marked "start on app
 *      launch" are recovered by the service.
 *   2. Then try to bring up the [MainActivity] UI. On Android 10+ this may be
 *      silently blocked by the system, in which case the service still runs
 *      and the user can open the app from the notification.
 *
 * NOTE: on some OEM ROMs (vivo, Xiaomi, ...) the BOOT_COMPLETED broadcast is
 * not delivered at all until the user allows "auto-start" for the app in
 * system settings — that is a ROM-level restriction no app can bypass.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val configStore = ShareConfigStore(context)
        val adbStore = AdbTunnelStore(context)
        val adbCfg = adbStore.loadConfig()
        val fileBoot = configStore.isBootAutoStart()
        val adbBoot = adbCfg?.bootAutoStart == true
        if (!fileBoot && !adbBoot) {
            Log.d(TAG, "Boot completed, but app and ADB tunnel auto-start are both off — skip")
            return
        }

        Log.i(TAG, "Boot completed, auto-starting LunaShare")

        // 1) Foreground service (exempt from background-start restrictions)
        try {
            val svc = Intent(context, ShareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.i(TAG, "ShareService started after boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ShareService after boot", e)
        }

        // 2) Try to bring up the app UI (may be blocked on Android 10+)
        try {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(launch)
            Log.i(TAG, "MainActivity launched after boot")
        } catch (e: Exception) {
            Log.w(TAG, "Background activity start blocked; service keeps running", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
