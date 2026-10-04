package com.hikovo.mobilepet

import android.content.Context
import java.time.LocalDate

data class DailyReminderSnapshot(
  val enabled: Boolean,
  val message: String,
  val hour: Int,
  val minute: Int,
  val lastShownDate: String,
  val answerDate: String,
  val answer: String,
)

object DailyReminderStore {
  private const val PREFS = "sanhao_tu_daily_reminder"
  private const val DEFAULT_MESSAGE = "今天超话签到了嘛？"

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  fun today(): String = LocalDate.now().toString()

  fun read(context: Context): DailyReminderSnapshot {
    val prefs = prefs(context)
    val storedMessage = prefs.getString("message", DEFAULT_MESSAGE)?.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE
    val message = if (storedMessage == "今天做超 LIKE 了吗？" || storedMessage == "今天超话签到了吗？") DEFAULT_MESSAGE else storedMessage
    if (message != storedMessage) prefs.edit().putString("message", message).apply()
    return DailyReminderSnapshot(
      enabled = prefs.getBoolean("enabled", true),
      message = message,
      hour = prefs.getInt("hour", 20).coerceIn(0, 23),
      minute = prefs.getInt("minute", 0).coerceIn(0, 59),
      lastShownDate = prefs.getString("lastShownDate", "") ?: "",
      answerDate = prefs.getString("answerDate", "") ?: "",
      answer = prefs.getString("answer", "") ?: "",
    )
  }

  fun saveSettings(context: Context, enabled: Boolean, message: String, hour: Int, minute: Int) {
    prefs(context).edit()
      .putBoolean("enabled", enabled)
      .putString("message", message.trim().take(40).ifBlank { DEFAULT_MESSAGE })
      .putInt("hour", hour.coerceIn(0, 23))
      .putInt("minute", minute.coerceIn(0, 59))
      .apply()
  }

  fun markShown(context: Context) {
    prefs(context).edit().putString("lastShownDate", today()).apply()
  }

  fun answer(context: Context, answer: String) {
    val safeAnswer = if (answer == "done") "done" else "notYet"
    prefs(context).edit()
      .putString("answerDate", today())
      .putString("answer", safeAnswer)
      .apply()
  }
}
