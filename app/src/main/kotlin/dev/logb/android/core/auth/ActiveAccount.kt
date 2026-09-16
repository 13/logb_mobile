package dev.logb.android.core.auth

import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.LogbApi
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The client and the mirror for whoever is signed in right now. Both are rebuilt when the
 * account changes, so a household phone switching users never reads the other person's mirror
 * or sends their token.
 */
@Singleton
class ActiveAccount @Inject constructor(
    private val sessions: SessionRepository,
    private val databases: DatabaseProvider,
    private val apiFactory: ApiFactory,
) {
    private var cachedKey: Pair<String, Long>? = null
    private var cachedDb: LogbDatabase? = null
    private var cachedApi: LogbApi? = null
    private var cachedHttp: OkHttpClient? = null

    /** The signed-in session, or null. */
    val signedIn: Session.SignedIn? get() = sessions.session.value as? Session.SignedIn

    val db: LogbDatabase
        get() = synchronized(this) {
            val s = signedIn ?: error("no account is signed in")
            refresh(s)
            cachedDb!!
        }

    val api: LogbApi
        get() = synchronized(this) {
            val s = signedIn ?: error("no account is signed in")
            refresh(s)
            cachedApi!!
        }

    /** The same bearer-carrying client, for image loading. Null when signed out. */
    val httpClient: OkHttpClient?
        get() = synchronized(this) {
            val s = signedIn ?: return null
            refresh(s)
            cachedHttp
        }

    /**
     * The mirror, client and session for whoever is signed in, read together in one step -- for
     * work (a sync pass) that must use one account throughout. Null when signed out.
     */
    fun bound(): Bound? = synchronized(this) {
        val s = signedIn ?: return null
        refresh(s)
        Bound(s, cachedDb!!, cachedApi!!)
    }

    data class Bound(val session: Session.SignedIn, val db: LogbDatabase, val api: LogbApi)

    /** `/api/files/{id}` or its thumbnail on the signed-in server; null when signed out. */
    fun fileUrl(serverId: Long, thumb: Boolean): String? =
        signedIn?.let { "${it.serverUrl}api/files/$serverId${if (thumb) "/thumb" else ""}" }

    private fun refresh(s: Session.SignedIn) {
        val key = s.serverUrl to s.user.id
        if (key == cachedKey) return
        cachedDb?.close()
        cachedDb = databases.open(s.serverUrl, s.user.id)
        // Bound to this client's own account: once another account is signed in, this client
        // (and anything still holding it) gets no token at all, never the new one.
        val serverUrl = s.serverUrl
        val userId = s.user.id
        val tokenProvider = { sessions.tokenFor(serverUrl, userId) }
        cachedApi = apiFactory.create(s.serverUrl, tokenProvider, null)
        cachedHttp = ApiClient.okHttp(tokenProvider)
        cachedKey = key
    }

    fun deleteLocalData(serverUrl: String, userId: Long) = synchronized(this) {
        if (cachedKey == serverUrl to userId) {
            cachedDb?.close()
            cachedDb = null
            cachedApi = null
            cachedHttp = null
            cachedKey = null
        }
        databases.delete(serverUrl, userId)
    }
}
