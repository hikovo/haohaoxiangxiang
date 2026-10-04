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

class DailyReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    val context = applicationContext
    val state = DailyReminderStore.read(context)
    if (!state.enabled) return Result.success()
    if (!(state.answerDate == DailyReminderStore.today() && state.answer == "done")) {
      val event = inputData.getString("eventId").orEmpty()
      if (NotificationManagerCompat.from(context).areNotificationsEnabled() && event.isNotEmpty() && !ReminderStyle.claimDelivery(context, "daily", "", event)) {
        DailyReminderScheduler.sync(context)
        return Result.success()
      }
      if (ReminderStyle.read(context, "daily").popup && OverlayPetService.isRunning) {
        context.startService(
          Intent(context, OverlayPetService::class.java)
            .setAction(OverlayPetService.ACTION_SHOW_MESSAGE)
            .putExtra(OverlayPetService.EXTRA_MESSAGE, state.message),
        )
      }
      showNotification(context, state.message)
      DailyReminderStore.markShown(context)
    }
    DailyReminderScheduler.sync(context)
    return Result.success()
  }

  private fun showNotification(context: Context, message: String) {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    val channelId = ReminderStyle.channel(context, "daily")

    val doneIntent = Intent(context, DailyReminderActionReceiver::class.java).setAction(DailyReminderActionReceiver.ACTION_DONE)
    val laterIntent = Intent(context, DailyReminderActionReceiver::class.java).setAction(DailyReminderActionReceiver.ACTION_LATER)
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

    val notification = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(R.drawable.ic_sanhao_notification)
      .setLargeIcon(NotificationRabbit.portrait(context))
      .setContentTitle("三好兔来问候你啦")
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .addAction(0, "做啦", PendingIntent.getBroadcast(context, 201, doneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      .addAction(0, "1 小时后", PendingIntent.getBroadcast(context, 202, laterIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      .apply {
        launchIntent?.let {
          setContentIntent(PendingIntent.getActivity(context, 203, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
      }
      .build()

    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
  }

  companion object {
    const val CHANNEL_ID = "sanhao_tu_daily_question_v2"
    const val NOTIFICATION_ID = 212
  }
}
