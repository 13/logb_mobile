package dev.logb.android.core.auth

import dev.logb.android.core.network.LogbApi
import dev.logb.android.core.network.dto.Credentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One block of calls on a short cookie session. logb creates and revokes API tokens only for an
 * interactive login (`SessionUser`): a token that could mint tokens would be a leak that repairs
 * itself. So the password is typed, used for this login, and forgotten; the phone's own token is
 * never sent on these calls.
 */
class PasswordSession @Inject constructor(private val sessions: SessionRepository, private val apiFactory: ApiFactory) {
    /**
     * `runCatching` alone would also wrap a [CancellationException] -- leaving the screen mid-call
     * -- into a failed [Result], which stops the coroutine from actually cancelling. That is caught
     * and rethrown before the general case. Either way out, [LogbApi.logout] still runs, in
     * [NonCancellable] so a cancellation in flight cannot cut it short: the cookie session must not
     * outlive the screen that opened it.
     */
    suspend fun <T> run(password: String, block: suspend (LogbApi) -> T): Result<T> = try {
        val signedIn = sessions.session.value as? Session.SignedIn ?: error("signed out")
        val api = apiFactory.create(signedIn.serverUrl, { null }, InMemoryCookieJar())
        api.login(Credentials(signedIn.user.username, password))
        try {
            Result.success(block(api))
        } finally {
            withContext(NonCancellable) { runCatching { api.logout() } }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
