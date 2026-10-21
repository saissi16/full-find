package com.fullfind.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.speech.RecognizerIntent
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var progress: ProgressBar
    private val tabs = mutableListOf<WebView>()
    private var cur = 0
    private val prefs by lazy { getSharedPreferences("ff", Context.MODE_PRIVATE) }
    private var desktop = false
    private var pendingInput: EditText? = null
    private val VOICE = 7
    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val engines = linkedMapOf(
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Bing" to "https://www.bing.com/search?q=")

    private val web get() = tabs[cur]
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val home get() = prefs.getString("home", "https://www.google.com")!!

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        CookieManager.getInstance().setAcceptCookie(true)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        container = FrameLayout(this)
        root.addView(container)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(6), Gravity.TOP).apply { setMargins(dp(40), dp(8), dp(40), 0) })
        val fab = ImageButton(this).apply {
            setImageResource(R.drawable.ic_fg); scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDD1E88E5.toInt()) }
            setOnClickListener { showMenu() }
            setOnLongClickListener { showUrlBar(); true }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
        setContentView(root)
        desktop = prefs.getBoolean("desktop", false)
        val saved = JSONArray(prefs.getString("tabs", "[]"))
        for (i in 0 until saved.length()) newTab(saved.getString(i), false)
        intent?.data?.let { newTab(it.toString(), false) }
        if (tabs.isEmpty()) newTab(home, false)
        switchTo(if (intent?.data != null) tabs.size - 1 else prefs.getInt("cur", 0).coerceIn(0, tabs.size - 1))
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); i.data?.let { newTab(it.toString()) } }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String, show: Boolean = true) {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true)
            useWideViewPort = true; loadWithOverviewMode = true
            userAgentString = if (desktop) DESKTOP_UA else null
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.isFocusable = true; w.isFocusableInTouchMode = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme ?: return false
                if (s == "http" || s == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}
                return true
            }
            override fun onPageFinished(v: WebView, u: String) { addHistory(v.title ?: u, u); saveTabs() }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (v !== web) return
                progress.progress = p; progress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
        }
        w.setDownloadListener { url2, ua, cd, mime, _ ->
            try {
                val name = URLUtil.guessFileName(url2, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url2)).setMimeType(mime)
                    .addRequestHeader("User-Agent", ua)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url2) ?: "")
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading $name")
            } catch (e: Exception) { toast("Download failed") }
        }
        w.loadUrl(url)
        tabs.add(w)
        if (show) switchTo(tabs.size - 1)
        saveTabs()
    }

    private fun switchTo(i: Int) {
        cur = i
        container.removeAllViews()
        container.addView(web, -1, -1)
        web.requestFocus()
        saveTabs()
    }

    private fun closeTab(i: Int) {
        val w = tabs.removeAt(i); w.destroy()
        if (tabs.isEmpty()) newTab(home, false)
        switchTo(minOf(if (i <= cur) cur - 1 else cur, tabs.size - 1).coerceAtLeast(0))
    }

    private fun saveTabs() {
        val a = JSONArray(); tabs.forEach { a.put(it.url ?: home) }
        prefs.edit().putString("tabs", a.toString()).putInt("cur", cur).apply()
    }

    // ---- history / bookmarks (JSON in SharedPreferences) ----
    private fun list(key: String) = JSONArray(prefs.getString(key, "[]"))
    private fun putList(key: String, a: JSONArray) = prefs.edit().putString(key, a.toString()).apply()
    private fun addHistory(t: String, u: String) {
        if (u.startsWith("about:")) return
        val a = list("hist")
        if (a.length() > 0 && a.getJSONObject(a.length() - 1).optString("u") == u) return
        a.put(JSONObject().put("t", t).put("u", u))
        while (a.length() > 300) a.remove(0)
        putList("hist", a)
    }
    private fun toggleBookmark() {
        val u = web.url ?: return
        val a = list("bm")
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("u") == u) { a.remove(i); putList("bm", a); toast("Bookmark removed"); return }
        a.put(JSONObject().put("t", web.title ?: u).put("u", u)); putList("bm", a); toast("Bookmarked")
    }

    // ---- URL bar ----
    private fun go(input: String) {
        val s = input.trim(); if (s.isEmpty()) return
        val url = when {
            s.contains("://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> engine() + Uri.encode(s)
        }
        web.loadUrl(url)
    }
    private fun engine() = engines[prefs.getString("engine", "Google")] ?: engines["Google"]!!

    private fun showUrlBar() {
        val box = col()
        val et = EditText(this).apply {
            setText(web.url); selectAll(); setSingleLine(); textSize = 13f
            hint = "Search or URL"; imeOptions = EditorInfo.IME_ACTION_GO
        }
        box.addView(et)
        val d = dialog("Go to", box)
        et.setOnEditorActionListener { _, _, _ -> go(et.text.toString()); d.dismiss(); true }
        box.addView(btn("Go") { go(et.text.toString()); d.dismiss() })
        box.addView(btn("🎤 Voice") { d.dismiss(); voice() })
        d.show(); et.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    private fun voice() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search or say a site"), VOICE)
        } catch (e: Exception) { toast("Voice input unavailable") }
    }
    override fun onActivityResult(rq: Int, rs: Int, data: Intent?) {
        super.onActivityResult(rq, rs, data)
        if (rq == VOICE && rs == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            go(it.replace(" dot ", ".").let { s -> if (s.contains(' ')) s else s.lowercase() })
        }
    }

    // ---- menu ----
    private fun showMenu() {
        val box = col()
        val d = dialog(null, box)
        fun item(t: String, f: () -> Unit) = box.addView(btn(t) { d.dismiss(); f() })
        item("🔍 Search / URL") { showUrlBar() }
        item("◀ Back") { if (web.canGoBack()) web.goBack() }
        item("▶ Forward") { if (web.canGoForward()) web.goForward() }
        item("⟳ Reload") { web.reload() }
        item("✕ Stop") { web.stopLoading() }
        item("⌂ Home") { web.loadUrl(home) }
        item("🗂 Tabs (${tabs.size})") { showTabs() }
        item("＋ New tab") { newTab(home) }
        item("★ Bookmark this") { toggleBookmark() }
        item("☰ Bookmarks") { showList("bm", "Bookmarks") }
        item("🕘 History") { showList("hist", "History") }
        item("＋ Zoom in") { web.zoomIn() }
        item("－ Zoom out") { web.zoomOut() }
        item(if (desktop) "📱 Mobile site" else "🖥 Desktop site") { toggleDesktop() }
        item("⚙ Settings") { showSettings() }
        d.show()
    }

    private fun toggleDesktop() {
        desktop = !desktop; prefs.edit().putBoolean("desktop", desktop).apply()
        tabs.forEach { it.settings.userAgentString = if (desktop) DESKTOP_UA else null; it.settings.loadWithOverviewMode = true }
        web.reload()
    }

    private fun showTabs() {
        val box = col(); val d = dialog("Tabs", box)
        tabs.forEachIndexed { i, w ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btn((if (i == cur) "● " else "") + (w.title?.takeIf { it.isNotBlank() } ?: w.url ?: "Tab")) { d.dismiss(); switchTo(i) },
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(btn("✕") { d.dismiss(); closeTab(i); showTabs() })
            box.addView(row)
        }
        box.addView(btn("＋ New tab") { d.dismiss(); newTab(home) })
        d.show()
    }

    private fun showList(key: String, title: String) {
        val box = col(); val d = dialog(title, box)
        val a = list(key)
        if (a.length() == 0) box.addView(TextView(this).apply { text = "Empty"; gravity = Gravity.CENTER })
        for (i in a.length() - 1 downTo 0) {
            val o = a.getJSONObject(i)
            box.addView(btn(o.optString("t").ifBlank { o.optString("u") }) { d.dismiss(); web.loadUrl(o.optString("u")) }.apply {
                setOnLongClickListener { a.remove(i); putList(key, a); d.dismiss(); toast("Removed"); showList(key, title); true }
            })
        }
        if (a.length() > 0) box.addView(btn("🗑 Clear all") { putList(key, JSONArray()); d.dismiss(); toast("$title cleared") })
        box.addView(TextView(this).apply { text = "Long-press an item to remove"; textSize = 10f; gravity = Gravity.CENTER })
        d.show()
    }

    private fun showSettings() {
        val box = col(); val d = dialog("Settings", box)
        val et = EditText(this).apply { setText(home); setSingleLine(); textSize = 12f; hint = "Home page" }
        box.addView(TextView(this).apply { text = "Home page" }); box.addView(et)
        box.addView(btn("Use current page as home") { et.setText(web.url) })
        box.addView(btn("Save home page") { prefs.edit().putString("home", et.text.toString().let { if (it.contains("://")) it else "https://$it" }).apply(); toast("Saved") })
        box.addView(TextView(this).apply { text = "Search engine" })
        engines.keys.forEach { n ->
            box.addView(btn((if (prefs.getString("engine", "Google") == n) "● " else "○ ") + n) { prefs.edit().putString("engine", n).apply(); d.dismiss(); showSettings() })
        }
        box.addView(btn("🗑 Clear history") { putList("hist", JSONArray()); toast("History cleared") })
        box.addView(btn("🗑 Clear cookies & cache") {
            CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData()
            tabs.forEach { it.clearCache(true); it.clearFormData() }; toast("Browsing data cleared")
        })
        box.addView(btn("🗑 Clear all data") {
            CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData()
            tabs.forEach { it.clearCache(true); it.clearHistory() }
            prefs.edit().remove("hist").remove("bm").apply(); toast("All data cleared")
        })
        box.addView(TextView(this).apply { text = "Full Find 1.0\nTip: long-press ☁ for search"; textSize = 10f; gravity = Gravity.CENTER })
        d.show()
    }

    // ---- UI helpers (round-screen friendly: padded, scrolling, centered) ----
    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(22), dp(28), dp(22), dp(40))
    }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        minHeight = dp(36); minimumHeight = dp(36); setOnClickListener { f() }
    }
    private fun dialog(title: String?, content: View): AlertDialog {
        val sv = ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false; isFocusableInTouchMode = true }
        title?.let { (content as LinearLayout).addView(TextView(this).apply { text = it; gravity = Gravity.CENTER; textSize = 14f }, 0) }
        sv.setOnGenericMotionListener { v, e ->
            if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                v.scrollBy(0, (-e.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(48)).toInt()); true } else false
        }
        return AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).setView(sv).create().also {
            it.setOnShowListener { sv.requestFocus() }
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- rotary crown scrolls page ----
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) && tabs.isNotEmpty()) {
            web.scrollBy(0, (-e.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(64)).toInt()); return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush(); saveTabs() }
    override fun onDestroy() { tabs.forEach { it.destroy() }; super.onDestroy() }
}
