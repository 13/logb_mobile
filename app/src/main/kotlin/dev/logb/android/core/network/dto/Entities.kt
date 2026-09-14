package dev.logb.android.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** REST shapes, used by the create path (phase 2). Declared beside the sync DTOs so the two are reviewed together. */
@Serializable
data class ObjectDto(
    val id: Long,
    val name: String,
    val type: String,
    @SerialName("counter_unit") val counterUnit: String? = null,
    @SerialName("fuel_unit") val fuelUnit: String? = null,
    val description: String = "",
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("purchase_price_cents") val purchasePriceCents: Long? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("cover_attachment_id") val coverAttachmentId: Long? = null,
    @SerialName("parent_id") val parentId: Long? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("client_uuid") val clientUuid: String? = null,
)

@Serializable
data class ObjectInput(
    val name: String,
    val type: String,
    @SerialName("counter_unit") val counterUnit: String? = null,
    @SerialName("fuel_unit") val fuelUnit: String? = null,
    val description: String = "",
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("purchase_price_cents") val purchasePriceCents: Long? = null,
    val archived: Boolean? = null,
    @SerialName("parent_id") val parentId: Long? = null,
    @SerialName("client_uuid") val clientUuid: String? = null,
)

@Serializable
data class ActivityDto(
    val id: Long,
    @SerialName("object_id") val objectId: Long,
    val date: String,
    val category: String,
    val title: String,
    val notes: String = "",
    @SerialName("counter_value") val counterValue: Long? = null,
    @SerialName("cost_cents") val costCents: Long? = null,
    @SerialName("quantity_milli") val quantityMilli: Long? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("client_uuid") val clientUuid: String? = null,
)

@Serializable
data class ActivityInput(
    val date: String,
    val category: String,
    val title: String,
    val notes: String = "",
    @SerialName("counter_value") val counterValue: Long? = null,
    @SerialName("cost_cents") val costCents: Long? = null,
    @SerialName("quantity_milli") val quantityMilli: Long? = null,
    @SerialName("client_op_id") val clientOpId: String? = null,
    @SerialName("client_uuid") val clientUuid: String? = null,
)

@Serializable
data class ReminderDto(
    val id: Long,
    @SerialName("object_id") val objectId: Long,
    val title: String,
    val notes: String = "",
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_counter") val dueCounter: Long? = null,
    @SerialName("repeat_months") val repeatMonths: Long? = null,
    @SerialName("repeat_counter") val repeatCounter: Long? = null,
    @SerialName("snoozed_until") val snoozedUntil: String? = null,
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("done_activity_id") val doneActivityId: Long? = null,
    val kind: String = "service",
    @SerialName("every_n") val everyN: Long? = null,
    @SerialName("every_unit") val everyUnit: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("client_uuid") val clientUuid: String? = null,
)

@Serializable
data class ReminderInput(
    val title: String,
    val notes: String = "",
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_counter") val dueCounter: Long? = null,
    @SerialName("repeat_months") val repeatMonths: Long? = null,
    @SerialName("repeat_counter") val repeatCounter: Long? = null,
    val kind: String = "service",
    @SerialName("every_n") val everyN: Long? = null,
    @SerialName("every_unit") val everyUnit: String? = null,
    @SerialName("client_uuid") val clientUuid: String? = null,
)

@Serializable
data class AttachmentDto(
    val id: Long,
    @SerialName("object_id") val objectId: Long,
    @SerialName("activity_id") val activityId: Long? = null,
    @SerialName("file_id") val fileId: Long,
    val kind: String,
    val caption: String = "",
    @SerialName("original_name") val originalName: String,
    val mime: String,
    val size: Long,
    val width: Long? = null,
    val height: Long? = null,
    @SerialName("taken_at") val takenAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("client_uuid") val clientUuid: String? = null,
    @SerialName("file_uuid") val fileUuid: String? = null,
)
