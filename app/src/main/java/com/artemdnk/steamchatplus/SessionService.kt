package com.artemdnk.steamchatplus

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service with two jobs:
 *  - during a voice call: type "microphone", keeps capture alive with the screen off,
 *    holds CPU/Wi-Fi locks, offers Mute / Speaker actions;
 *  - optionally ("keep alive" setting): keeps the web chat connected for message notifications.
 */
class SessionService : Service() {

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> CallManager.toggleMute()
            ACTION_SPEAKER -> CallManager.toggleSpeaker()
            ACTION_HANGUP -> SteamState.hangup()
            ACTION_REJECT -> {
                val id = intent?.getLongExtra(EXTRA_ACCOUNT, 0L) ?: 0L
                if (id != 0L) {
                    SteamState.reject(id)
                    Notifier.cancelIncoming(this, id)
                }
            }
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun refresh() {
        val call = CallManager.active
        if (!call && !Prefs.keepAlive(this)) {
            releaseLocks()
            if (!inForeground) {
                // Started via startForegroundService(): Android demands startForeground() anyway.
                runCatching {
                    ServiceCompat.startForeground(this, Notifier.ID_SESSION, buildNotification(false), foregroundBaseType())
                }
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (instance === this) instance = null // a new sync() will start a fresh service
            stopSelf()
            return
        }
        val notification = buildNotification(call)
        var type = foregroundBaseType()
        if (call && Permissions.hasMic(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, Notifier.ID_SESSION, notification, type)
        } catch (e: Exception) {
            // Android 14 refuses a microphone FGS started from the background; fall back.
            Log.w(TAG, "startForeground with mic failed, retrying without", e)
            runCatching { ServiceCompat.startForeground(this, Notifier.ID_SESSION, notification, foregroundBaseType()) }
        }
        inForeground = true
        if (call) acquireLocks() else releaseLocks()
    }

    private fun foregroundBaseType() =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private fun buildNotification(call: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (!call) {
            return NotificationCompat.Builder(this, Notifier.CH_BACKGROUND)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle(getString(R.string.bg_title))
                .setContentText(getString(R.string.bg_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
        val muted = CallManager.muted
        val speaker = AudioRouter.isSpeakerOn(this)
        val text = buildString {
            append(getString(if (muted) R.string.call_mic_off else R.string.call_mic_on))
            append(" · ")
            append(getString(if (speaker) R.string.call_speaker else R.string.call_earpiece))
        }
        return NotificationCompat.Builder(this, Notifier.CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(SteamState.voice.value?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.call_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setUsesChronometer(true)
            .setWhen(callStartedAt)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                getString(if (muted) R.string.action_unmute else R.string.action_mute),
                actionIntent(ACTION_MUTE, 1)
            )
            .addAction(
                if (speaker) R.drawable.ic_speaker else R.drawable.ic_earpiece,
                getString(if (speaker) R.string.action_earpiece else R.string.action_speaker),
                actionIntent(ACTION_SPEAKER, 2)
            )
            .addAction(R.drawable.ic_call_end, getString(R.string.action_hangup), actionIntent(ACTION_HANGUP, 3))
            .build()
    }

    private fun actionIntent(action: String, req: Int) = PendingIntent.getService(
        this, req, Intent(this, SessionService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (cpuLock?.isHeld != true) {
            cpuLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SteamChatPlus:call")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "SteamChatPlus:call")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        cpuLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "SessionService"
        const val ACTION_MUTE = "com.artemdnk.steamchatplus.MUTE"
        const val ACTION_SPEAKER = "com.artemdnk.steamchatplus.SPEAKER"
        const val ACTION_HANGUP = "com.artemdnk.steamchatplus.HANGUP"
        const val ACTION_REJECT = "com.artemdnk.steamchatplus.REJECT"
        const val EXTRA_ACCOUNT = "account"

        private var instance: SessionService? = null
        private var callStartedAt = 0L
        private var lastCall = false

        /** Start, update or stop the service to match the current state. */
        fun sync(ctx: Context) {
            val call = CallManager.active
            if (call && !lastCall) callStartedAt = System.currentTimeMillis()
            lastCall = call
            val shouldRun = call || Prefs.keepAlive(ctx)
            val running = instance
            if (running != null) {
                running.refresh()
            } else if (shouldRun) {
                try {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "cannot start foreground service now", e)
                }
            }
        }
    }
}
