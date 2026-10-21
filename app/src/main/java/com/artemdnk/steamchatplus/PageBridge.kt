package com.artemdnk.steamchatplus

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONObject

/**
 * Receives messages from inject.js and sends commands back.
 * Every frame of a trusted origin gets its own [JavaScriptReplyProxy]; call state is
 * aggregated over all frames so an idle iframe can't "end" a live call.
 */
object PageBridge : WebViewCompat.WebMessageListener {

    private class FrameState {
        var callActive = false
        var muted = false
    }

    private val frames = LinkedHashMap<JavaScriptReplyProxy, FrameState>()
    private val legacyFrame = FrameState()
    private val main = Handler(Looper.getMainLooper())

    /** True when the page is talking to us through the old addJavascriptInterface path. */
    var legacyMode = false

    /** Mirrors the activity visibility; sent to every frame. */
    var hidden = true
        private set

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        if (!WebHolder.isTrusted(sourceOrigin)) return
        val st = frames.getOrPut(replyProxy) { FrameState() }
        handle(message.data, st, replyProxy)
    }

    fun handleLegacy(data: String) = handle(data, legacyFrame, null)

    private fun handle(data: String?, st: FrameState, proxy: JavaScriptReplyProxy?) {
        val json = runCatching { JSONObject(data ?: return) }.getOrNull() ?: return
        val ctx = App.instance
        when (json.optString("type")) {
            "hello" -> {
                val hiddenCmd = cmd("hidden", hidden)
                val muteCmd = cmd("mute", CallManager.muted)
                if (proxy != null) {
                    runCatching { proxy.postMessage(hiddenCmd) }
                    if (CallManager.muted) runCatching { proxy.postMessage(muteCmd) }
                } else {
                    evalCmd(hiddenCmd)
                }
            }
            "call" -> {
                st.callActive = json.optBoolean("active")
                st.muted = json.optBoolean("muted")
                publishCall()
            }
            "notify" -> Notifier.showMessage(
                ctx,
                proxy = proxy,
                jsId = json.optInt("id"),
                title = json.optString("title"),
                body = json.optString("body"),
                iconUrl = json.optString("icon"),
                tag = json.optString("tag"),
                silent = json.optBoolean("silent")
            )
            "notifyClose" -> Notifier.closeFromPage(ctx, proxy, json.optInt("id"))
            "notifyPermission" -> WebHolder.host?.requestNotificationPermission()
        }
    }

    private fun publishCall() {
        val all = frames.values + legacyFrame
        val active = all.any { it.callActive }
        val muted = all.filter { it.callActive }.any { it.muted }
        CallManager.onPageState(active, muted)
    }

    /** New top-level document: forget frames of the old one. */
    fun onMainFrameNavigation() {
        frames.clear()
        legacyFrame.callActive = false
        legacyFrame.muted = false
        publishCall()
    }

    fun setHidden(h: Boolean) {
        hidden = h
        broadcast(cmd("hidden", h))
    }

    fun setMuted(m: Boolean) = broadcast(cmd("mute", m))

    fun notificationClicked(proxy: JavaScriptReplyProxy?, jsId: Int) {
        val c = JSONObject().put("t", "click").put("id", jsId).toString()
        if (proxy != null) runCatching { proxy.postMessage(c) } else evalCmd(c)
    }

    private fun cmd(t: String, v: Boolean) = JSONObject().put("t", t).put("v", v).toString()

    private fun broadcast(c: String) {
        if (frames.isEmpty() || legacyMode) evalCmd(c)
        frames.keys.toList().forEach { runCatching { it.postMessage(c) } }
    }

    private fun evalCmd(c: String) {
        WebHolder.webView?.evaluateJavascript(
            "window.__scp && window.__scp.cmd($c);", null
        )
    }

    /** Fallback for WebView builds without WebMessageListener. */
    class LegacyJsBridge {
        @SuppressLint("JavascriptInterface")
        @JavascriptInterface
        fun postMessage(data: String) {
            // Called on a WebView background thread; check origin on the UI thread.
            main.post {
                val url = WebHolder.webView?.url ?: return@post
                if (WebHolder.isTrusted(Uri.parse(url))) handleLegacy(data)
            }
        }
    }
}
