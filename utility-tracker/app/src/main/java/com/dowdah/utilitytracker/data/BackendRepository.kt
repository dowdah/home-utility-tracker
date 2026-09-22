package com.dowdah.utilitytracker.data

import androidx.room.withTransaction
import com.dowdah.utilitytracker.network.BackendApi
import com.dowdah.utilitytracker.network.EntityDto
import com.dowdah.utilitytracker.network.MetaResponse
import com.dowdah.utilitytracker.network.MutationDto
import com.dowdah.utilitytracker.network.SyncRequestDto
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.HttpException
import java.io.IOException

sealed interface SyncResult {
    data object Success : SyncResult
    /** The server version and local draft were persisted; resolve it from the conflict center. */
    data object ConflictDetected : SyncResult
    data class Retryable(val detail: String) : SyncResult
    data class ActionRequired(val detail: String) : SyncResult
}

data class EndpointHealth(
    val healthy: Boolean,
    val detail: String,
    val latencyMs: Long? = null,
)

class EndpointValidationException(message: String) : IllegalArgumentException(message)

@Singleton
class BackendRepository @Inject constructor(
    private val database: UtilityDatabase,
    private val secretStore: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val endpoints: Flow<List<EndpointEntity>> = database.endpointDao().observeAll()
    val meters: Flow<List<MeterEntity>> = database.meterDao().observeActive()
    val readings: Flow<List<ReadingEntity>> = database.readingDao().observeActive()
    val tariffs: Flow<List<TariffEntity>> = database.tariffDao().observeActive()
    val conflicts: Flow<List<ConflictEntity>> = database.conflictDao().observeAll()
    val syncState: Flow<SyncStateEntity?> = database.syncStateDao().observe()
    val pendingCount: Flow<Int> = database.outboxDao().observeCount()

    fun dashboard(): Flow<DashboardData> = combine(meters, readings, syncState, pendingCount) { meterList, readingList, state, pending ->
        DashboardData(meterList, readingList, state, pending)
    }

    suspend fun saveEndpoint(id: String?, label: String, rawUrl: String) {
        val url = normalizeEndpointUrl(rawUrl)
        val health = checkHealth(url)
        if (!health.healthy) throw EndpointValidationException(health.detail)
        val duplicate = database.endpointDao().all().firstOrNull { it.baseUrl == url && it.id != id }
        if (duplicate != null) throw EndpointValidationException("This URL already exists")
        val previous = id?.let { database.endpointDao().byId(it) }
        database.endpointDao().upsert(
            EndpointEntity(
                id = previous?.id ?: UUID.randomUUID().toString(),
                label = label.trim().ifBlank { url }, baseUrl = url, active = previous?.active ?: false,
                healthStatus = "HEALTHY", healthDetail = health.detail,
                healthCheckedAt = System.currentTimeMillis(), healthLatencyMs = health.latencyMs,
            ),
        )
    }

    suspend fun refreshHealth() {
        database.endpointDao().all().forEach { endpoint ->
            val health = checkHealth(endpoint.baseUrl)
            database.endpointDao().upsert(endpoint.copy(
                healthStatus = if (health.healthy) "HEALTHY" else "UNAVAILABLE",
                healthDetail = health.detail, healthCheckedAt = System.currentTimeMillis(),
                healthLatencyMs = health.latencyMs,
            ))
        }
    }

    suspend fun enableEndpoint(id: String) {
        val endpoint = requireNotNull(database.endpointDao().byId(id))
        val health = checkHealth(endpoint.baseUrl)
        if (!health.healthy) throw EndpointValidationException(health.detail)
        val token = secretStore.token() ?: throw EndpointValidationException("Save a token before enabling an endpoint")
        val meta = client(endpoint.baseUrl, token).meta()
        val state = database.syncStateDao().current()
        if (state?.backendInstanceId != null && state.backendInstanceId != meta.backendInstanceId) {
            throw EndpointValidationException("This URL belongs to a different backend instance")
        }
        database.withTransaction {
            database.endpointDao().setOnlyActive(id)
            mergeMeta(meta)
            database.syncStateDao().upsert((state ?: SyncStateEntity()).copy(backendInstanceId = meta.backendInstanceId))
        }
    }

    suspend fun deleteEndpoint(id: String) = database.endpointDao().delete(id)
    fun tokenPresent(): Boolean = secretStore.token() != null
    fun saveToken(token: String) = secretStore.saveToken(token.trim())
    fun clearToken() = secretStore.clearToken()

    suspend fun saveReading(id: String? = null, meterId: String, value: String, recordedAt: String, note: String?) {
        val normalized = canonicalDecimal(value)
        val existing = id?.let { database.readingDao().byId(it) }
        val entityId = existing?.id ?: UUID.randomUUID().toString()
        val payload = readingPayload(meterId, normalized, recordedAt, note)
        database.withTransaction {
            database.readingDao().upsert(ReadingEntity(entityId, meterId, normalized, recordedAt, note, false, existing?.serverRevision ?: 0))
            database.outboxDao().deleteForEntity("reading", entityId)
            database.outboxDao().upsert(OutboxEntity(
                entityType = "reading", entityId = entityId, kind = "upsert", baseRevision = existing?.serverRevision ?: 0,
                payloadJson = payload.toString(), createdAt = Instant.now().toString(),
            ))
        }
    }

    suspend fun deleteReading(reading: ReadingEntity) = database.withTransaction {
        database.outboxDao().deleteForEntity("reading", reading.id)
        if (reading.serverRevision == 0L) database.readingDao().delete(reading.id)
        else {
            database.readingDao().upsert(reading.copy(deleted = true))
            database.outboxDao().upsert(OutboxEntity(entityType = "reading", entityId = reading.id, kind = "tombstone", baseRevision = reading.serverRevision, payloadJson = "{}", createdAt = Instant.now().toString()))
        }
    }

    suspend fun saveTariff(id: String? = null, meterId: String, price: String, effectiveFrom: String) {
        val normalized = canonicalDecimal(price)
        val existing = id?.let { database.tariffDao().byId(it) }
        val entityId = existing?.id ?: UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("meter_id", meterId); put("price_decimal", normalized); put("currency", "CNY"); put("effective_from", effectiveFrom)
        }
        database.withTransaction {
            database.tariffDao().upsert(TariffEntity(entityId, meterId, normalized, "CNY", effectiveFrom, false, existing?.serverRevision ?: 0))
            database.outboxDao().deleteForEntity("tariff", entityId)
            database.outboxDao().upsert(OutboxEntity(entityType = "tariff", entityId = entityId, kind = "upsert", baseRevision = existing?.serverRevision ?: 0, payloadJson = payload.toString(), createdAt = Instant.now().toString()))
        }
    }

    suspend fun deleteTariff(tariff: TariffEntity) = database.withTransaction {
        database.outboxDao().deleteForEntity("tariff", tariff.id)
        if (tariff.serverRevision == 0L) database.tariffDao().delete(tariff.id)
        else {
            database.tariffDao().upsert(tariff.copy(deleted = true))
            database.outboxDao().upsert(OutboxEntity(entityType = "tariff", entityId = tariff.id, kind = "tombstone", baseRevision = tariff.serverRevision, payloadJson = "{}", createdAt = Instant.now().toString()))
        }
    }

    suspend fun keepServerConflict(conflict: ConflictEntity) = database.withTransaction {
        conflict.serverEntityJson?.let { applyEntity(json.decodeFromString(EntityDto.serializer(), it)) }
        database.outboxDao().deleteForEntity(conflict.entityType, conflict.entityId)
        // A later server version supersedes any earlier conflict for the same entity.
        database.conflictDao().deleteForEntity(conflict.entityType, conflict.entityId)
    }

    suspend fun overrideConflict(conflict: ConflictEntity) = database.withTransaction {
        val server = conflict.serverEntityJson?.let { json.decodeFromString(EntityDto.serializer(), it) }
            ?: throw IllegalStateException("Conflict has no server version")
        val kind = if (conflict.localPayloadJson == "{}") "tombstone" else "upsert"
        val payload = json.parseToJsonElement(conflict.localPayloadJson)
        if (kind == "upsert") applyLocalDraft(conflict.entityType, conflict.entityId, payload.jsonObject, server.serverRevision ?: 0)
        else applyEntity(server.copy(deleted = true))
        database.outboxDao().deleteForEntity(conflict.entityType, conflict.entityId)
        database.outboxDao().upsert(OutboxEntity(entityType = conflict.entityType, entityId = conflict.entityId, kind = kind, baseRevision = server.serverRevision ?: 0, payloadJson = conflict.localPayloadJson, createdAt = Instant.now().toString()))
        database.conflictDao().deleteForEntity(conflict.entityType, conflict.entityId)
    }

    suspend fun sync(): SyncResult = try {
        val endpoint = database.endpointDao().active() ?: return syncActionRequired("No active endpoint")
        val token = secretStore.token() ?: return syncActionRequired("No token configured")
        val api = client(endpoint.baseUrl, token)
        val meta = api.meta()
        val current = database.syncStateDao().current() ?: SyncStateEntity()
        if (current.backendInstanceId != null && current.backendInstanceId != meta.backendInstanceId) {
            throw EndpointValidationException("Active endpoint identity changed")
        }
        database.withTransaction { mergeMeta(meta) }
        var cursor = current.cursorRevision
        var conflictDetected = false
        var hasMore: Boolean
        do {
            val operations = database.outboxDao().next(100)
            val response = api.sync(SyncRequestDto(
                backendInstanceId = meta.backendInstanceId, cursorRevision = cursor,
                deviceId = installationId(), mutations = operations.map { it.toDto() },
            ))
            database.withTransaction {
                response.results.forEach { result ->
                    when (result.status) {
                        "accepted", "duplicate" -> database.outboxDao().delete(result.operationId)
                        "conflict" -> operations.firstOrNull { it.operationId == result.operationId }?.let { local ->
                            database.conflictDao().deleteForEntity(local.entityType, local.entityId)
                            database.conflictDao().upsert(ConflictEntity(
                                result.operationId, local.entityType, local.entityId, local.payloadJson,
                                result.entity?.let { json.encodeToString(com.dowdah.utilitytracker.network.EntityDto.serializer(), it) }, Instant.now().toString(),
                            ))
                            // A conflict is no longer a retryable mutation. The user can explicitly
                            // keep the server version or create a fresh override operation.
                            database.outboxDao().delete(result.operationId)
                            conflictDetected = true
                        }
                    }
                }
                response.changes.forEach { applyChange(it.entity) }
                database.syncStateDao().upsert(SyncStateEntity(
                    backendInstanceId = meta.backendInstanceId, cursorRevision = response.nextCursorRevision,
                    lastSuccessAt = System.currentTimeMillis(),
                    lastError = if (conflictDetected) "Conflict needs resolution" else null,
                ))
            }
            cursor = response.nextCursorRevision
            hasMore = response.hasMore
        } while (hasMore)
        if (conflictDetected) SyncResult.ConflictDetected else SyncResult.Success
    } catch (error: HttpException) {
        val detail = when (error.code()) {
            401 -> "Token is invalid or revoked"
            409 -> "Backend identity changed"
            422 -> "A pending change is invalid"
            507 -> "Server writes are temporarily disabled"
            else -> "Server error (${error.code()})"
        }
        if (error.code() >= 500 && error.code() != 507) syncRetryable(detail) else syncActionRequired(detail)
    } catch (error: IOException) {
        syncRetryable("Network connection failed: ${error.javaClass.simpleName}")
    } catch (error: EndpointValidationException) {
        syncActionRequired(error.message ?: "Sync needs attention")
    }

    private suspend fun syncRetryable(detail: String): SyncResult {
        database.syncStateDao().upsert((database.syncStateDao().current() ?: SyncStateEntity()).copy(lastError = detail))
        return SyncResult.Retryable(detail)
    }

    private suspend fun syncActionRequired(detail: String): SyncResult {
        database.syncStateDao().upsert((database.syncStateDao().current() ?: SyncStateEntity()).copy(lastError = detail))
        return SyncResult.ActionRequired(detail)
    }

    private suspend fun mergeMeta(meta: MetaResponse) {
        database.meterDao().upsertAll(meta.meters.map { MeterEntity(it.id, it.meterType, it.generation, it.unit, it.active, it.deleted, it.createdRevision) })
    }

    private suspend fun applyEntity(entity: EntityDto) {
        when (entity.entityType) {
            "reading" -> if (entity.meterId != null && entity.valueDecimal != null && entity.recordedAt != null) database.readingDao().upsert(
                ReadingEntity(entity.id, entity.meterId, entity.valueDecimal, entity.recordedAt, entity.note, entity.deleted, entity.serverRevision ?: 0),
            )
            "tariff" -> if (entity.meterId != null && entity.priceDecimal != null && entity.effectiveFrom != null) database.tariffDao().upsert(
                TariffEntity(entity.id, entity.meterId, entity.priceDecimal, entity.currency ?: "CNY", entity.effectiveFrom, entity.deleted, entity.serverRevision ?: 0),
            )
        }
    }

    private suspend fun applyLocalDraft(type: String, id: String, payload: JsonObject, revision: Long) {
        when (type) {
            "reading" -> database.readingDao().upsert(ReadingEntity(id, payload["meter_id"]!!.jsonPrimitive.content, payload["value_decimal"]!!.jsonPrimitive.content, payload["recorded_at"]!!.jsonPrimitive.content, payload["note"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }, false, revision))
            "tariff" -> database.tariffDao().upsert(TariffEntity(id, payload["meter_id"]!!.jsonPrimitive.content, payload["price_decimal"]!!.jsonPrimitive.content, "CNY", payload["effective_from"]!!.jsonPrimitive.content, false, revision))
        }
    }

    suspend fun exportCsv(output: java.io.OutputStream) {
        val endpoint = database.endpointDao().active() ?: throw EndpointValidationException("No active endpoint")
        val token = secretStore.token() ?: throw EndpointValidationException("No token configured")
        val response = client(endpoint.baseUrl, token).exportReadings()
        response.byteStream().use { input -> input.copyTo(output) }
    }

    private fun readingPayload(meterId: String, value: String, recordedAt: String, note: String?) = buildJsonObject {
        put("meter_id", meterId); put("value_decimal", value); put("recorded_at", recordedAt)
        if (note == null) put("note", kotlinx.serialization.json.JsonNull) else put("note", note)
    }

    /** The initial backend migration emits meter changes without entity_type; infer that stable shape. */
    private suspend fun applyChange(entity: JsonObject) {
        val type = entity["entity_type"]?.jsonPrimitive?.content
            ?: if (entity.containsKey("meter_type")) "meter" else return
        if (type == "meter") {
            val id = entity["id"]?.jsonPrimitive?.content ?: return
            val meterType = entity["meter_type"]?.jsonPrimitive?.content ?: return
            val generation = entity["generation"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
            val unit = entity["unit"]?.jsonPrimitive?.content ?: return
            val active = entity["active"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val deleted = entity["deleted"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val revision = entity["created_revision"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            database.meterDao().upsertAll(listOf(MeterEntity(id, meterType, generation, unit, active, deleted, revision)))
        } else {
            applyEntity(json.decodeFromJsonElement(com.dowdah.utilitytracker.network.EntityDto.serializer(), entity))
        }
    }

    private fun OutboxEntity.toDto() = MutationDto(operationId, entityType, entityId, kind, baseRevision, json.parseToJsonElement(payloadJson))

    private fun client(url: String, token: String? = null): BackendApi {
        val http = OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
            val request: Request = chain.request().newBuilder().apply {
                if (token != null) header("Authorization", "Bearer $token")
            }.build()
            chain.proceed(request)
        }.build()
        return Retrofit.Builder().baseUrl("$url/").client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BackendApi::class.java)
    }

    private suspend fun checkHealth(url: String): EndpointHealth {
        val started = System.nanoTime()
        return try {
            val response = client(url).health()
            val latency = (System.nanoTime() - started) / 1_000_000
            if (response.status == "ok" && response.database == "open" && response.writesEnabled) EndpointHealth(true, "Healthy", latency)
            else EndpointHealth(false, "Server is not accepting writes", latency)
        } catch (_: Exception) {
            EndpointHealth(false, "Cannot reach a healthy Utility Sync server")
        }
    }

    private fun canonicalDecimal(value: String): String {
        val decimal = value.toBigDecimalOrNull() ?: throw IllegalArgumentException("Reading must be a decimal")
        require(decimal >= BigDecimal.ZERO) { "Reading cannot be negative" }
        return decimal.stripTrailingZeros().toPlainString().ifBlank { "0" }
    }

    private fun installationId(): String = secretStore.installationId()
}

internal fun normalizeEndpointUrl(raw: String): String = try {
    val uri = URI(raw.trim()).normalize()
    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(uri.path.isNullOrEmpty() || uri.path == "/")
    uri.toString().removeSuffix("/")
} catch (_: Exception) { throw EndpointValidationException("Enter an absolute HTTP or HTTPS base URL") }

data class DashboardData(val meters: List<MeterEntity>, val readings: List<ReadingEntity>, val state: SyncStateEntity?, val pendingCount: Int)
