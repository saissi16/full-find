package com.fullfind.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*

const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

object Engine {
    /** True when the device has a usable system WebView (most Wear OS watches do NOT). */
    fun webViewAvailable(ctx: Context): Boolean = try {
        if (!ctx.packageManager.hasSystemFeature("android.software.webview")) false
        else { CookieManager.getInstance(); true }
    } catch (_: Throwable) { false }
}

@SuppressLint("SetJavaScriptEnabled")
class WebPage(ctx: Context, cb: Callbacks, desktop: Boolean) : Page {
    private val w = WebView(ctx)
    init {
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true)
            useWideViewPort = true; loadWithOverviewMode = true
        }
        setDesktop(desktop)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.isFocusable = true; w.isFocusableInTouchMode = true
        val self = this
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme ?: return false
                if (s == "http" || s == "https") return false
                cb.external(r.url); return true
            }
            override fun onPageFinished(v: WebView, u: String) = cb.finished(self)
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) = cb.progress(self, p)
        }
        w.setDownloadListener { u, ua, cd, mime, _ -> cb.download(u, ua, cd, mime) }
    }
    override val view get() = w
    override val url get() = w.url
    override val title get() = w.title
    override fun loadUrl(u: String) = w.loadUrl(u)
    override fun canGoBack() = w.canGoBack()
    override fun canGoForward() = w.canGoForward()
    override fun goBack() = w.goBack()
    override fun goForward() = w.goForward()
    override fun reload() = w.reload()
    override fun stop() = w.stopLoading()
    override fun zoomIn() { w.zoomIn() }
    override fun zoomOut() { w.zoomOut() }
    override fun scrollBy(dy: Int) = w.scrollBy(0, dy)
    override fun setDesktop(d: Boolean) { w.settings.userAgentString = if (d) DESKTOP_UA else null }
    override fun clear() { w.clearCache(true); w.clearHistory(); w.clearFormData() }
    override fun destroy() = w.destroy()
}
