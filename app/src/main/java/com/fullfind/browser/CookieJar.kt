package com.fullfind.browser

import java.net.CookieManager as JCM
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI

object CookieJar {
    val jar = JCM(null, CookiePolicy.ACCEPT_ALL)
    fun get(u: String): String? = try { jar.cookieStore.get(URI(u)).joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null } } catch (_: Exception) { null }
    fun set(u: String, h: String) { try { HttpCookie.parse(h).forEach { jar.cookieStore.add(URI(u), it) } } catch (_: Exception) {} }
    fun clear() = jar.cookieStore.removeAll()
}
