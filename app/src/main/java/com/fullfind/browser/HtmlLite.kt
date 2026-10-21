package com.fullfind.browser

import java.net.URL

/** Pure-JVM HTML -> plain text + link ranges. No regex backtracking, safe on large pages. */
object HtmlLite {
    data class Link(val start: Int, val end: Int, val href: String)
    data class Doc(val title: String?, val text: String, val links: List<Link>)

    private val SKIP = setOf("script", "style", "noscript", "svg", "template", "head", "iframe", "object", "canvas", "select")
    private val BLOCK = setOf("p", "div", "br", "li", "ul", "ol", "tr", "table", "section", "article", "header", "footer",
        "nav", "main", "aside", "form", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "hr", "dd", "dt", "dl", "figure", "td", "th", "option", "button")

    fun parse(html: String, base: String?): Doc {
        val title = extractTitle(html)
        val out = StringBuilder(); val links = ArrayList<Link>()
        var linkStart = -1; var linkHref: String? = null
        val baseUrl = try { base?.let { URL(it) } } catch (_: Exception) { null }
        var i = 0; val n = html.length
        fun nl() { trimEndSpaces(out); if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append(if (out.endsWith("\n")) "\n" else "\n") }
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                if (html.startsWith("<!--", i)) { val e = html.indexOf("-->", i + 4); i = if (e < 0) n else e + 3; continue }
                val e = html.indexOf('>', i + 1); if (e < 0) break
                val raw = html.substring(i + 1, e).trim(); i = e + 1
                if (raw.isEmpty() || raw[0] == '!' || raw[0] == '?') continue
                val closing = raw[0] == '/'
                val body = if (closing) raw.substring(1) else raw
                var k = 0; while (k < body.length && (body[k].isLetterOrDigit())) k++
                val tag = body.substring(0, k).lowercase()
                if (!closing && tag in SKIP && !raw.endsWith("/")) {
                    val close = indexOfIgnoreCase(html, "</$tag", i); i = if (close < 0) n else (html.indexOf('>', close).let { if (it < 0) n else it + 1 }); continue
                }
                when {
                    tag == "title" && !closing -> { val close = indexOfIgnoreCase(html, "</title", i); i = if (close < 0) n else (html.indexOf('>', close) + 1).coerceAtLeast(close + 1) }
                    tag == "a" && !closing -> { attr(body, "href")?.let { h -> linkHref = resolve(baseUrl, decode(h)); linkStart = out.length } }
                    tag == "a" && closing -> {
                        trimEndSpaces(out)
                        val h = linkHref
                        if (h != null && linkStart in 0 until out.length) links.add(Link(linkStart, out.length, h))
                        linkHref = null; linkStart = -1; if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                    }
                    tag == "img" -> attr(body, "alt")?.takeIf { it.isNotBlank() }?.let { out.append('[').append(decode(it).trim()).append("] ") }
                    tag == "input" && !closing -> {
                        val type = attr(body, "type")?.lowercase()
                        if (type == "submit" || type == "button") attr(body, "value")?.let { out.append('[').append(decode(it)).append("] ") }
                    }
                    tag == "li" && !closing -> { nl(); out.append("• ") }
                    tag in BLOCK -> nl()
                }
            } else {
                val e = html.indexOf('<', i).let { if (it < 0) n else it }
                val chunk = decode(html.substring(i, e))
                for (ch in chunk) {
                    if (ch.isWhitespace()) { if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ') }
                    else out.append(ch)
                }
                i = e
            }
        }
        // trim leading whitespace and shift links
        var lead = 0; while (lead < out.length && out[lead].isWhitespace()) lead++
        trimEndSpaces(out)
        val text = out.substring(lead).trimEnd()
        val fixed = links.mapNotNull { l -> val s = l.start - lead; val en = minOf(l.end - lead, text.length); if (s >= 0 && en > s) Link(s, en, l.href) else null }
        return Doc(title, text, fixed)
    }

    fun extractTitle(html: String): String? {
        val s = indexOfIgnoreCase(html, "<title", 0); if (s < 0) return null
        val gt = html.indexOf('>', s); if (gt < 0) return null
        val e = indexOfIgnoreCase(html, "</title", gt); if (e < 0) return null
        return decode(html.substring(gt + 1, e)).replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }

    /** charset from <meta charset> or http-equiv in the first 4 KB. */
    fun metaCharset(head: String): String? {
        val m = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(head.take(4096))
        return m?.groupValues?.get(1)
    }

    fun attr(tagBody: String, name: String): String? {
        val r = Regex("(?:^|\\s)" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
        val m = r.find(tagBody) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
    }

    fun resolve(base: URL?, href: String): String = try { if (base != null) URL(base, href.trim()).toString() else href.trim() } catch (_: Exception) { href.trim() }

    private val ENT = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "#39" to "'",
        "copy" to "©", "reg" to "®", "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "middot" to "·", "bull" to "•", "times" to "×", "euro" to "€")

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) {
                    val name = s.substring(i + 1, semi)
                    val rep = when {
                        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> ENT[name.lowercase()]
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun indexOfIgnoreCase(h: String, p: String, from: Int): Int {
        var i = from; val last = h.length - p.length
        while (i <= last) { if (h.regionMatches(i, p, 0, p.length, ignoreCase = true)) return i; i++ }
        return -1
    }
    private fun trimEndSpaces(sb: StringBuilder) { while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1) }
}
