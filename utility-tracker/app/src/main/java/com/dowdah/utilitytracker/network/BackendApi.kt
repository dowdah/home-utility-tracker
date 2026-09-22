package com.dowdah.utilitytracker.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming
import okhttp3.ResponseBody

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    @SerialName("writes_enabled") val writesEnabled: Boolean,
)

@Serializable
data class MeterDto(
    val id: String,
    @SerialName("meter_type") val meterType: String,
    val generation: Int,
    val unit: String,
    val active: Boolean,
    @SerialName("created_revision") val createdRevision: Long,
    val deleted: Boolean,
)

@Serializable
data class MetaResponse(
    @SerialName("backend_instance_id") val backendInstanceId: String,
    @SerialName("current_revision") val currentRevision: Long,
    val meters: List<MeterDto>,
)

@Serializable
data class MutationDto(
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val kind: String,
    @SerialName("base_revision") val baseRevision: Long,
    val payload: JsonElement,
)

@Serializable
data class SyncRequestDto(
    @SerialName("backend_instance_id") val backendInstanceId: String,
    @SerialName("cursor_revision") val cursorRevision: Long,
    @SerialName("device_id") val deviceId: String,
    val mutations: List<MutationDto>,
    @SerialName("pull_limit") val pullLimit: Int = 500,
)

@Serializable
data class EntityDto(
    @SerialName("entity_type") val entityType: String,
    val id: String,
    @SerialName("server_revision") val serverRevision: Long? = null,
    val deleted: Boolean = false,
    @SerialName("meter_id") val meterId: String? = null,
    @SerialName("value_decimal") val valueDecimal: String? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
    val note: String? = null,
    @SerialName("price_decimal") val priceDecimal: String? = null,
    val currency: String? = null,
    @SerialName("effective_from") val effectiveFrom: String? = null,
)

@Serializable
data class MutationResultDto(
    @SerialName("operation_id") val operationId: String,
    val status: String,
    val reason: String? = null,
    val entity: EntityDto? = null,
)

@Serializable
data class ChangeDto(val revision: Long, val entity: JsonObject)

@Serializable
data class SyncResponseDto(
    val results: List<MutationResultDto>,
    val changes: List<ChangeDto>,
    @SerialName("next_cursor_revision") val nextCursorRevision: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("high_water_revision") val highWaterRevision: Long,
)

interface BackendApi {
    @GET("healthz") suspend fun health(): HealthResponse
    @GET("api/v1/meta") suspend fun meta(): MetaResponse
    @POST("api/v1/sync") suspend fun sync(@Body request: SyncRequestDto): SyncResponseDto
    @Streaming @GET("api/v1/exports/readings.csv") suspend fun exportReadings(): ResponseBody
}
