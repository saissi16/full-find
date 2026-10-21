package com.fullfind.browser

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/** Common tab interface: backed by WebView when available, otherwise a built-in text renderer. */
interface Page {
    val view: View
    val url: String?
    val title: String?
    fun loadUrl(u: String)
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
    fun zoomIn()
    fun zoomOut()
    fun scrollBy(dy: Int)
    fun setDesktop(d: Boolean)
    fun clear()
    fun destroy()
}

class Callbacks(
    val progress: (Page, Int) -> Unit,
    val finished: (Page) -> Unit,
    val download: (url: String, ua: String, cd: String?, mime: String?) -> Unit,
    val external: (Uri) -> Unit,
)

/** Lightweight HTML renderer for watches without a system WebView (most Wear OS devices). */
class TextPage(ctx: Context, private val cb: Callbacks, private var desktop: Boolean) : Page {
    private val tv = TextView(ctx).apply {
        setTextColor(Color.WHITE); setLinkTextColor(0xFF7FD3FF.toInt()); textSize = 13f
        val p = (24 * ctx.resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p * 2); movementMethod = LinkMovementMethod.getInstance()
    }
    private val sv = ScrollView(ctx).apply { addView(tv); isFocusableInTouchMode = true }
    override val view: View get() = sv
    override var url: String? = null; private set
    override var title: String? = null; private set
    private val back = ArrayList<String>(); private val fwd = ArrayList<String>()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0

    private val ua get() = if (desktop) DESKTOP_UA
        else "Mozilla/5.0 (Linux; Android 11; Watch) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    override fun loadUrl(u: String) { url?.let { if (it != u) { back.add(it); fwd.clear() } }; fetch(u) }
    override fun canGoBack() = back.isNotEmpty()
    override fun canGoForward() = fwd.isNotEmpty()
    override fun goBack() { if (back.isEmpty()) return; url?.let { fwd.add(it) }; fetch(back.removeAt(back.size - 1)) }
    override fun goForward() { if (fwd.isEmpty()) return; url?.let { back.add(it) }; fetch(fwd.removeAt(fwd.size - 1)) }
    override fun reload() { url?.let { fetch(it) } }
    override fun stop() { gen++; cb.progress(this, 100) }
    override fun zoomIn() { tv.textSize = (tv.textSize / tv.resources.displayMetrics.scaledDensity + 2).coerceAtMost(30f) }
    override fun zoomOut() { tv.textSize = (tv.textSize / tv.resources.displayMetrics.scaledDensity - 2).coerceAtLeast(8f) }
    override fun scrollBy(dy: Int) = sv.smoothScrollBy(0, dy)
    override fun setDesktop(d: Boolean) { desktop = d }
    override fun clear() { back.clear(); fwd.clear() }
    override fun destroy() { gen++ }

    private fun fetch(target: String) {
        val my = ++gen
        url = target; title = target
        cb.progress(this, 10)
        val ctx = tv.context.applicationContext
        Thread {
            var result: CharSequence; var t = target; var finalUrl = target
            var via = "?"
            try {
                var u = URL(target); var conn: HttpURLConnection; var hops = 0
                while (true) {
                    val r = Net.open(u) { c ->
                        c.instanceFollowRedirects = false; c.connectTimeout = 20000; c.readTimeout = 20000
                        c.setRequestProperty("User-Agent", ua)
                        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                        c.setRequestProperty("Accept-Encoding", "gzip")
                        CookieJar.get(u.toString())?.let { c.setRequestProperty("Cookie", it) }
                    }
                    conn = r.first; via = r.second
                    val code = conn.responseCode
                    conn.headerFields["Set-Cookie"]?.forEach { CookieJar.set(u.toString(), it) }
                    if (code in 300..399 && hops++ < 10 && conn.getHeaderField("Location") != null) {
                        u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue
                    }
                    break
                }
                finalUrl = u.toString()
                val code = conn.responseCode
                val mime = conn.contentType ?: "text/html"
                if (!mime.startsWith("text/") && !mime.contains("xml") && !mime.contains("json")) {
                    val cd = conn.getHeaderField("Content-Disposition"); conn.disconnect()
                    ui.post { if (my == gen) { cb.progress(this, 100); cb.download(finalUrl, ua, cd, mime.substringBefore(';')) } }
                    return@Thread
                }
                ui.post { if (my == gen) cb.progress(this, 50) }
                val bytes = Net.body(conn)?.use { s ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
                    while (true) { val n = s.read(buf); if (n < 0 || out.size() > 4_000_000 || my != gen) break; out.write(buf, 0, n) }
                    out.toByteArray()
                } ?: ByteArray(0)
                conn.disconnect()
                val headerCs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(mime)?.groupValues?.get(1)
                val cs = headerCs ?: HtmlLite.metaCharset(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)) ?: "UTF-8"
                val html = try { String(bytes, charset(cs)) } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
                val doc = if (mime.contains("html") || mime.contains("xml")) HtmlLite.parse(html, finalUrl) else HtmlLite.Doc(null, html, emptyList())
                t = doc.title ?: finalUrl
                val sp = SpannableStringBuilder(doc.text.ifBlank { "(Page loaded but has no readable text. It may need JavaScript.)\n\nHTTP $code, ${bytes.size} bytes via $via" })
                for (l in doc.links) {
                    if (l.end > sp.length) continue
                    val abs = l.href
                    sp.setSpan(object : ClickableSpan() {
                        override fun onClick(w: View) {
                            val uri = Uri.parse(abs)
                            if (uri.scheme == "http" || uri.scheme == "https") loadUrl(abs) else cb.external(uri)
                        }
                    }, l.start, l.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (code >= 400) sp.insert(0, "HTTP $code\n\n")
                result = sp
            } catch (e: Throwable) {
                val chain = generateSequence(e) { it.cause }.take(4).joinToString("\n← ") { "${it.javaClass.name}: ${it.message}" }
                result = "Could not load page\n\n$target\n\n$chain\n\nNetwork: ${Net.status}; ${Net.describeDefault(ctx)}\nVia: $via"
                android.util.Log.e("FullFind", "load failed $target", e)
            }
            ui.post {
                if (my != gen) return@post
                url = finalUrl; title = t; tv.text = result; sv.scrollTo(0, 0)
                cb.progress(this, 100); cb.finished(this)
            }
        }.start()
    }
}
