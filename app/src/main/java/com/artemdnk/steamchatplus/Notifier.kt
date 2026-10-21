package com.artemdnk.steamchatplus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.JavaScriptReplyProxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Native notifications for messages coming from the page's Notification polyfill. */
object Notifier {
    const val CH_MESSAGES = "messages"
    const val CH_CALL = "call"
    const val CH_BACKGROUND = "background"
    const val ID_SESSION = 1
    const val EXTRA_NOTIF_KEY = "notif_key"
    private const val GROUP = "steam_messages"
    private const val ID_SUMMARY = 2

    private data class Origin(val proxy: JavaScriptReplyProxy?, val jsId: Int, val androidId: Int)

    /** android notification id -> where it came from (for click routing). */
    private val origins = LinkedHashMap<Int, Origin>()
    private var counter = 1000
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MESSAGES, ctx.getString(R.string.ch_messages), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_CALL, ctx.getString(R.string.ch_call), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { setSound(null, null) },
                NotificationChannel(CH_BACKGROUND, ctx.getString(R.string.ch_background), NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        )
    }

    fun showMessage(
        ctx: Context,
        proxy: JavaScriptReplyProxy?,
        jsId: Int,
        title: String,
        body: String,
        iconUrl: String,
        tag: String,
        silent: Boolean
    ) {
        if (!Prefs.notifications(ctx) || !Permissions.hasNotifications(ctx)) return
        // Same tag (= same conversation) replaces the previous notification, like browsers do.
        val androidId = if (tag.isNotEmpty()) (tag.hashCode() and 0x3fffffff) or 0x10000 else counter++
        origins.remove(androidId)
        origins[androidId] = Origin(proxy, jsId, androidId)
        while (origins.size > 200) origins.remove(origins.keys.first())

        val safeIcon = iconUrl.takeIf { it.startsWith("https://") }
        if (safeIcon == null) {
            post(ctx, androidId, title, body, null, silent)
        } else {
            io.execute {
                val bmp = loadBitmap(safeIcon)
                main.post { post(ctx, androidId, title, body, bmp, silent) }
            }
        }
    }

    private fun post(ctx: Context, id: Int, title: String, body: String, icon: Bitmap?, silent: Boolean) {
        if (!origins.containsKey(id)) return // closed before the avatar arrived
        val tap = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_NOTIF_KEY, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(title.ifBlank { ctx.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setLargeIcon(icon)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP)
            .build()
        val summary = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching {
            val nm = NotificationManagerCompat.from(ctx)
            nm.notify(id, n)
            nm.notify(ID_SUMMARY, summary)
        }
    }

    fun closeFromPage(ctx: Context, proxy: JavaScriptReplyProxy?, jsId: Int) {
        val entry = origins.values.firstOrNull { it.proxy == proxy && it.jsId == jsId } ?: return
        origins.remove(entry.androidId)
        NotificationManagerCompat.from(ctx).cancel(entry.androidId)
    }

    /** Called by MainActivity when the user taps a message notification. */
    fun onClicked(androidId: Int) {
        val o = origins.remove(androidId) ?: return
        PageBridge.notificationClicked(o.proxy, o.jsId)
    }

    fun cancelMessages(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        origins.keys.toList().forEach { nm.cancel(it) }
        origins.clear()
        nm.cancel(ID_SUMMARY)
    }

    private fun loadBitmap(url: String): Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.instanceFollowRedirects = true
        try {
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
