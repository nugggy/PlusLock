package io.github.nugggy.pluslock.core

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/** Haptic signatures: feel who is messaging without looking at the phone. */
object Haptics {
    val presets = linkedMapOf(
        "NONE" to "Off",
        "HEARTBEAT" to "Heartbeat",
        "KNOCK" to "Knock knock",
        "RAPID" to "Rapid fire",
        "WAVE" to "Wave",
        "LONG" to "Long buzz",
        "SOS" to "SOS",
        "MORSE" to "Morse of the app's first letter",
    )

    private val morse = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---",
        '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
    )

    private fun morseTimings(text: String): LongArray {
        val out = mutableListOf(0L)
        text.uppercase().forEachIndexed { i, ch ->
            val code = morse[ch] ?: return@forEachIndexed
            code.forEachIndexed { j, s ->
                out += if (s == '.') 70L else 220L
                out += if (j == code.lastIndex) 0L else 90L
            }
            if (i != text.lastIndex) out[out.lastIndex] = 300L
        }
        return out.toLongArray()
    }

    fun timings(preset: String, appLabel: String): LongArray? = when (preset) {
        "HEARTBEAT" -> longArrayOf(0, 60, 110, 90, 600, 60, 110, 90)
        "KNOCK" -> longArrayOf(0, 35, 140, 35, 420, 35, 140, 35)
        "RAPID" -> longArrayOf(0, 25, 45, 25, 45, 25, 45, 25, 45, 25, 45, 25)
        "WAVE" -> longArrayOf(0, 20, 30, 40, 30, 70, 30, 110, 30, 70, 30, 40, 30, 20)
        "LONG" -> longArrayOf(0, 850)
        "SOS" -> morseTimings("SOS")
        "MORSE" -> morseTimings((appLabel.firstOrNull { it.isLetterOrDigit() } ?: 'E').toString())
        else -> null
    }

    fun play(ctx: Context, preset: String, appLabel: String) {
        val t = timings(preset, appLabel) ?: return
        val vm = ctx.getSystemService(VibratorManager::class.java) ?: return
        val effect = VibrationEffect.createWaveform(t, -1)
        runCatching {
            vm.defaultVibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        }
    }

    fun tick(ctx: Context) {
        val vm = ctx.getSystemService(VibratorManager::class.java) ?: return
        runCatching {
            vm.defaultVibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
            )
        }
    }
}
