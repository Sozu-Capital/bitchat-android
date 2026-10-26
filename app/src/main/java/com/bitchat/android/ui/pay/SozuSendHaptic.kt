package com.bitchat.android.ui.pay

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rising ticks while a cheque is leaving the phone, then a short bloom when it is sent.
 * Same shape as the AirDrop handoff: light, then closer, then a firm end.
 */
object SozuSendHaptic {
    private var job: Job? = null

    fun begin(context: Context, scope: CoroutineScope) {
        val vibrator = vibrator(context) ?: return
        job?.cancel()
        vibrator.cancel()
        job = scope.launch(Dispatchers.Default) {
            var step = 0
            while (isActive) {
                val amp = (32 + step * 14).coerceAtMost(230)
                val on = (12L + step).coerceAtMost(28L)
                val gap = (120L - step * 6L).coerceAtLeast(26L)
                pulse(vibrator, on, amp)
                delay(on + gap)
                if (step < 16) step += 1
            }
        }
    }

    fun finish(context: Context) {
        job?.cancel()
        job = null
        val vibrator = vibrator(context) ?: return
        vibrator.cancel()
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 14, 26, 16, 20, 16, 14, 48),
                intArrayOf(0, 80, 0, 140, 0, 200, 0, 255),
                -1,
            ),
        )
    }

    fun stop(context: Context) {
        job?.cancel()
        job = null
        vibrator(context)?.cancel()
    }

    private fun pulse(vibrator: Vibrator, durationMs: Long, amplitude: Int) {
        val amp = if (vibrator.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amp))
    }

    private fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
