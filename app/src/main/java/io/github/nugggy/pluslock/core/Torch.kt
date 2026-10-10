package io.github.nugggy.pluslock.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

object Torch {
    @Volatile var isOn = false
        private set
    private var registered = false
    private var cameraId: String? = null

    fun init(ctx: Context) {
        if (registered) return
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return
        cameraId = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) isOn = enabled
            }
        }, Handler(Looper.getMainLooper()))
        registered = true
    }

    /** Highest brightness step the torch supports. 1 means brightness can't be changed. */
    fun maxLevel(ctx: Context): Int {
        init(ctx)
        val id = cameraId ?: return 0
        val cm = ctx.getSystemService(CameraManager::class.java)
        return runCatching {
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        }.getOrDefault(1)
    }

    fun toggle(ctx: Context) = setOn(ctx, !isOn)

    fun setOn(ctx: Context, on: Boolean) {
        init(ctx)
        val id = cameraId ?: return
        val cm = ctx.getSystemService(CameraManager::class.java)
        runCatching {
            val level = P.torchLevel.value
            if (on && level > 0 && maxLevel(ctx) > 1) {
                cm.turnOnTorchWithStrengthLevel(id, level.coerceIn(1, maxLevel(ctx)))
            } else {
                cm.setTorchMode(id, on)
            }
            isOn = on
        }
    }

    fun setLevel(ctx: Context, level: Int) {
        P.torchLevel.value = level
        if (isOn) setOn(ctx, true)
    }
}
