# JS fallback bridge (used only on old WebView without WebMessageListener)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
