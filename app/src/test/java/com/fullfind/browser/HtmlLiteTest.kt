package com.fullfind.browser

import org.junit.Assert.*
import org.junit.Test

class HtmlLiteTest {
    @Test fun basicPage() {
        val html = """<!DOCTYPE html><html><head><title>Test &amp; Page</title><style>body{x:1}</style>
            <script>var a = "<p>no</p>";</script></head><body><h1>Hello&nbsp;World</h1>
            <p>Para one with <a href="/next?a=1&amp;b=2">a link</a>.</p><!-- comment <b>x</b> -->
            <ul><li>One</li><li>Two</li></ul><img alt="logo" src="x.png"><p>&#169; &#x263A;</p></body></html>"""
        val d = HtmlLite.parse(html, "https://example.com/dir/page.html")
        assertEquals("Test & Page", d.title)
        assertTrue(d.text, d.text.startsWith("Hello World"))
        assertFalse(d.text.contains("no"))
        assertFalse(d.text.contains("comment"))
        assertFalse(d.text.contains("body{"))
        assertTrue(d.text.contains("• One"))
        assertTrue(d.text.contains("[logo]"))
        assertTrue(d.text.contains("© ☺"))
        assertEquals(1, d.links.size)
        val l = d.links[0]
        assertEquals("a link", d.text.substring(l.start, l.end))
        assertEquals("https://example.com/next?a=1&b=2", l.href)
    }

    @Test fun duckDuckGoHtmlResult() {
        val html = """<html><head><meta charset="utf-8"><title>cats at DuckDuckGo</title></head><body>
            <form action="/html/" method="post"><input name="q" value="cats"><input type="submit" value="Search"></form>
            <div class="result"><h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FCat">Cat - Wikipedia</a></h2>
            <a class="result__snippet">The cat is a small carnivorous mammal.</a></div></body></html>"""
        val d = HtmlLite.parse(html, "https://html.duckduckgo.com/html/?q=cats")
        assertEquals("cats at DuckDuckGo", d.title)
        val hrefs = d.links.map { it.href }
        assertTrue(hrefs.toString(), hrefs.contains("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FCat"))
        assertTrue(d.text.contains("Cat - Wikipedia"))
        assertTrue(d.text.contains("[Search]"))
        assertEquals("utf-8", HtmlLite.metaCharset(html))
    }

    @Test fun googleLikeHeavyPage() {
        val big = StringBuilder("<html><head><title>Google</title>")
        repeat(2000) { big.append("<script>function f$it(){return '<div>'}</script>") }
        big.append("</head><body><a href='https://www.google.com/search?q=x'>Search</a><noscript><p>Enable JS</p></noscript></body></html>")
        val t0 = System.currentTimeMillis()
        val d = HtmlLite.parse(big.toString(), "https://www.google.com/")
        assertTrue("slow", System.currentTimeMillis() - t0 < 2000)
        assertEquals("Google", d.title)
        assertEquals("Search", d.text.trim())
        assertEquals("https://www.google.com/search?q=x", d.links.single().href)
    }

    @Test fun unclosedAndMalformed() {
        val d = HtmlLite.parse("<p>Hi <b>there<a href='x'>link<p>end &bogus; &", "http://h/")
        assertTrue(d.text.contains("Hi there"))
        assertTrue(d.text.contains("end &bogus; &"))
    }
}
