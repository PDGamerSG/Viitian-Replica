package edu.vit.vtop.replica

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object DownloadNotificationHelper {
    private const val CHANNEL_ID = "vitian_downloads"
    private const val REQUEST_CODE_POST_NOTIFICATIONS = 6011
    private const val PREFS_NAME = "vitian_downloads"
    private const val PREF_PERMISSION_REQUESTED = "notif_permission_requested"

    fun requestPermissionIfNeeded(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(PREF_PERMISSION_REQUESTED, false)) {
            return
        }
        preferences.edit().putBoolean(PREF_PERMISSION_REQUESTED, true).apply()
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE_POST_NOTIFICATIONS,
        )
    }

    fun showDownloadStarted(context: Context, fileName: String) {
        postNotification(
            context = context,
            icon = android.R.drawable.stat_sys_download,
            title = context.getString(R.string.download_notification_started_title),
            body = context.getString(
                R.string.download_notification_started_body,
                displayName(context, fileName),
            ),
        )
    }

    fun showDownloadCompleted(context: Context, fileName: String) {
        postNotification(
            context = context,
            icon = android.R.drawable.stat_sys_download_done,
            title = context.getString(R.string.download_notification_completed_title),
            body = context.getString(
                R.string.download_notification_completed_body,
                displayName(context, fileName),
            ),
        )
    }

    private fun postNotification(
        context: Context,
        icon: Int,
        title: String,
        body: String,
    ) {
        if (!canPostNotifications(context)) {
            return
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(nextNotificationId(), notification)
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.download_notification_channel_description)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun displayName(context: Context, fileName: String): String {
        return fileName.trim().ifBlank { context.getString(R.string.download_notification_file_fallback) }
    }

    private fun nextNotificationId(): Int {
        return (System.currentTimeMillis() and 0x0FFFFFFF).toInt()
    }
}
