package com.artemdnk.steamchatplus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Owns the single WebView for the whole process. It lives outside the Activity
 * (through a MutableContextWrapper), so a voice call survives the activity being
 * destroyed, rotated or swiped away while the foreground service keeps the process alive.
 */
@SuppressLint("SetJavaScriptEnabled")
object WebHolder {

    const val START_URL = "https://steamcommunity.com/chat/"
    private val ORIGIN_RULES = setOf("https://steamcommunity.com", "https://*.steamcommunity.com")
    private val INTERNAL_DOMAINS = listOf("steamcommunity.com", "steampowered.com")

    interface Host {
        fun requestMicPermission(onResult: (Boolean) -> Unit)
        fun requestNotificationPermission()
        fun showFileChooser(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
        fun onProgress(progress: Int)
        fun onLoadError(description: String)
        fun onWebViewLost()
    }

    var webView: WebView? = null
        private set
    var host: Host? = null

    private var wrapper: MutableContextWrapper? = null
    private var scriptHandler: ScriptHandler? = null
    private var docStartSupported = false
    private var scriptSource: String? = null

    // ---------- origin helpers ----------

    private fun hostMatches(h: String?, domain: String) =
        h != null && (h == domain || h.endsWith(".$domain"))

    fun isTrusted(uri: Uri): Boolean =
        uri.scheme == "https" && hostMatches(uri.host, "steamcommunity.com")

    private fun isInternal(uri: Uri): Boolean =
        uri.scheme == "https" && INTERNAL_DOMAINS.any { hostMatches(uri.host, it) }

    // ---------- lifecycle ----------

    fun attach(activity: Activity, container: ViewGroup): WebView {
        var wv = webView
        if (wv == null) {
            val w = MutableContextWrapper(activity)
            wrapper = w
            wv = create(w)
            webView = wv
            wv.loadUrl(START_URL)
        } else {
            wrapper?.setBaseContext(activity)
            (wv.parent as? ViewGroup)?.removeView(wv)
        }
        container.addView(
            wv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return wv
    }

    fun detach(activity: Activity) {
        val wv = webView ?: return
        if (wrapper?.baseContext === activity) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wrapper?.setBaseContext(App.instance)
        }
    }

    fun goHome() {
        webView?.loadUrl(START_URL)
    }

    fun reload() {
        webView?.reload()
    }

    /** Re-applies settings that affect the page; reloads so the new script runs. */
    fun applySettings() {
        val wv = webView ?: return
        configureSettings(wv)
        installScript(wv)
        wv.reload()
    }

    fun logout() {
        val wv = webView ?: return
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            wv.clearCache(true)
            wv.clearHistory()
            wv.loadUrl(START_URL)
        }
    }

    // ---------- creation ----------

    private fun create(ctx: Context): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val wv = WebView(ctx)
        wv.setBackgroundColor(Color.parseColor("#1b2838"))
        configureSettings(wv)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "SCPBridge", ORIGIN_RULES, PageBridge)
            PageBridge.legacyMode = false
        } else {
            wv.addJavascriptInterface(PageBridge.LegacyJsBridge(), "SCPBridge")
            PageBridge.legacyMode = true
        }
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        installScript(wv)

        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { url, _, _, _, _ -> openLink(Uri.parse(url)) }
        return wv
    }

    private fun configureSettings(wv: WebView) {
        val ctx = App.instance
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = Prefs.textZoom(ctx)
            userAgentString = userAgent(ctx)
        }
    }

    private fun userAgent(ctx: Context): String {
        val def = WebSettings.getDefaultUserAgent(ctx)
        if (!Prefs.desktopUa(ctx)) return def.replace("; wv", "")
        val chrome = Regex("Chrome/([\\d.]+)").find(def)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    private fun buildScript(): String {
        val ctx = App.instance
        val cfg = JSONObject()
            .put("pageWidth", Prefs.pageWidth(ctx))
            .put("css", Prefs.customCss(ctx))
        val body = ctx.assets.open("inject.js").bufferedReader().use { it.readText() }
        return "window.__scpConfig=$cfg;\n$body"
    }

    private fun installScript(wv: WebView) {
        val src = buildScript()
        scriptSource = src
        if (!docStartSupported) return
        scriptHandler?.remove()
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, src, ORIGIN_RULES)
    }

    // ---------- links ----------

    fun openLink(uri: Uri) {
        val ctx: Context = (wrapper?.baseContext as? Activity) ?: App.instance
        val scheme = uri.scheme?.lowercase()
        val intent = if (scheme == "intent") {
            runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                ?.apply { addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null }
                ?: return
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            host?.onLoadError(ctx.getString(R.string.no_app_for_link))
        }
    }

    private class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
            if (!request.isForMainFrame) return false
            if (isInternal(uri)) return false
            openLink(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            PageBridge.onMainFrameNavigation()
            if (!docStartSupported && url != null && isTrusted(Uri.parse(url))) {
                scriptSource?.let { view.evaluateJavascript(it, null) }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) host?.onLoadError(error.description?.toString() ?: "")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            (view.parent as? ViewGroup)?.removeView(view)
            if (view === webView) {
                webView = null
                scriptHandler = null
                PageBridge.onMainFrameNavigation()
                view.destroy()
                host?.onWebViewLost()
            } else {
                view.destroy()
            }
            return true
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            if (!isTrusted(request.origin)) {
                request.deny(); return
            }
            val wanted = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            if (wanted.isEmpty()) {
                request.deny(); return
            }
            val h = host
            if (Permissions.hasMic(App.instance)) {
                request.grant(wanted.toTypedArray())
            } else if (h != null) {
                h.requestMicPermission { ok ->
                    if (ok) request.grant(wanted.toTypedArray()) else request.deny()
                }
            } else {
                request.deny()
            }
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            val h = host
            if (h == null) {
                filePathCallback.onReceiveValue(null)
                return true
            }
            return h.showFileChooser(filePathCallback, fileChooserParams)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            host?.onProgress(newProgress)
        }

        // target=_blank / window.open: catch the URL in a throwaway WebView, open it outside.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val temp = WebView(view.context)
            var handled = false
            fun take(u: Uri?) {
                if (handled || u == null) return
                val s = u.toString()
                if (s.isBlank() || s == "about:blank") return
                handled = true
                openLink(u)
                temp.post { temp.destroy() }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    take(r.url); return true
                }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    take(url?.let(Uri::parse))
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }
}
