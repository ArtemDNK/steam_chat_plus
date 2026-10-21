package com.artemdnk.steamchatplus

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager

/**
 * Chromium inside WebView already switches the audio mode to "in communication"
 * while a WebRTC call is running and picks the loudspeaker by default.
 * This class only adds a manual speaker/earpiece toggle and the proximity screen-off
 * lock for earpiece mode, and undoes its own route change when the call ends.
 */
object AudioRouter {

    private var weChangedRoute = false
    private var proximity: PowerManager.WakeLock? = null

    private val headsetTypes = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 31) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }

    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun onCallStart(ctx: Context) = updateProximity(ctx)

    @Suppress("DEPRECATION")
    fun onCallEnd(ctx: Context) {
        if (weChangedRoute) {
            val am = am(ctx)
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else am.isSpeakerphoneOn = false
        }
        weChangedRoute = false
        releaseProximity()
    }

    @Suppress("DEPRECATION")
    fun isSpeakerOn(ctx: Context): Boolean {
        val am = am(ctx)
        return if (Build.VERSION.SDK_INT >= 31) {
            am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            am.isSpeakerphoneOn
        }
    }

    fun hasHeadset(ctx: Context): Boolean =
        am(ctx).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes }

    @Suppress("DEPRECATION")
    fun setSpeaker(ctx: Context, on: Boolean) {
        val am = am(ctx)
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            val target = if (on) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                val order = listOf(
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                )
                order.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } }
            }
            if (target != null) am.setCommunicationDevice(target)
        } else {
            am.isSpeakerphoneOn = on
        }
        weChangedRoute = true
        updateProximity(ctx)
    }

    /** Screen off near the ear, only when the sound actually goes to the earpiece. */
    private fun updateProximity(ctx: Context) {
        val earpiece = CallManager.active && !isSpeakerOn(ctx) && !hasHeadset(ctx)
        if (earpiece) acquireProximity(ctx) else releaseProximity()
    }

    private fun acquireProximity(ctx: Context) {
        if (proximity?.isHeld == true) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "SteamChatPlus:proximity")
            .apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseProximity() {
        proximity?.let { if (it.isHeld) it.release() }
        proximity = null
    }
}
