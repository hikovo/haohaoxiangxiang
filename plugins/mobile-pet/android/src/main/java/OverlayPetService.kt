package com.hikovo.mobilepet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OverlayPetService : Service() {
  companion object {
    const val ACTION_START = "com.hikovo.sanhaotu.overlay.START"
    const val ACTION_STOP = "com.hikovo.sanhaotu.overlay.STOP"
    const val ACTION_SHOW_MESSAGE = "com.hikovo.sanhaotu.overlay.SHOW_MESSAGE"
    const val EXTRA_MESSAGE = "message"
    private const val CHANNEL_ID = "sanhao_tu_companion"
    private const val NOTIFICATION_ID = 112

    @Volatile
    var isRunning: Boolean = false
      private set
    var instance: OverlayPetService? = null
      private set
    var lastSnapshot: String? = null
      private set
  }

  private lateinit var windowManager: WindowManager
  private lateinit var windowParams: WindowManager.LayoutParams
  private lateinit var root: LinearLayout
  private lateinit var petView: AnimatedPetView
  private lateinit var speechBubble: TextView
  private lateinit var miniBar: LinearLayout
  private lateinit var menu: LinearLayout
  private lateinit var timerStatus: TextView
  private lateinit var menuScroll: ScrollView
  private lateinit var chatInput: EditText
  private lateinit var chatRow: LinearLayout
  private lateinit var sizeStatus: TextView
  private lateinit var chat: UnifiedPersonaEngine
  private var chatVisible = false
  private var focusRemaining = 0L
  private var working = false
  private var previousSpeechHeight = 0
  private var keyboardHeight = 0
  private val handler = Handler(Looper.getMainLooper())
  private var scale = 0.6f
  private var unitMultiplier = 1f
  private var menuDesiredWidth = 286
  private var menuVisible = false
  private var speechVisible = false
  private var speechHide: Runnable? = null
  private var returnAnimation: android.animation.ValueAnimator? = null
  private var focusEndsAt = 0L

  private val timerTick = object : Runnable {
    override fun run() {
      if (focusEndsAt <= 0L) {
        timerStatus.text = "暂未开始倒计时"
        return
      }
      val remaining = focusEndsAt - System.currentTimeMillis()
      if (remaining <= 0L) {
        focusEndsAt = 0L
        timerStatus.text = "这一轮完成啦"
        setWorkingMode(false)
        petView.playOnce(8, 6, 145L, 2)
        showSpeech("专注结束啦，辛苦了，休息一下吧。", 8_000L)
        updateNotification("专注完成，记得休息一下～")
        return
      }
      val totalSeconds = remaining / 1000L
      timerStatus.text = "专注剩余 %02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
      handler.postDelayed(this, 1_000L)
    }
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    createNotificationChannel()
    val prefs = getSharedPreferences("overlay_settings", MODE_PRIVATE)
    scale = prefs.getFloat("scale_v2", 0.6f).coerceIn(0.4f, 1.2f)
    working = prefs.getBoolean("working", false)
    chat = UnifiedPersonaEngine.get(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopOverlay()
      return START_NOT_STICKY
    }

    startAsForeground()
    if (!isRunning) showOverlay()
    intent?.getStringExtra("state")?.let { raw ->
      runCatching { org.json.JSONObject(raw) }.getOrNull()?.let { state -> root.post { applyState(state) } }
    }
    if (intent?.action == ACTION_SHOW_MESSAGE) {
      showSpeech(intent.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { "今天超话签到了嘛？" })
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    returnAnimation?.cancel()
    if (::windowParams.isInitialized) saveSnapshot()
    handler.removeCallbacksAndMessages(null)
    if (::root.isInitialized) runCatching { windowManager.removeView(root) }
    isRunning = false
    instance = null
    super.onDestroy()
  }

  private fun startAsForeground() {
    val notification = buildNotification("休闲中，长按三好兔可以打开菜单")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      @Suppress("DEPRECATION")
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "三好兔陪伴",
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "保持三好兔悬浮陪伴和倒计时运行"
      setShowBadge(false)
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun buildNotification(message: String): android.app.Notification {
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_sanhao_notification)
      .setLargeIcon(NotificationRabbit.portrait(this))
      .setContentTitle("三好兔正在陪伴你")
      .setContentText(message)
      .setOngoing(true)
      .setSilent(true)
      .addAction(
      android.R.drawable.ic_menu_close_clear_cancel,
      "结束陪伴",
      PendingIntent.getService(
        this,
        113,
        Intent(this, OverlayPetService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      ),
      )
    appPendingIntent()?.let { builder.setContentIntent(it) }
    return builder.build()
  }

  private fun appPendingIntent(): PendingIntent? {
    val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
      this,
      114,
      launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun updateNotification(message: String) {
    getSystemService(NotificationManager::class.java)
      .notify(NOTIFICATION_ID, buildNotification(message))
  }

  private fun showOverlay() {
    root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val height = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
      if (height != keyboardHeight) {
        keyboardHeight = height
        if (isRunning) root.post { updateWindowSize(); clampWindowPosition(); windowManager.updateViewLayout(root, windowParams) }
      }
      insets
    }

    speechBubble = TextView(this).apply {
      visibility = View.GONE
      gravity = Gravity.CENTER
      textSize = 15f
      setTextColor(0xff62434a.toInt())
      setPadding(dp(16), dp(11), dp(16), dp(11))
      background = roundedDrawable(0xfffffdf9.toInt(), 18f, 0xfff4c8cf.toInt())
      elevation = dp(6).toFloat()
      maxLines = 5
      maxWidth = dp(304)
      setOnClickListener { speechHide?.run() }
    }
    root.addView(speechBubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
      gravity = Gravity.CENTER_HORIZONTAL
      marginStart = dp(4)
      marginEnd = dp(4)
    })
    petView = AnimatedPetView(
      this,
      onMove = ::moveWindow,
      onScaleChanged = ::resizePet,
      onLongPress = ::toggleMenu,
      onReaction = { showSpeech(it, 3_600L) },
    )
    petView.setScale(scale)
    root.addView(petView, LinearLayout.LayoutParams(petWidth(), petHeight()))
    miniBar = buildMiniBar()
    menu = buildMenu()
    menuScroll = ScrollView(this).apply {
      visibility = View.GONE
      isFillViewport = false
      isVerticalScrollBarEnabled = true
      isScrollbarFadingEnabled = false
      clipToPadding = false
      addView(menu)
    }
    root.addView(menuScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

    windowParams = WindowManager.LayoutParams(
      max(petWidth() + dp(12), dp(202)),
      petHeight() + dp(68),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      },
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = dp(24)
      y = dp(180)
    }

    windowManager.addView(root, windowParams)
    isRunning = true
    petView.setWorking(working)
    root.post { updateWindowSize(); clampWindowPosition(); windowManager.updateViewLayout(root, windowParams) }
  }

  private fun buildMiniBar(): LinearLayout {
    val bar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(dp(5), dp(4), dp(5), dp(4))
      background = roundedDrawable(0xf7fffaf1.toInt(), 24f, 0x227f5435)
      elevation = dp(7).toFloat()
    }

    fun action(label: String, onClick: () -> Unit) = TextView(this).apply {
      text = label
      gravity = Gravity.CENTER
      textSize = 13f
      setTextColor(0xff79505b.toInt())
      setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
    }

    fun divider() = View(this).apply { setBackgroundColor(0x237f5435) }

    bar.addView(action("互动") {
      petView.playOnce(4, 5, 125L)
      showSpeech(listOf("我在这里呀。", "今天也一起加油。", "想和我聊点什么？").random(), 4_500L)
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    bar.addView(divider(), LinearLayout.LayoutParams(dp(1), dp(24)).apply { gravity = Gravity.CENTER_VERTICAL })
    bar.addView(action("消息") {
      val state = DailyReminderStore.read(this)
      val doneToday = state.answerDate == DailyReminderStore.today() && state.answer == "done"
      showSpeech(if (doneToday) "今天的消息已经看完啦。" else state.message)
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    bar.addView(divider(), LinearLayout.LayoutParams(dp(1), dp(24)).apply { gravity = Gravity.CENTER_VERTICAL })
    bar.addView(action("⌃") { toggleMenu() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .72f))
    return bar
  }

  private fun buildMenu(): LinearLayout {
    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(14), dp(12), dp(14), dp(14))
      background = roundedDrawable(0xfffff8ed.toInt(), 18f, 0xffefc5cd.toInt())
    }

    timerStatus = TextView(this)
    chatRow = LinearLayout(this).apply { visibility = View.GONE }
    chatInput = EditText(this)
    val sizeRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(4), dp(3), dp(4), 0)
    }
    sizeStatus = TextView(this).apply {
      text = "大小 ${ (scale * 100).toInt() }%"
      textSize = 13f
      gravity = Gravity.CENTER_VERTICAL
      includeFontPadding = false
      setTextColor(0xff62434a.toInt())
    }
    sizeRow.addView(sizeStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) })
    sizeRow.addView(android.widget.SeekBar(this).apply {
      max = 80
      progress = ((scale - 0.4f) * 100).toInt()
      progressTintList = android.content.res.ColorStateList.valueOf(0xffdfa0b0.toInt())
      thumbTintList = android.content.res.ColorStateList.valueOf(0xffdfa0b0.toInt())
      progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xffeadfd1.toInt())
      minimumHeight = dp(44)
      setPadding(dp(8), 0, dp(8), 0)
      setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) resizePet(0.4f + value / 100f) }
        override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {
          bar?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
      })
    }, LinearLayout.LayoutParams(0, dp(44), 1f))
    // Three compact rows; reminders keep their existing background scheduler.
    panel.addView(sizeRow)
    fun addPair(left: String, right: String, onLeft: () -> Unit, onRight: () -> Unit) {
      val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
      val first = menuButton(left, onLeft); val second = menuButton(right, onRight)
      val leftWidth = first.paint.measureText(left) + dp(28)
      val rightWidth = second.paint.measureText(right) + dp(28)
      row.gravity = Gravity.CENTER_VERTICAL
      row.addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, leftWidth))
      row.addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, rightWidth).apply { marginStart = dp(10) })
      menuDesiredWidth = max(menuDesiredWidth, (leftWidth + rightWidth + dp(38)).toInt())
      panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }
    menuDesiredWidth = max(dp(260), (sizeStatus.paint.measureText("大小 120%") + dp(170)).toInt())
    addPair("互动", "隐藏兔兔", {
      petView.playOnce(4, 5, 125L)
      showSpeech(listOf("我在这里呀～", "今天也一起加油！", "陪你歇一小会儿 (＾＾)").random(), 4_500L)
    }, { stopOverlay() })
    addPair("提醒", "今日待办", {
      val state = DailyReminderStore.read(this)
      val doneToday = state.answerDate == DailyReminderStore.today() && state.answer == "done"
      showSpeech(if (doneToday) "今天的超话签到完成啦～" else state.message)
    }, { showTodayTasks() })
    return panel
  }

  private fun showTodayTasks() {
    val prefs = getSharedPreferences("sanhao_overlay_chat", MODE_PRIVATE)
    val items = runCatching { org.json.JSONArray(prefs.getString("memos", "[]")) }.getOrElse { org.json.JSONArray() }
    val today = java.time.LocalDate.now().toString()
    val titles = (0 until items.length()).mapNotNull { index ->
      val item = items.optJSONObject(index) ?: return@mapNotNull null
      val date = item.optString("date")
      if (item.optString("kind") != "task" || (date.isNotEmpty() && date > today) || item.optBoolean("done")) return@mapNotNull null
      item.optString("title").trim().takeIf { it.isNotEmpty() }?.let { title ->
        val time = item.optString("time").trim()
        if (time.isEmpty()) title else "$time · $title"
      }
    }
    showSpeech(if (titles.isEmpty()) "今天没有待办啦，慢慢来～" else {
      val list = titles.take(3).joinToString("\n") { "· $it" }
      "今天还有这些小事喔\n$list" + if (titles.size > 3) "\n还有 ${titles.size - 3} 件，去备忘录看看吧" else ""
    }, 15_000L)
  }

  private fun menuButton(label: String, action: () -> Unit) = Button(this).apply {
    text = label
    textSize = 13f
    gravity = Gravity.CENTER
    includeFontPadding = false
    isAllCaps = false
    maxLines = 2
    minHeight = dp(44)
    minimumHeight = dp(44)
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(14), dp(10), dp(14), dp(10))
    setTextColor(0xff704d55.toInt())
    background = roundedDrawable(0xffffe8ec.toInt(), 12f, 0xfff0cbd2.toInt())
    setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); action() }
    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    params.topMargin = dp(5)
    layoutParams = params
  }

  private fun roundedDrawable(color: Int, radiusDp: Float, strokeColor: Int) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = dp(radiusDp.toInt()).toFloat()
    setStroke(dp(1), strokeColor)
  }

  private fun setWorkingMode(value: Boolean) {
    working = value
    getSharedPreferences("overlay_settings", MODE_PRIVATE).edit().putBoolean("working", value).apply()
    petView.setWorking(value)
    saveSnapshot()
  }

  fun applyState(state: org.json.JSONObject) {
    returnAnimation?.cancel()
    if (state.has("unitMultiplier")) unitMultiplier = state.optDouble("unitMultiplier", 1.0).toFloat().coerceIn(.5f, 3f)
    if (state.has("scale")) resizePet(state.optDouble("scale", scale.toDouble()).toFloat())
    if (state.has("working")) setWorkingMode(state.optBoolean("working"))
    if (state.has("centerX")) windowParams.x = (state.getDouble("centerX") - windowParams.width / 2.0).toInt()
    if (state.has("top")) windowParams.y = (state.getDouble("top") - dp(6) - previousSpeechHeight).toInt()
    clampWindowPosition(); windowManager.updateViewLayout(root, windowParams); saveSnapshot()
  }

  fun returnTo(state: org.json.JSONObject) {
    returnAnimation?.cancel()
    val startX = windowParams.x
    val startY = windowParams.y
    val bounds = screenBounds()
    val endX = (state.optDouble("centerX", bounds.width() / 2.0) - windowParams.width / 2.0).toInt().coerceIn(0, max(0, bounds.width() - windowParams.width))
    val endY = (state.optDouble("top", bounds.height() / 2.0 - petHeight() / 2.0) - dp(6) - previousSpeechHeight).toInt().coerceIn(0, max(0, bounds.height() - windowParams.height))
    returnAnimation = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 520L
      interpolator = android.view.animation.DecelerateInterpolator(1.5f)
      addUpdateListener { animator ->
        val progress = animator.animatedValue as Float
        windowParams.x = (startX + (endX - startX) * progress).toInt()
        windowParams.y = (startY + (endY - startY) * progress).toInt()
        windowManager.updateViewLayout(root, windowParams)
        saveSnapshot()
      }
      start()
    }
  }

  private fun saveSnapshot() {
    if (!::windowParams.isInitialized) return
    lastSnapshot = org.json.JSONObject().put("scale", scale.toDouble()).put("working", working)
      .put("centerX", windowParams.x + windowParams.width / 2.0)
      .put("top", windowParams.y + dp(6) + previousSpeechHeight).put("height", petHeight()).toString()
  }

  private fun toggleChat() {
    chatVisible = !chatVisible
    chatRow.visibility = if (chatVisible) View.VISIBLE else View.GONE
    for (index in 0 until menu.childCount) {
      val child = menu.getChildAt(index)
      if (child !== chatRow) child.visibility = if (chatVisible) View.GONE else View.VISIBLE
    }
    val keyboard = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
    if (chatVisible) {
      windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
      windowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
    } else {
      keyboard.hideSoftInputFromWindow(chatInput.windowToken, 0)
      chatInput.clearFocus()
      windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }
    updateWindowSize()
    clampWindowPosition()
    windowManager.updateViewLayout(root, windowParams)
    if (chatVisible) chatInput.postDelayed({
      chatInput.requestFocus()
      menuScroll.smoothScrollTo(0, 0)
      keyboard.showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT)
    }, 160L)
  }

  private var offlineChatBusy = false
  private fun sendOverlayChat() {
    val input = chatInput.text.toString().trim()
    if (input.isEmpty() || offlineChatBusy) return
    chatInput.setText("")
    val messageId = "overlay-${System.currentTimeMillis()}"
    offlineChatBusy = true
    showSpeech("想一下……", 120_000L)
    UnifiedPersonaEngine.execute {
      val result = runCatching { chat.reply(messageId, input, "overlay").reply }
      handler.post {
        offlineChatBusy = false
        if (!isRunning) return@post
        showSpeech(result.getOrElse { "刚刚没想好，再试一下吧。" },15_000L)
      }
    }
  }

  private fun toggleMenu() {
    if (menuVisible) hideMenu() else showMenu()
  }

  private fun showMenu() {
    menuVisible = true
    menuScroll.visibility = View.VISIBLE
    updateWindowSize()
    clampWindowPosition()
    windowManager.updateViewLayout(root, windowParams)
  }

  private fun hideMenu() {
    menuVisible = false
    if (chatVisible) toggleChat()
    menuScroll.visibility = View.GONE
    updateWindowSize()
    clampWindowPosition()
    windowManager.updateViewLayout(root, windowParams)
  }

  private fun moveWindow(dx: Float, dy: Float) {
    returnAnimation?.cancel()
    windowParams.x += dx.toInt()
    windowParams.y += dy.toInt()
    clampWindowPosition()
    windowManager.updateViewLayout(root, windowParams)
    saveSnapshot()
  }

  private fun resizePet(newScale: Float) {
    val feetY = windowParams.y + dp(6) + previousSpeechHeight + petHeight()
    scale = min(1.2f, max(0.4f, newScale))
    getSharedPreferences("overlay_settings", MODE_PRIVATE).edit().putFloat("scale_v2", scale).apply()
    sizeStatus.text = "大小 ${(scale * 100).toInt()}%"
    petView.setScale(scale)
    petView.layoutParams = (petView.layoutParams as LinearLayout.LayoutParams).apply {
      width = petWidth()
      height = petHeight()
    }
    updateWindowSize()
    windowParams.y = feetY - dp(6) - previousSpeechHeight - petHeight()
    clampWindowPosition()
    windowManager.updateViewLayout(root, windowParams)
    saveSnapshot()
  }

  private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    windowManager.currentWindowMetrics.bounds
  } else {
    @Suppress("DEPRECATION")
    Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
  }

  private fun updateWindowSize() {
    val bounds = screenBounds()
    val oldWidth = windowParams.width
    windowParams.width = min(when {
      menuVisible -> menuDesiredWidth
      speechVisible -> dp(304)
      else -> petWidth() + dp(12)
    }, bounds.width() - dp(16))
    windowParams.x += (oldWidth - windowParams.width) / 2
    val innerWidth = windowParams.width - dp(12)
    petView.layoutParams = (petView.layoutParams as LinearLayout.LayoutParams).apply { width = petWidth(); height = petHeight() }
    speechBubble.measure(View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val speechHeight = if (speechVisible) speechBubble.measuredHeight + dp(4) else 0
    windowParams.y += previousSpeechHeight - speechHeight
    previousSpeechHeight = speechHeight
    val availableHeight = if (chatVisible) {
      if (keyboardHeight > 0) bounds.height() - keyboardHeight - dp(32) else bounds.height() / 2
    } else bounds.height() - dp(48)
    menu.measure(View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val menuHeight = if (menuVisible) min(menu.measuredHeight, max(dp(72), availableHeight - petHeight() - speechHeight - dp(24))) else 0
    menuScroll.layoutParams.height = menuHeight
    windowParams.height = petHeight() + dp(12) + speechHeight + if (menuVisible) menuHeight + dp(8) else 0
    if (chatVisible) windowParams.y = dp(24)
  }

  private fun showSpeech(message: String, duration: Long = 6_000L) {
    speechHide?.let(handler::removeCallbacks)
    speechBubble.text = message
    speechBubble.visibility = View.VISIBLE
    speechVisible = true
    updateWindowSize()
    clampWindowPosition()
    windowManager.updateViewLayout(root, windowParams)

    speechHide = Runnable {
      speechBubble.visibility = View.GONE
      speechVisible = false
      updateWindowSize()
      clampWindowPosition()
      windowManager.updateViewLayout(root, windowParams)
    }.also { handler.postDelayed(it, duration) }
  }

  private fun clampWindowPosition() {
    val bounds = screenBounds()
    windowParams.x = min(max(0, windowParams.x), max(0, bounds.width() - windowParams.width))
    windowParams.y = min(max(0, windowParams.y), max(0, bounds.height() - windowParams.height))
    saveSnapshot()
  }

  private fun startFocus(minutes: Int) {
    BubbleReminderScheduler.scheduleFocus(this, minutes * 60_000L, "专注结束啦，辛苦了，休息一下吧。", if (minutes > 5) 5 else 1)
    focusEndsAt = System.currentTimeMillis() + minutes * 60_000L
    handler.removeCallbacks(timerTick)
    handler.post(timerTick)
    setWorkingMode(true)
    updateNotification("专注 $minutes 分钟进行中")
    hideMenu()
  }

  private fun openApp() {
    packageManager.getLaunchIntentForPackage(packageName)?.also {
      it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      startActivity(it)
    }
    hideMenu()
  }

  private fun stopOverlay() {
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun petWidth() = (petHeight() * 192f / 208f).toInt()
  private fun petHeight() = if (chatVisible) min(dp((125f * 208f / 192f * scale * unitMultiplier).toInt()), dp(96)) else dp((125f * 208f / 192f * scale * unitMultiplier).toInt())
  private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private class AnimatedPetView(
  context: Context,
  private val onMove: (Float, Float) -> Unit,
  private val onScaleChanged: (Float) -> Unit,
  private val onLongPress: () -> Unit,
  private val onReaction: (String) -> Unit,
) : View(context) {
  companion object {
    private const val CELL_WIDTH = 192
    private const val CELL_HEIGHT = 208
    private const val LONG_PRESS_MS = 520L
    private const val TAP_SETTLE_MS = 320L
  }

  private val bitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.spritesheet)
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val handler = Handler(Looper.getMainLooper())
  private val tapTimes = mutableListOf<Long>()
  private var row = 0
  private var frame = 0
  private var frameCount = 1
  private var frameMs = 115L
  private var loopsRemaining = 0
  private var looping = false
  private var working = false
  private var downRawX = 0f
  private var downRawY = 0f
  private var lastRawX = 0f
  private var lastRawY = 0f
  private var dragging = false
  private var longPressed = false
  private var currentScale = 0.6f

  private val longPressRunnable: Runnable = Runnable {
    if (!dragging && !scaleDetector.isInProgress) {
      handler.removeCallbacks(tapDecision)
      tapTimes.clear()
      longPressed = true
      performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
      onLongPress()
    }
  }

  private val tapDecision: Runnable = Runnable {
    val count = tapTimes.size
    tapTimes.clear()
    if (count >= 2) {
      onReaction("wink～")
      playOnce(4, 5, 125L)
    } else if (count == 1) {
      onReaction("我在呢～")
      playOnce(3, 4, 105L)
    }
  }

  private val animationTick = object : Runnable {
    override fun run() {
      frame += 1
      if (frame >= frameCount) {
        frame = 0
        if (!looping) {
          loopsRemaining -= 1
          if (loopsRemaining <= 0) {
            restoreBackground()
            return
          }
        }
      }
      invalidate()
      handler.postDelayed(this, frameMs)
    }
  }

  private val idleBlink = Runnable {
    if (!working) playOnce(0, 6, 115L)
  }

  private val scaleDetector: ScaleGestureDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        handler.removeCallbacks(longPressRunnable)
        dragging = false
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        currentScale = min(1.2f, max(0.4f, currentScale * detector.scaleFactor))
        onScaleChanged(currentScale)
        return true
      }
    },
  )

  init {
    isClickable = true
    setBackgroundColor(Color.TRANSPARENT)
    restoreBackground()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val source = Rect(
      frame * CELL_WIDTH,
      row * CELL_HEIGHT,
      (frame + 1) * CELL_WIDTH,
      (row + 1) * CELL_HEIGHT,
    )
    canvas.drawBitmap(bitmap, source, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        parent.requestDisallowInterceptTouchEvent(true)
        downRawX = event.rawX
        downRawY = event.rawY
        lastRawX = event.rawX
        lastRawY = event.rawY
        dragging = false
        longPressed = false
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
        return true
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        handler.removeCallbacks(longPressRunnable)
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (scaleDetector.isInProgress) return true
        val total = hypot(event.rawX - downRawX, event.rawY - downRawY)
        if (!dragging && total >= 10f * resources.displayMetrics.density) {
          dragging = true
          handler.removeCallbacks(longPressRunnable)
          handler.removeCallbacks(tapDecision)
          tapTimes.clear()
        }
        if (dragging) {
          val dx = event.rawX - lastRawX
          val dy = event.rawY - lastRawY
          val targetRow = if (dx < 0) 2 else 1
          if (!looping || row != targetRow) startLoop(targetRow, 8, 105L)
          onMove(dx, dy)
          lastRawX = event.rawX
          lastRawY = event.rawY
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        handler.removeCallbacks(longPressRunnable)
        if (dragging) restoreBackground()
        else if (!longPressed && !scaleDetector.isInProgress) registerTap()
        dragging = false
        performClick()
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        handler.removeCallbacks(longPressRunnable)
        dragging = false
        restoreBackground()
        return true
      }
    }
    return true
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  fun setScale(value: Float) {
    currentScale = value
  }

  fun setWorking(value: Boolean) {
    working = value
    if (working) startLoop(7, 6, 115L)
    else {
      playOnce(8, 6, 145L)
      handler.postDelayed(idleBlink, 4_000L)
    }
  }

  fun playOnce(targetRow: Int, frames: Int, interval: Long, loops: Int = 1) {
    handler.removeCallbacks(animationTick)
    handler.removeCallbacks(idleBlink)
    row = targetRow
    frame = 0
    frameCount = frames
    frameMs = interval
    loopsRemaining = loops
    looping = false
    invalidate()
    handler.postDelayed(animationTick, frameMs)
  }

  private fun startLoop(targetRow: Int, frames: Int, interval: Long) {
    handler.removeCallbacks(animationTick)
    handler.removeCallbacks(idleBlink)
    row = targetRow
    frame = 0
    frameCount = frames
    frameMs = interval
    looping = true
    invalidate()
    handler.postDelayed(animationTick, frameMs)
  }

  private fun restoreBackground() {
    handler.removeCallbacks(animationTick)
    if (working) {
      startLoop(7, 6, 115L)
    } else {
      row = 0
      frame = 0
      frameCount = 1
      looping = false
      invalidate()
      handler.removeCallbacks(idleBlink)
      handler.postDelayed(idleBlink, 3_400L)
    }
  }

  private fun registerTap() {
    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    val now = System.currentTimeMillis()
    tapTimes.removeAll { now - it > 950L }
    tapTimes.add(now)
    handler.removeCallbacks(tapDecision)
    if (tapTimes.size >= 4) {
      tapTimes.clear()
      onReaction("晕晕啦……让我缓一下。")
      playOnce(5, 8, 105L, 2)
    } else {
      handler.postDelayed(tapDecision, TAP_SETTLE_MS)
    }
  }
}
