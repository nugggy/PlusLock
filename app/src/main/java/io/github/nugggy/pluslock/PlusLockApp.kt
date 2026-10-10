package io.github.nugggy.pluslock

import android.app.Application
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.update.UpdateScheduler

class PlusLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifs.createChannels(this)
        UpdateScheduler.schedule(this)
    }
}
