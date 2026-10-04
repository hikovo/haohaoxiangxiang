package com.hikovo.mobilepet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class BubbleReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    val message = inputData.getString(KEY_MESSAGE).orEmpty().trim()
    if (message.isBlank()) return Result.success()

    val kind = inputData.getString(KEY_KIND)?.takeIf { it in ReminderStyle.kinds } ?: "todo"
    val reminderId = inputData.getString(KEY_ID).orEmpty()
    val event = inputData.getString(KEY_EVENT).orEmpty()
    // A disabled notification must not consume the in-app sound/vibration event.
    val notificationsAllowed = NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
    if (notificationsAllowed && event.isNotEmpty() && !ReminderStyle.claimDelivery(applicationContext, kind, reminderId, event)) return Result.success()
    if (ReminderStyle.read(applicationContext, kind, reminderId).popup && OverlayPetService.isRunning) {
      applicationContext.startService(
        Intent(applicationContext, OverlayPetService::class.java)
          .setAction(OverlayPetService.ACTION_SHOW_MESSAGE)
          .putExtra(OverlayPetService.EXTRA_MESSAGE, message),
      )
    }
    showNotification(message, kind, reminderId)
    return Result.success()
  }

  private fun showNotification(message: String, kind: String, reminderId: String) {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    val channelId = ReminderStyle.channel(applicationContext, kind, reminderId)
    val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
    val notification = NotificationCompat.Builder(applicationContext, channelId)
      .setSmallIcon(R.drawable.ic_sanhao_notification)
      .setLargeIcon(NotificationRabbit.portrait(applicationContext))
      .setContentTitle("三好兔提醒你")
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .apply {
        launchIntent?.let {
          setContentIntent(PendingIntent.getActivity(
            applicationContext,
            message.hashCode(),
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          ))
        }
      }
      .build()
    NotificationManagerCompat.from(applicationContext).notify(message.hashCode(), notification)
  }

  companion object {
    const val KEY_MESSAGE = "message"
    const val KEY_KIND = "kind"
    const val KEY_ID = "reminderId"
    const val KEY_EVENT = "eventId"
    const val CHANNEL_ID = "sanhao_tu_timed_reminders_v2"
  }
}
