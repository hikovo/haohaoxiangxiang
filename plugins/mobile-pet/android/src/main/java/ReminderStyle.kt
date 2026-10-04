package com.hikovo.mobilepet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.AudioManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

data class ReminderOptions(
  val popup: Boolean = true, val vibration: Boolean = true, val sound: Boolean = true,
  val rhythm: String = "double", val tone: String = "gentle",
  val soundSeconds: Int = 15, val vibrationSeconds: Int = 15,
)

/** Scheduling only stores the category; workers read the latest choice when they fire. */
object ReminderStyle {
  val kinds = setOf("daily", "todo", "countdown", "focus")
  private val rhythms = mapOf("single" to longArrayOf(0, 110), "double" to longArrayOf(0, 80, 150, 80), "slow" to longArrayOf(0, 120, 240, 120, 240, 120))
  private val tones = setOf("gentle", "chime", "three")
  private var player: MediaPlayer? = null

  private fun scope(kind: String, id: String) = if (id.isBlank()) kind else "$kind/$id"
  fun read(context: Context, kind: String, id: String = ""): ReminderOptions {
    val preferences = context.getSharedPreferences("reminder_styles", Context.MODE_PRIVATE)
    val data = runCatching { JSONObject(preferences.getString(scope(kind, id), preferences.getString(kind, "{}"))!!) }.getOrDefault(JSONObject())
    return parse(data)
  }
  private fun parse(data: JSONObject) = ReminderOptions(data.optBoolean("popup", true), data.optBoolean("vibration", true), data.optBoolean("sound", true), data.optString("rhythm", "double").takeIf { rhythms.containsKey(it) } ?: "double", data.optString("tone", "gentle"), data.optInt("soundSeconds", 15).coerceIn(1, 15), data.optInt("vibrationSeconds", 15).coerceIn(1, 15))

  fun handle(context: Context, data: JSONObject): JSONObject {
    val kind = data.optString("kind")
    require(kind in kinds) { "Unknown reminder kind" }
    val reminderId = data.optString("reminderId").takeIf { it != "null" }.orEmpty()
    require(reminderId.length <= 100 && !reminderId.contains('/'))
    val action = data.optString("action", "save")
    require(action in setOf("save", "preview", "stop", "fire"))
    if (action == "stop") { player?.release(); player = null; context.getSystemService(Vibrator::class.java).cancel(); return JSONObject().put("status", "stopped") }
    if (action == "fire") {
      val event = if (kind == "focus") context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE).getString("focusEnd", "").orEmpty() else data.optString("eventId")
      require(event.isNotBlank() && event.length <= 100)
      if (!claimDelivery(context, kind, reminderId, event)) return JSONObject().put("status", "alreadyDelivered")
      val options = read(context, kind, reminderId)
      try { playFeedback(context, options) }
      catch (error: Exception) {
        context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE).edit().remove("last-${scope(kind, reminderId)}").commit()
        throw error
      }
      return JSONObject().put("status", "delivered").put("snapshot", diagnostics(context, options).toString())
    }
    var tone = data.optString("tone", "gentle")
    val audio = data.optString("audio")
    if (audio.isNotEmpty()) {
      require(action == "save" && audio.length <= 1_500_000)
      val bytes = android.util.Base64.decode(audio, android.util.Base64.DEFAULT)
      require(validWave(bytes)) { "Unsupported audio clip" }
      tone = "custom-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
      audioFile(context, tone).writeBytes(bytes)
    }
    require(tone in tones || (tone.matches(Regex("custom-[a-f0-9]{64}")) && audioFile(context, tone).isFile)) { "Missing ringtone" }
    data.put("tone", tone)
    val options = parse(data)
    if (action == "preview") {
      playFeedback(context, options)
    } else {
      // Never keep base64 or import paths in preferences.
      val saved = JSONObject().put("popup", options.popup).put("vibration", options.vibration).put("sound", options.sound).put("rhythm", options.rhythm).put("tone", options.tone).put("soundSeconds", options.soundSeconds).put("vibrationSeconds", options.vibrationSeconds)
      context.getSharedPreferences("reminder_styles", Context.MODE_PRIVATE).edit().putString(scope(kind, reminderId), saved.toString()).apply()
    }
    return JSONObject().put("status", if (action == "preview") "previewed" else "saved").put("snapshot", diagnostics(context, options).put("tone", tone).toString())
  }

  @Synchronized
  fun claimDelivery(context: Context, kind: String, id: String, event: String): Boolean {
    val prefs = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)
    val key = "last-${scope(kind, id)}"
    if (prefs.getString(key, "") == event) return false
    prefs.edit().putString(key, event).commit()
    return true
  }

  private fun diagnostics(context: Context, options: ReminderOptions): JSONObject {
    val audio = context.getSystemService(AudioManager::class.java)
    val manager = context.getSystemService(NotificationManager::class.java)
    val warnings = mutableListOf<String>()
    if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) warnings.add("手机的通知还没开启，离开 App 后可能收不到提醒")
    if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) warnings.add("手机正在勿扰，铃声和震动可能被静音")
    if (options.sound && audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) warnings.add("手机的通知音量是零，调高后再试听吧")
    if (options.vibration && !context.getSystemService(Vibrator::class.java).hasVibrator()) warnings.add("这台设备没有震动功能")
    return JSONObject().put("warning", warnings.joinToString("；"))
  }

  private fun playFeedback(context: Context, options: ReminderOptions) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
    player?.release(); player = null
    // In-app feedback reads the same saved options as background notifications.
    if (options.vibration) {
      val vibrator = context.getSystemService(Vibrator::class.java)
      if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(vibrationTimings(options.rhythm, options.vibrationSeconds), -1), audioAttributes())
    }
    if (options.sound && context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0) {
      val next = MediaPlayer()
      try {
        next.setAudioAttributes(audioAttributes())
        next.setDataSource(playbackFile(context, options).absolutePath)
        next.setOnCompletionListener { it.release(); if (player === it) player = null }
        next.setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; true }
        next.prepare(); player = next; next.start()
      } catch (error: Exception) { next.release(); if (player === next) player = null; throw error }
    }
  }

  private fun audioFileForPlayback(context: Context, tone: String): File {
    val file = audioFile(context, tone)
    if (tone in tones && (!file.isFile || file.length() != 44L + 16000L * 15 * 2)) file.writeBytes(builtinWave(tone))
    require(file.isFile) { "Missing ringtone" }
    return file
  }

  internal fun vibrationTimings(rhythm: String, seconds: Int): LongArray {
    val cycle = rhythms.getValue(rhythm).drop(1) + 700L
    val timings = mutableListOf(0L)
    var remaining = seconds.coerceIn(1, 15) * 1000L
    while (remaining > 0) for (duration in cycle) {
      if (remaining == 0L) break
      val part = minOf(duration, remaining)
      timings.add(part); remaining -= part
    }
    return timings.toLongArray()
  }

  private fun playbackFile(context: Context, options: ReminderOptions): File {
    val original = audioFileForPlayback(context, options.tone)
    val output = audioFile(context, "${options.tone}-${options.soundSeconds}s-v2")
    if (!output.isFile) output.writeBytes(boundedWave(original.readBytes(), options.soundSeconds))
    return output
  }

  internal fun boundedWave(bytes: ByteArray, seconds: Int): ByteArray {
    val length = minOf(bytes.size, 44 + seconds.coerceIn(1, 15) * 16000 * 2)
    val result = bytes.copyOf(length)
    val header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
    header.putInt(4, length - 8); header.putInt(40, length - 44)
    return result
  }

  fun channel(context: Context, kind: String, reminderId: String = ""): String {
    val style = read(context, kind, reminderId)
    val signature = "v2-${style.popup}-${style.vibration}-${style.sound}-${style.rhythm}-${style.tone}-${style.soundSeconds}-${style.vibrationSeconds}"
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    val id = "hhxx-reminder-$kind-$fingerprint"
    val label = mapOf("daily" to "每日询问", "todo" to "待办事项", "countdown" to "倒数日", "focus" to "专注时间")[kind] ?: "小提醒"
    val importance = if (style.popup) NotificationManager.IMPORTANCE_HIGH else if (style.sound || style.vibration) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id, label, importance).apply {
      description = "兔兔按你选好的方式来提醒"
      enableVibration(style.vibration)
      if (style.vibration) vibrationPattern = vibrationTimings(style.rhythm, style.vibrationSeconds)
      setSound(if (style.sound) toneUri(context, style) else null, audioAttributes())
    })
    return id
  }
  private fun audioAttributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
  private fun audioFile(context: Context, tone: String): File {
    val folder = File(context.filesDir, "reminder-tones").apply { mkdirs() }
    return File(folder, "$tone.wav")
  }
  private fun toneUri(context: Context, style: ReminderOptions): Uri {
    val file = playbackFile(context, style)
    // The system notification service must retain access after the App exits.
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.reminder.audio", file)
    context.grantUriPermission("com.android.systemui", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.grantUriPermission("android", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return uri
  }
  internal fun validWave(bytes: ByteArray): Boolean {
    if (bytes.size !in 46..480044) return false
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE" && String(bytes, 12, 4) == "fmt " && b.getInt(16) == 16 && b.getShort(20).toInt() == 1 && b.getShort(22).toInt() == 1 && b.getInt(24) == 16000 && b.getShort(34).toInt() == 16 && String(bytes, 36, 4) == "data" && b.getInt(40) == bytes.size - 44 && b.getInt(4) == bytes.size - 8
  }
  internal fun builtinWave(tone: String): ByteArray {
    val rate = 16000; val count = rate * 15
    val b = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(count * 2)
    val notes = when (tone) { "chime" -> listOf(0.0 to 880.0, 0.28 to 1320.0); "three" -> listOf(0.0 to 660.0, 0.32 to 784.0, 0.64 to 988.0); else -> listOf(0.0 to 784.0) }
    for (i in 0 until count) {
      val t = (i.toDouble() / rate) % 2.0
      val value = notes.sumOf { (start, frequency) -> val age = t - start; if (age in 0.0..0.8) sin(2 * PI * frequency * age) * kotlin.math.exp(-age * 7) * (age / 0.012).coerceAtMost(1.0) * 0.22 else 0.0 }
      b.putShort((value.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
    }
    return b.array()
  }
}
