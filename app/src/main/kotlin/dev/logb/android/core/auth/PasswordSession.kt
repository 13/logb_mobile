package dev.logb.android.core.auth

import dev.logb.android.core.network.LogbApi
import dev.logb.android.core.network.dto.Credentials
import javax.inject.Inject

/**
 * One block of calls on a short cookie session. logb creates and revokes API tokens only for an
 * interactive login (`SessionUser`): a token that could mint tokens would be a leak that repairs
 * itself. So the password is typed, used for this login, and forgotten; the phone's own token is
 * never sent on these calls.
 */
class PasswordSession @Inject constructor(private val sessions: SessionRepository, private val apiFactory: ApiFactory) {
    suspend fun <T> run(password: String, block: suspend (LogbApi) -> T): Result<T> = runCatching {
        val signedIn = sessions.session.value as? Session.SignedIn ?: error("signed out")
        val api = apiFactory.create(signedIn.serverUrl, { null }, InMemoryCookieJar())
        api.login(Credentials(signedIn.user.username, password))
        try {
            block(api)
        } finally {
            runCatching { api.logout() }
        }
    }
}
