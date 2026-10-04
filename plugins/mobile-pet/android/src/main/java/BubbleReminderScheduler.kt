package com.hikovo.mobilepet

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object BubbleReminderScheduler {
  private const val MEMO_TAG = "sanhao-tu-memo-reminder"
  private const val MEMO_PREFIX = "sanhao-tu-memo-"
  private const val FOCUS_WORK = "sanhao-tu-focus-reminder"
  private const val FOCUS_PRE_WORK = "sanhao-tu-focus-pre-reminder"

  fun syncMemos(context: Context, payload: String) {
    context.getSharedPreferences("sanhao_overlay_chat", Context.MODE_PRIVATE).edit()
      .putString("memos", payload).putString("memoDate", java.time.LocalDate.now().toString()).apply()
    val manager = WorkManager.getInstance(context)
    manager.cancelAllWorkByTag(MEMO_TAG)
    val now = System.currentTimeMillis()
    val reminders = runCatching { JSONArray(payload) }.getOrElse { JSONArray() }
    for (index in 0 until reminders.length()) {
      val item = reminders.optJSONObject(index) ?: continue
      if (item.optBoolean("completed", false)) continue
      val id = item.optString("id").trim()
      val title = item.optString("title").trim()
      val triggerAt = item.optLong("triggerAt", 0L)
      val message = item.optString("message").trim()
      if (id.isBlank() || title.isBlank() || triggerAt <= now) continue
      enqueue(
        context,
        MEMO_PREFIX + id,
        message.ifBlank { "“${title.take(32)}”时间到啦。" },
        triggerAt - now,
        MEMO_TAG,
        if (item.optString("kind") == "countdown") "countdown" else "todo",
        item.optString("reminderId"),
        triggerAt.toString(),
      )
    }
  }

  fun scheduleFocus(context: Context, delayMs: Long, message: String, leadMinutes: Int = 0) {
    context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE).edit().putString("focusEnd", (System.currentTimeMillis() + delayMs).toString()).apply()
    WorkManager.getInstance(context).cancelUniqueWork(FOCUS_PRE_WORK)
    val leadMs = leadMinutes.coerceAtLeast(0) * 60_000L
    if (leadMs > 0 && delayMs > leadMs) {
      enqueue(context, FOCUS_PRE_WORK, "还有 ${leadMinutes} 分钟，这一轮专注就完成啦。", delayMs - leadMs, null, "focus")
    }
    enqueue(context, FOCUS_WORK, message.ifBlank { "专注结束啦，休息一下吧。" }, delayMs, null, "focus")
  }

  fun cancelFocus(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(FOCUS_WORK)
    WorkManager.getInstance(context).cancelUniqueWork(FOCUS_PRE_WORK)
  }

  private fun enqueue(context: Context, name: String, message: String, delayMs: Long, tag: String?, kind: String, reminderId: String = "", eventId: String = "") {
    val event = eventId.ifBlank { if (kind == "focus" && name == FOCUS_WORK) context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE).getString("focusEnd", "").orEmpty() else (System.currentTimeMillis() + delayMs).toString() }
    val data = Data.Builder().putString(BubbleReminderWorker.KEY_MESSAGE, message).putString(BubbleReminderWorker.KEY_KIND, kind).putString(BubbleReminderWorker.KEY_ID, reminderId).putString(BubbleReminderWorker.KEY_EVENT, event).build()
    val builder = OneTimeWorkRequestBuilder<BubbleReminderWorker>()
      .setInputData(data)
      .setInitialDelay(delayMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
    if (tag != null) builder.addTag(tag)
    WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, builder.build())
  }
}
