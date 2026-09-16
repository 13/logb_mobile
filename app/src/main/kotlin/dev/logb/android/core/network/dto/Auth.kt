package dev.logb.android.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Credentials(val username: String, val password: String)

@Serializable
data class User(
    val id: Long,
    val username: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    val lang: String = "en",
)

@Serializable
data class NewToken(val name: String)

@Serializable
data class ApiToken(
    val id: Long,
    val name: String,
    val prefix: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
)

/** `POST /auth/tokens`: the plaintext is in this response and nowhere else, ever. */
@Serializable
data class NewApiToken(
    val id: Long,
    val name: String,
    val prefix: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    val token: String,
)

@Serializable
data class Settings(val currency: String = "EUR", val timezone: String = "UTC")

/** `PATCH /api/users/{id}` for one's own account: only the password from the phone. */
@Serializable
data class UserPatch(val password: String)

/** `GET /api/health`, for the server version shown on the Account screen. */
@Serializable
data class HealthInfo(val version: String = "", val status: String = "", val features: List<String> = emptyList())

/** `POST /api/auth/pair/redeem`: the code scanned from a QR code (or opened as a deep link), and this phone's name. */
@Serializable
data class PairRedeem(val code: String, @SerialName("device_name") val deviceName: String)

/** The pairing code swapped for a token, exactly as a password sign-in would mint one. */
@Serializable
data class PairRedeemed(val token: String, @SerialName("token_id") val tokenId: Long, val user: User)
