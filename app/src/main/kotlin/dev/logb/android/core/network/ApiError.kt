package dev.logb.android.core.network

import java.io.IOException

/** The server said no: `{ "error": code, "message": text }` with a 4xx or 5xx. Not a connectivity failure. */
open class ApiException(val status: Int, val code: String, override val message: String) : IOException(message)

/** 401: the token is gone or revoked. The session drops the token and asks for a password again. */
class UnauthorizedException(message: String) : ApiException(401, "unauthorized", message)

/** 410 from pull: the cursor predates the retained history, or the epoch changed. Re-bootstrap. */
class GoneException(message: String) : ApiException(410, "gone", message)
