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
