package io.github.nugggy.pluslock.update

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.nugggy.pluslock.BuildConfig
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.ui.MainActivity

object UpdateScheduler {
    private const val JOB_ID = 4201

    fun schedule(ctx: Context, replace: Boolean = false) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (!replace && js.getPendingJob(JOB_ID) != null) return
        val hours = P.updateIntervalHours.value.coerceAtLeast(1)
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
            .setRequiredNetworkType(if (P.updateWifiOnly.value) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(hours * 3_600_000L)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }
}

class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Prefs.init(this)
        Thread {
            runCatching { Updater.runScheduled(applicationContext, foregroundUi = MainActivity.isVisible) }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // First self-update: Android wants one confirmation tap.
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (MainActivity.isVisible) {
                    runCatching { ctx.startActivity(confirm) }
                } else {
                    val pi = PendingIntent.getActivity(ctx, 7, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    Notifs.post(ctx, Notifs.ID_UPDATE, Notifs.CH_UPDATES, "PlusLock update ready",
                        "Tap to finish installing. After this one, updates install by themselves.", pi)
                }
                P.updateStatus.value = "Waiting for you to confirm the install"
            }
            PackageInstaller.STATUS_SUCCESS -> P.updateStatus.value = "Updated"
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                P.updateStatus.value = "Install failed: $msg"
                Notifs.post(ctx, Notifs.ID_UPDATE, Notifs.CH_UPDATES, "PlusLock update failed", msg, Notifs.openApp(ctx, "updates"))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        UpdateScheduler.schedule(ctx, replace = true)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val v = BuildConfig.VERSION_NAME
            if (P.lastSeenVersion.value != v) {
                P.lastSeenVersion.value = v
                P.updateStatus.value = "Updated to $v"
                Notifs.post(ctx, Notifs.ID_UPDATE, Notifs.CH_UPDATES, "PlusLock updated to $v",
                    "Tap to see what's new.", Notifs.openApp(ctx, "updates"))
            }
        }
    }
}
