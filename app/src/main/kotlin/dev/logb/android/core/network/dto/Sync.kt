package dev.logb.android.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One row of the change feed. `value` is double-encoded JSON, and both SQL NULL and the string "null" mean "clear". */
@Serializable
data class ChangeRow(
    val seq: Long,
    val entity: String,
    @SerialName("entity_uuid") val entityUuid: String,
    val op: String,
    val field: String? = null,
    val value: String? = null,
    @SerialName("edited_at") val editedAt: String,
    @SerialName("device_id") val deviceId: String,
    /** The server's integer id for `entity_uuid`; null once retention has purged the row. */
    @SerialName("entity_id") val entityId: Long? = null,
)

@Serializable
data class PullResult(
    val changes: List<ChangeRow>,
    @SerialName("next_seq") val nextSeq: Long,
    val complete: Boolean,
    @SerialName("server_time") val serverTime: String,
    val epoch: String,
)

/** Every live row the account owns, verbatim by column name; mapped by name in `Bootstrap`. */
@Serializable
data class BootstrapResult(
    val objects: List<JsonObject>,
    val activities: List<JsonObject>,
    val reminders: List<JsonObject>,
    val attachments: List<JsonObject>,
    val files: List<JsonObject>,
    @SerialName("object_types") val objectTypes: List<JsonObject> = emptyList(),
    val seq: Long,
    @SerialName("server_time") val serverTime: String,
    val epoch: String,
)

@Serializable
data class Op(
    @SerialName("client_op_id") val clientOpId: String,
    val entity: String,
    @SerialName("entity_uuid") val entityUuid: String,
    val op: String,
    val field: String? = null,
    val value: JsonElement? = null,
    @SerialName("edited_at") val editedAt: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class PushBody(val ops: List<Op>)

@Serializable
data class OpResult(
    @SerialName("client_op_id") val clientOpId: String,
    val outcome: String,
    val reason: String? = null,
)

@Serializable
data class PushResult(
    val results: List<OpResult>,
    @SerialName("server_time") val serverTime: String,
    val ids: Map<String, Long> = emptyMap(),
)
