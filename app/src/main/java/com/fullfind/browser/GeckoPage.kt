package com.fullfind.browser

import android.app.Application
import android.content.Context
import android.net.Uri
import org.mozilla.geckoview.*

/** Starts GeckoRuntime at process start so the first page load is fast. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Engine.webViewAvailable(this)) {
            Net.start(this)            // ask for Wi-Fi/cellular instead of the slow Bluetooth proxy
            Gecko.runtime(this)        // warm up the engine
        }
    }
}

object Gecko {
    @Volatile private var rt: GeckoRuntime? = null
    var failure: Throwable? = null; private set

    fun runtime(ctx: Context): GeckoRuntime? {
        rt?.let { return it }
        if (failure != null) return null
        return try {
            val s = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .consoleOutput(false)
                .aboutConfigEnabled(false)
                .automaticFontSizeAdjustment(false)
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                .build()
            GeckoRuntime.create(ctx.applicationContext, s).also { rt = it }
        } catch (t: Throwable) { failure = t; android.util.Log.e("FullFind", "Gecko init failed", t); null }
    }

    fun zoom(ctx: Context, delta: Float) {
        val r = runtime(ctx) ?: return
        r.settings.fontSizeFactor = (r.settings.fontSizeFactor + delta).coerceIn(0.5f, 3f)
    }
    fun clearData(ctx: Context) { runtime(ctx)?.storageController?.clearData(StorageController.ClearFlags.ALL) }
}

class GeckoPage(ctx: Context, private val cb: Callbacks, desktop: Boolean, rt: GeckoRuntime) : Page {
    private val gv = GeckoView(ctx)
    private val session = GeckoSession(GeckoSessionSettings.Builder()
        .usePrivateMode(false)
        .useTrackingProtection(true)
        .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        .build())
    private var back = false; private var fwd = false
    override var url: String? = null; private set
    override var title: String? = null; private set

    init {
        val self = this
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(s: GeckoSession, u: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, userGesture: Boolean) { url = u }
            override fun onCanGoBack(s: GeckoSession, v: Boolean) { back = v }
            override fun onCanGoForward(s: GeckoSession, v: Boolean) { fwd = v }
            override fun onLoadRequest(s: GeckoSession, r: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val scheme = Uri.parse(r.uri).scheme?.lowercase()
                return if (scheme == null || scheme in setOf("http", "https", "about", "data", "blob", "file", "resource", "moz-extension")) GeckoResult.allow()
                else { cb.external(Uri.parse(r.uri)); GeckoResult.deny() }
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, u: String) { cb.progress(self, 5) }
            override fun onProgressChange(s: GeckoSession, p: Int) { cb.progress(self, p) }
            override fun onPageStop(s: GeckoSession, ok: Boolean) { cb.progress(self, 100); cb.finished(self) }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, t: String?) { title = t }
            override fun onExternalResponse(s: GeckoSession, r: WebResponse) {
                val h = r.headers
                cb.download(r.uri, "", h["Content-Disposition"] ?: h["content-disposition"], h["Content-Type"] ?: h["content-type"])
            }
            override fun onCrash(s: GeckoSession) { s.open(rt); url?.let { s.loadUri(it) } }
            override fun onKill(s: GeckoSession) { s.open(rt); url?.let { s.loadUri(it) } }
        }
        session.open(rt)
        gv.setSession(session)
        gv.isFocusable = true; gv.isFocusableInTouchMode = true
    }

    override val view get() = gv
    override fun loadUrl(u: String) { url = u; session.loadUri(u) }
    override fun canGoBack() = back
    override fun canGoForward() = fwd
    override fun goBack() = session.goBack()
    override fun goForward() = session.goForward()
    override fun reload() = session.reload()
    override fun stop() = session.stop()
    override fun zoomIn() = Gecko.zoom(gv.context, 0.15f)
    override fun zoomOut() = Gecko.zoom(gv.context, -0.15f)
    override fun scrollBy(dy: Int) { gv.panZoomController.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(dy.toDouble())) }
    override fun setDesktop(d: Boolean) {
        session.settings.userAgentMode = if (d) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode = if (d) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }
    override fun clear() { session.purgeHistory() }
    override fun destroy() { gv.releaseSession(); session.close() }
}
