package com.hikovo.mobilepet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class DailyReminderActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      ACTION_DONE -> {
        DailyReminderStore.answer(context, "done")
        NotificationManagerCompat.from(context).cancel(DailyReminderWorker.NOTIFICATION_ID)
      }
      ACTION_LATER -> {
        DailyReminderStore.answer(context, "notYet")
        NotificationManagerCompat.from(context).cancel(DailyReminderWorker.NOTIFICATION_ID)
        DailyReminderScheduler.snooze(context)
      }
    }
  }

  companion object {
    const val ACTION_DONE = "com.hikovo.sanhaotu.daily.DONE"
    const val ACTION_LATER = "com.hikovo.sanhaotu.daily.LATER"
  }
}
