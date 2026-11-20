package com.kezez.tracklink.tracker

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 开机自启（可选）。Android 12+ 对后台启动定位型前台服务有限制，
 * 启动失败时退化为一条通知，引导用户点开 App 继续记录，不做任何静默失败。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // 设计上没有"停止"概念：只要装着且有定位权限，开机/升级后就恢复记录
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { TrackService.start(context) }.onFailure { promptUser(context) }
    }

    private fun promptUser(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, "tracklink_tracking")
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("轨迹记录未启动")
            .setContentText("系统限制了开机自启，点这里打开 App 手动开始记录")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(4102, notification) }
    }
}
