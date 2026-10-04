package com.hikovo.mobilepet

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object DailyReminderScheduler {
  const val DAILY_WORK = "sanhao-tu-daily-question"
  const val SNOOZE_WORK = "sanhao-tu-daily-question-snooze"

  fun sync(context: Context) {
    val state = DailyReminderStore.read(context)
    if (!state.enabled) {
      WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK)
      WorkManager.getInstance(context).cancelUniqueWork(SNOOZE_WORK)
      return
    }
    val now = ZonedDateTime.now()
    var next = now.withHour(state.hour).withMinute(state.minute).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusDays(1)
    enqueue(context, DAILY_WORK, Duration.between(now, next).toMillis())
  }

  fun snooze(context: Context, minutes: Long = 60L) {
    enqueue(context, SNOOZE_WORK, TimeUnit.MINUTES.toMillis(minutes))
  }

  private fun enqueue(context: Context, name: String, delayMs: Long) {
    val request = OneTimeWorkRequestBuilder<DailyReminderWorker>()
      .setInputData(Data.Builder().putString("eventId", ((System.currentTimeMillis() + delayMs) / 1000 * 1000).toString()).build())
      .setInitialDelay(delayMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
  }
}
