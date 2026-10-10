package io.github.nugggy.pluslock.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Turns the screen on and gets out of the way, leaving the lock screen showing. */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 600)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
