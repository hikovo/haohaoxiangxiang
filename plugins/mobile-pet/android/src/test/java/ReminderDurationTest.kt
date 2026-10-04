package com.hikovo.mobilepet

import org.junit.Assert.*
import org.junit.Test

class ReminderDurationTest {
  @Test fun builtinTonesLastFifteenSeconds() {
    for (tone in listOf("gentle", "chime", "three")) {
      val wave = ReminderStyle.builtinWave(tone)
      assertEquals(44 + 16000 * 15 * 2, wave.size)
      assertTrue(ReminderStyle.validWave(wave))
    }
  }

  @Test fun durationDoesNotChangeOriginalAudio() {
    val original = ReminderStyle.builtinWave("gentle")
    val before = original.copyOf()
    for (seconds in 1..15) {
      val playback = ReminderStyle.boundedWave(original, seconds)
      assertEquals(44 + seconds * 16000 * 2, playback.size)
      assertTrue(ReminderStyle.validWave(playback))
    }
    assertArrayEquals(before, original)
  }

  @Test fun vibrationRhythmsAreBoundedAndFinite() {
    for (rhythm in listOf("single", "double", "slow")) for (seconds in 1..15) {
      val timings = ReminderStyle.vibrationTimings(rhythm, seconds)
      assertEquals(0L, timings.first())
      assertEquals(seconds * 1000L, timings.sum())
      assertTrue(timings.drop(1).all { it > 0L })
      assertTrue(timings.size > 2)
    }
  }
}
