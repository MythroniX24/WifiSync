package com.example.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import android.util.Log

object WaveDropNotificationHelper {
    private const val CHANNEL_ID = "wavedrop_file_channel"
    private const val CHANNEL_NAME = "WaveDrop File Transfers"
    private const val CHANNEL_DESC = "Notifications for incoming and completed file transfers in Space"
    private var isChannelCreated = false

    fun createNotificationChannel(context: Context) {
        if (isChannelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            isChannelCreated = true
        }
    }

    fun showFileTransferNotification(context: Context, fileName: String, senderName: String, sizeBytes: Long) {
        try {
            createNotificationChannel(context)
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val sizeStr = if (sizeBytes > 1024 * 1024) {
                String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0))
            } else if (sizeBytes > 1024) {
                String.format("%.2f KB", sizeBytes / 1024.0)
            } else {
                "$sizeBytes Bytes"
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("File Received in Space")
                .setContentText("'$fileName' ($sizeStr) sent by $senderName")
                .setSubText("WaveDrop Sharing")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
            Log.d("WaveDropNotify", "Notification shown for file: $fileName")
        } catch (e: Exception) {
            Log.e("WaveDropNotify", "Failed to display notification", e)
        }
    }
}
