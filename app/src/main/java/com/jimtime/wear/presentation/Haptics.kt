package com.jimtime.wear.presentation

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/// Vibrazione one-shot breve — tap acqua (30 ms), tick countdown (30 ms),
/// via countdown (80 ms). Nessuna VibratorManager: minSdk 30 già supporta
/// getSystemService(Vibrator::class.java) direttamente.
fun Context.vibrate(ms: Long) {
    val vibrator = getSystemService(Vibrator::class.java) ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}
