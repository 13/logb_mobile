package dev.logb.android.core.auth

import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.network.LogbApi
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

    private fun refresh(s: Session.SignedIn) {
        val key = s.serverUrl to s.user.id
        if (key == cachedKey) return
        cachedDb?.close()
        cachedDb = databases.open(s.serverUrl, s.user.id)
        cachedApi = apiFactory.create(s.serverUrl, { (sessions.session.value as? Session.SignedIn)?.token }, null)
        cachedKey = key
    }

    fun deleteLocalData(serverUrl: String, userId: Long) = synchronized(this) {
        if (cachedKey == serverUrl to userId) {
            cachedDb?.close()
            cachedDb = null
            cachedApi = null
            cachedKey = null
        }
        databases.delete(serverUrl, userId)
    }
}
