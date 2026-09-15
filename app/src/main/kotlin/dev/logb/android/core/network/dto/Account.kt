package dev.logb.android.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /api/import`: what the archive added. The two type counts exist from logb 0.8.0. */
@Serializable
data class ImportCounts(
    val objects: Int = 0,
    val activities: Int = 0,
    val attachments: Int = 0,
    val reminders: Int = 0,
    @SerialName("types_created") val typesCreated: Int = 0,
    @SerialName("types_merged") val typesMerged: Int = 0,
)

/** `GET /api/me/notifications`. `hour` is the instance's digest hour; push fields are the browser's business. */
@Serializable
data class ServerNotifications(
    val url: String? = null,
    val format: String = "text",
    @SerialName("instance_webhook") val instanceWebhook: Boolean = false,
    val hour: Int = 8,
)

@Serializable
data class ServerNotificationsIn(val url: String?, val format: String)

/** `webhook`: "sent", the failure reason, or null when this user has no webhook. */
@Serializable
data class NotificationTest(
    val webhook: String? = null,
    @SerialName("push_sent") val pushSent: Int = 0,
    @SerialName("push_failed") val pushFailed: Int = 0,
)
