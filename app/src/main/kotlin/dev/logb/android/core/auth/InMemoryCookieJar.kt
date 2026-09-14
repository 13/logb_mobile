package dev.logb.android.core.auth

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Holds the session cookie for the one exchange that needs it: sign in, mint a token, sign out.
 * Discarded afterwards, so the app never keeps a cookie session alive beside its token.
 */
class InMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this.cookies) {
            this.cookies.removeAll { c -> cookies.any { it.name == c.name } }
            this.cookies.addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(cookies) { cookies.filter { it.matches(url) } }
}
