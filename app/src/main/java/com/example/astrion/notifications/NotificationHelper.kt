package com.example.astrion.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.example.astrion.MainActivity
import com.example.astrion.R

private const val PANEL_SERVICE_CHANNEL_ID = "PanelService"

fun createPanelServiceNotificationChannel(context: Context) {
    val channelName = "Astrion Panel Background Service"
    val chan = NotificationChannel(
        PANEL_SERVICE_CHANNEL_ID,
        channelName,
        NotificationManager.IMPORTANCE_NONE
    )
    chan.lightColor = Color.BLUE
    chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(chan)
}

fun createPanelServiceNotification(context: Context, content: String): Notification {
    val notificationBuilder =
        NotificationCompat.Builder(context, PANEL_SERVICE_CHANNEL_ID)

    // Open the app when the notification is clicked
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        },
        PendingIntent.FLAG_IMMUTABLE
    )
    val notification = notificationBuilder.setOngoing(true)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(content)
        .setPriority(NotificationManager.IMPORTANCE_LOW)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setContentIntent(pendingIntent)
        .build()
    return notification
}