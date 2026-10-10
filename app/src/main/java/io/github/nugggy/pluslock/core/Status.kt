package io.github.nugggy.pluslock.core

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import io.github.nugggy.pluslock.service.PlusAccessibilityService
import io.github.nugggy.pluslock.service.PlusNotificationListener

/** Checks for each permission PlusLock needs, and the screen that grants it. */
object Status {
    fun accessibility(ctx: Context): Boolean {
        if (PlusAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, PlusAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun notificationAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(ctx, PlusNotificationListener::class.java))

    fun postNotifications(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun writeSettings(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    fun installUpdates(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun dndAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun dndAccessIntent() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    private fun pkgUri(ctx: Context) = Uri.parse("package:" + ctx.packageName)

    fun accessibilityIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun notificationAccessIntent(ctx: Context) =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(ctx, PlusNotificationListener::class.java).flattenToString(),
            )
    fun writeSettingsIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(ctx))
    fun installIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri(ctx))
    @Suppress("BatteryLife")
    fun batteryIntent(ctx: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(ctx))
    fun appInfoIntent(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
}
