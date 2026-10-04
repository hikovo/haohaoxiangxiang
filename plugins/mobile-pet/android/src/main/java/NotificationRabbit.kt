package com.hikovo.mobilepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** The original supplied portrait is shared by reminders and floating companionship. */
internal object NotificationRabbit {
  @Volatile private var portrait: Bitmap? = null

  fun portrait(context: Context): Bitmap = portrait ?: synchronized(this) {
    portrait ?: BitmapFactory.decodeResource(
      context.resources,
      R.drawable.sanhao_notification_portrait,
      BitmapFactory.Options().apply { inSampleSize = 4 },
    ).also { portrait = it }
  }
}
