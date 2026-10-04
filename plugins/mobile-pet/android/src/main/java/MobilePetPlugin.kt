package com.hikovo.mobilepet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@InvokeArg
class ExternalUrlArgs {
  lateinit var url: String
}

@InvokeArg
class OverlaySyncArgs { var payload: String = "" }

@InvokeArg
class DailyReminderSettingsArgs {
  var enabled: Boolean = true
  lateinit var message: String
  var hour: Int = 20
  var minute: Int = 0
}

@InvokeArg
class DailyReminderAnswerArgs {
  lateinit var answer: String
}

@InvokeArg
class MemoReminderSyncArgs {
  var payload: String = "[]"
}

@InvokeArg
class FocusReminderArgs {
  var delayMs: Long = 0L
  var message: String = "专注结束啦，休息一下吧。"
  var leadMinutes: Int = 0
}

@InvokeArg
class UnifiedChatArgs {
  lateinit var messageId: String
  lateinit var message: String
  var entryPoint: String = "main"
}

@InvokeArg
class UnifiedRetractArgs {
  var messageIds: Array<String> = emptyArray()
}

@InvokeArg
class UnifiedImportLegacyArgs {
  var historyJson: String = "[]"
  var memoryJson: String = "{}"
}

@InvokeArg
class UnifiedManualMemoryArgs {
  var memoryJson: String = "{}"
}

@TauriPlugin
class MobilePetPlugin(private val activity: Activity) : Plugin(activity) {
  init { RetiredModelCleanup.run(activity.applicationContext) }
  private var lastHapticAt = -80L

  @Command
  fun openExternal(invoke: Invoke) {
    val args = invoke.parseArgs(ExternalUrlArgs::class.java)
    val uri = Uri.parse(args.url)
    if (uri.scheme != "https" || uri.host !in setOf("weibo.com", "www.weibo.com", "pan.baidu.com") || uri.userInfo != null) {
      invoke.reject("这个入口暂时打不开")
      return
    }
    activity.runOnUiThread {
      try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        resolve(invoke, "opened")
      } catch (_: Exception) { invoke.reject("手机还没找到能打开这个入口的应用") }
    }
  }

  @Command
  fun hapticTap(invoke: Invoke) {
    activity.runOnUiThread {
      val now = android.os.SystemClock.elapsedRealtime()
      if (now - lastHapticAt < 80L) {
        resolve(invoke, "throttled")
        return@runOnUiThread
      }
      lastHapticAt = now
      // Respect the phone's touch-feedback preference; no vibration permission needed.
      val performed = activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
      resolve(invoke, if (performed) "performed" else "silent")
    }
  }
  private fun resolve(invoke: Invoke, status: String) {
    invoke.resolve(JSObject().apply { put("status", status) })
  }

  private fun overlayPayload(invoke: Invoke): org.json.JSONObject {
    val args = invoke.parseArgs(OverlaySyncArgs::class.java)
    return runCatching { org.json.JSONObject(args.payload) }.getOrElse { org.json.JSONObject() }
  }

  private fun findWebView(view: android.view.View): android.webkit.WebView? {
    if (view is android.webkit.WebView) return view
    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
    return null
  }

  private fun overlayCoordinates(payload: org.json.JSONObject, toScreen: Boolean): org.json.JSONObject {
    val web = findWebView(activity.window.decorView) ?: return payload
    val origin = IntArray(2); web.getLocationOnScreen(origin)
    val ratio = web.width / payload.optDouble("viewportWidth", (web.width / activity.resources.displayMetrics.density).toDouble()).coerceAtLeast(1.0)
    val result = org.json.JSONObject(payload.toString())
    if (toScreen) {
      if (payload.has("centerX")) result.put("centerX", origin[0] + payload.getDouble("centerX") * ratio)
      if (payload.has("top")) result.put("top", origin[1] + payload.getDouble("top") * ratio)
      result.put("unitMultiplier", ratio / activity.resources.displayMetrics.density)
    } else {
      if (payload.has("centerX")) result.put("centerX", (payload.getDouble("centerX") - origin[0]) / ratio)
      if (payload.has("top")) result.put("top", (payload.getDouble("top") - origin[1]) / ratio)
      if (payload.has("height")) result.put("height", payload.getDouble("height") / ratio)
    }
    return result
  }

  private fun resolveOverlay(invoke: Invoke, payload: org.json.JSONObject) {
    val snapshot = OverlayPetService.lastSnapshot?.let {
      val state = org.json.JSONObject(it)
      if (payload.optDouble("viewportWidth", 0.0) > 0.0) state.put("viewportWidth", payload.getDouble("viewportWidth"))
      overlayCoordinates(state, false).toString()
    }
    invoke.resolve(JSObject().apply {
      put("status", if (OverlayPetService.isRunning) "active" else "inactive")
      if (snapshot != null) put("snapshot", snapshot)
    })
  }

  private fun notificationPermission(): String = if (
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  ) "granted" else "denied"

  private fun resolveDaily(invoke: Invoke) {
    val state = DailyReminderStore.read(activity)
    invoke.resolve(JSObject().apply {
      put("enabled", state.enabled)
      put("message", state.message)
      put("hour", state.hour)
      put("minute", state.minute)
      put("lastShownDate", state.lastShownDate)
      put("answerDate", state.answerDate)
      put("answer", state.answer)
      put("notificationPermission", notificationPermission())
    })
  }

  @Command
  fun startOverlay(invoke: Invoke) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${activity.packageName}"),
      )
      activity.startActivity(intent)
      resolve(invoke, "permissionRequired")
      return
    }

    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        112,
      )
    }

    val payload = overlayPayload(invoke)
    activity.runOnUiThread {
      ContextCompat.startForegroundService(activity,
        Intent(activity, OverlayPetService::class.java).setAction(OverlayPetService.ACTION_START)
          .putExtra("state", overlayCoordinates(payload, true).toString()))
      resolve(invoke, "active")
    }
  }

  @Command
  fun stopOverlay(invoke: Invoke) {
    activity.startService(
      Intent(activity, OverlayPetService::class.java).setAction(OverlayPetService.ACTION_STOP),
    )
    resolve(invoke, "inactive")
  }

  @Command
  fun overlayStatus(invoke: Invoke) {
    val payload = overlayPayload(invoke)
    activity.runOnUiThread {
      when (payload.optString("operation")) {
        "update" -> OverlayPetService.instance?.applyState(overlayCoordinates(payload, true))
        "return" -> OverlayPetService.instance?.returnTo(overlayCoordinates(payload, true))
      }
      resolveOverlay(invoke, payload)
    }
  }

  @Command
  fun dailyReminderState(invoke: Invoke) {
    // The first app launch also arms the default reminder. WorkManager keeps
    // it running on later days even when the app itself is not opened.
    DailyReminderScheduler.sync(activity.applicationContext)
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 212)
    }
    resolveDaily(invoke)
  }

  @Command
  fun saveDailyReminder(invoke: Invoke) {
    val args = invoke.parseArgs(DailyReminderSettingsArgs::class.java)
    DailyReminderStore.saveSettings(activity, args.enabled, args.message, args.hour, args.minute)
    DailyReminderScheduler.sync(activity.applicationContext)
    if (
      args.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 212)
    }
    resolveDaily(invoke)
  }

  @Command
  fun markDailyPromptShown(invoke: Invoke) {
    DailyReminderStore.markShown(activity)
    resolveDaily(invoke)
  }

  @Command
  fun answerDailyReminder(invoke: Invoke) {
    val args = invoke.parseArgs(DailyReminderAnswerArgs::class.java)
    DailyReminderStore.answer(activity, args.answer)
    if (args.answer == "done") {
      androidx.core.app.NotificationManagerCompat.from(activity).cancel(DailyReminderWorker.NOTIFICATION_ID)
    } else {
      DailyReminderScheduler.snooze(activity.applicationContext)
    }
    resolveDaily(invoke)
  }

  @Command
  fun reminderStyle(invoke: Invoke) {
    val args = invoke.parseArgs(MemoReminderSyncArgs::class.java)
    activity.runOnUiThread {
      val data = org.json.JSONObject(args.payload)
      if (data.optString("action") == "settings") {
        val target = data.optString("target")
        if (target !in setOf("sound", "notifications")) {
          invoke.reject("未知的设置页面")
          return@runOnUiThread
        }
        val primary = if (target == "sound") Intent(Settings.ACTION_SOUND_SETTINGS)
          else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
        val opened = listOf(primary, fallback, Intent(Settings.ACTION_SETTINGS)).firstOrNull { intent ->
          runCatching { activity.startActivity(intent) }.isSuccess
        }
        if (opened == null) invoke.reject("设置页面未能打开")
        else invoke.resolve(JSObject().apply { put("status", "opened") })
        return@runOnUiThread
      }
      if (data.optString("action", "save") == "save" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 213)
      }
      runCatching { ReminderStyle.handle(activity.applicationContext, data) }
        .onSuccess { invoke.resolve(JSObject.fromJSONObject(it)) }
        .onFailure { invoke.reject("提醒方式还没保存好，请重试") }
    }
  }

  @Command
  fun syncMemoReminders(invoke: Invoke) {
    val args = invoke.parseArgs(MemoReminderSyncArgs::class.java)
    BubbleReminderScheduler.syncMemos(activity.applicationContext, args.payload)
    resolve(invoke, "synced")
  }

  @Command
  fun scheduleFocusReminder(invoke: Invoke) {
    val args = invoke.parseArgs(FocusReminderArgs::class.java)
    BubbleReminderScheduler.scheduleFocus(activity.applicationContext, args.delayMs, args.message, args.leadMinutes)
    resolve(invoke, "scheduled")
  }

  @Command
  fun cancelFocusReminder(invoke: Invoke) {
    BubbleReminderScheduler.cancelFocus(activity.applicationContext)
    resolve(invoke, "cancelled")
  }

  @Command
  fun unifiedChat(invoke: Invoke) {
    val args = invoke.parseArgs(UnifiedChatArgs::class.java)
    UnifiedPersonaEngine.execute {
      runCatching { UnifiedPersonaEngine.get(activity.applicationContext).reply(args.messageId, args.message, args.entryPoint) }
        .onSuccess { invoke.resolve(JSObject.fromJSONObject(it.toJson())) }
        .onFailure { invoke.reject("刚刚没想好，再试一下吧。") }
    }
  }

  @Command
  fun unifiedRetractMessages(invoke: Invoke) {
    val args = invoke.parseArgs(UnifiedRetractArgs::class.java)
    UnifiedPersonaEngine.execute {
    val change = UnifiedPersonaEngine.get(activity.applicationContext).retract(args.messageIds.toSet())
    invoke.resolve(JSObject().apply {
      put("status", "retracted")
      put("removed", change.removed.size)
      put("updated", change.updated.size)
    })
    }
  }

  @Command
  fun unifiedClearConversation(invoke: Invoke) {
    UnifiedPersonaEngine.execute {
    UnifiedPersonaEngine.get(activity.applicationContext).clearConversation()
    resolve(invoke, "cleared")
    }
  }

  @Command
  fun unifiedMemoryState(invoke: Invoke) {
    UnifiedPersonaEngine.execute {
    invoke.resolve(JSObject.fromJSONObject(UnifiedPersonaEngine.get(activity.applicationContext).snapshot().memoryJson()))
    }
  }

  @Command
  fun unifiedSetManualMemory(invoke: Invoke) {
    val args = invoke.parseArgs(UnifiedManualMemoryArgs::class.java)
    UnifiedPersonaEngine.execute {
    val manual = runCatching {
      val root = org.json.JSONObject(args.memoryJson)
      mapOf(
        "name" to listOf(root.optString("name")),
        "preferredAddress" to listOf(root.optString("preferredAddress")),
        "userRole" to listOf(root.optString("userRole")),
        "petRole" to listOf(root.optString("petRole")),
        "likes" to root.optString("likes").split(Regex("[、，,]")).map(String::trim).filter(String::isNotBlank),
        "currentTopic" to listOf(root.optString("currentTopic")),
        "correction" to listOf(root.optString("correction")),
      )
    }.getOrElse { emptyMap() }
    UnifiedPersonaEngine.get(activity.applicationContext).setManualMemory(manual)
    resolve(invoke, "saved")
    }
  }

  @Command
  fun unifiedImportLegacy(invoke: Invoke) {
    val args = invoke.parseArgs(UnifiedImportLegacyArgs::class.java)
    UnifiedPersonaEngine.execute {
    val history = runCatching {
      val array = org.json.JSONArray(args.historyJson)
      (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        PersonaMessage(
          item.getString("id"),
          if (item.getString("role") == "pet") "assistant" else "user",
          item.getString("message"),
          "legacy-main",
          item.optLong("createdAt", System.currentTimeMillis() + index),
        )
      }
    }.getOrElse { emptyList() }
    val legacy = runCatching {
      val root = org.json.JSONObject(args.memoryJson)
      mapOf(
        "name" to listOf(root.optString("name")),
        "preferredAddress" to listOf(root.optString("preferredAddress")),
        "userRole" to listOf(root.optString("userRole")),
        "petRole" to listOf(root.optString("petRole")),
        "likes" to (root.optJSONArray("likes")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()),
        "currentTopic" to listOf(root.optString("currentTopic")),
      )
    }.getOrElse { emptyMap() }
    UnifiedPersonaEngine.get(activity.applicationContext).importLegacy(history, legacy)
    resolve(invoke, "imported")
    }
  }
}
