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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val syncMutex = Mutex()
    val endpoints: Flow<List<EndpointEntity>> = database.endpointDao().observeAll()
    val meters: Flow<List<MeterEntity>> = database.meterDao().observeActive()
    val readings: Flow<List<ReadingEntity>> = database.readingDao().observeActive()
    val recharges: Flow<List<RechargeEntity>> = database.rechargeDao().observeActive()
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

    private suspend fun enqueue(type: String, id: String, kind: String, revision: Long, payload: JsonObject,
                                groupId: String? = null, groupSize: Int? = null) {
        val conflict = database.conflictDao().forEntity(type, id)
        if (conflict != null) {
            database.conflictDao().upsert(conflict.copy(localPayloadJson = payload.toString()))
            return
        }
        val previous = database.outboxDao().forEntity(type, id).lastOrNull()
        database.outboxDao().upsert(OutboxEntity(entityType = type, entityId = id, kind = kind,
            baseRevision = revision, payloadJson = payload.toString(), createdAt = Instant.now().toString(),
            previousOperationId = previous?.operationId, groupId = groupId, groupSize = groupSize))
    }

    private fun validateInput(meterId: String, timestamp: String, note: String? = null) {
        require(meterId.isNotBlank()) { "Select a meter" }
        Instant.parse(timestamp)
        require((note?.length ?: 0) <= 1000) { "Note must be at most 1000 characters" }
    }

    suspend fun saveReading(id: String? = null, meterId: String, value: String, recordedAt: String, note: String?) = database.withTransaction {
        validateInput(meterId, recordedAt, note)
        val normalized = canonicalDecimal(value)
        val existing = id?.let { database.readingDao().byId(it) }
        val entityId = existing?.id ?: UUID.randomUUID().toString()
        database.readingDao().upsert(ReadingEntity(entityId, meterId, normalized, recordedAt, note, false, existing?.serverRevision ?: 0))
        enqueue("reading", entityId, "upsert", existing?.serverRevision ?: 0, readingPayload(meterId, normalized, recordedAt, note))
    }

    suspend fun deleteReading(reading: ReadingEntity) = database.withTransaction {
        val current = database.readingDao().byId(reading.id) ?: return@withTransaction
        database.readingDao().upsert(current.copy(deleted = true))
        enqueue("reading", current.id, "tombstone", current.serverRevision, buildJsonObject {})
    }

    suspend fun saveTariff(id: String? = null, meterId: String, price: String, effectiveFrom: String) = database.withTransaction {
        validateInput(meterId, effectiveFrom)
        val normalized = canonicalDecimal(price)
        val existing = id?.let { database.tariffDao().byId(it) }
        val entityId = existing?.id ?: UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("meter_id", meterId); put("price_decimal", normalized); put("currency", "CNY"); put("effective_from", effectiveFrom)
        }
        database.tariffDao().upsert(TariffEntity(entityId, meterId, normalized, "CNY", effectiveFrom, false, existing?.serverRevision ?: 0))
        enqueue("tariff", entityId, "upsert", existing?.serverRevision ?: 0, payload)
    }

    suspend fun deleteTariff(tariff: TariffEntity) = database.withTransaction {
        val current = database.tariffDao().byId(tariff.id) ?: return@withTransaction
        database.tariffDao().upsert(current.copy(deleted = true))
        enqueue("tariff", current.id, "tombstone", current.serverRevision, buildJsonObject {})
    }

    suspend fun saveRecharge(id: String? = null, meterId: String, amount: String, price: String,
                             creditedAt: String, note: String?, remaining: String? = null) = database.withTransaction {
        validateInput(meterId, creditedAt, note)
        val quantity = rechargeQuantity(amount, price).toPlainString()
        val existing = id?.let { database.rechargeDao().byId(it) }
        val entityId = existing?.id ?: UUID.randomUUID().toString()
        val group = if (existing == null && !remaining.isNullOrBlank()) UUID.randomUUID().toString() else null
        if (group != null) require(database.meterDao().active().any { it.id == meterId && it.meterType == "ELECTRICITY" })
        val payload = buildJsonObject {
            put("meter_id", meterId); put("amount_decimal", canonicalDecimal(amount)); put("unit_price_decimal", canonicalDecimal(price))
            put("quantity_decimal", quantity); put("currency", "CNY"); put("credited_at", creditedAt); put("note", note)
        }
        applyLocalDraft("recharge", entityId, payload, existing?.serverRevision ?: 0)
        enqueue("recharge", entityId, "upsert", existing?.serverRevision ?: 0, payload, group, group?.let { 2 })
        if (group != null) {
            val readingId = UUID.randomUUID().toString()
            val reading = readingPayload(meterId, canonicalDecimal(requireNotNull(remaining)), creditedAt, note)
            applyLocalDraft("reading", readingId, reading, 0)
            enqueue("reading", readingId, "upsert", 0, reading, group, 2)
        }
    }

    suspend fun deleteRecharge(recharge: RechargeEntity) = database.withTransaction {
        val current = database.rechargeDao().byId(recharge.id) ?: return@withTransaction
        database.rechargeDao().upsert(current.copy(deleted = true))
        enqueue("recharge", current.id, "tombstone", current.serverRevision, buildJsonObject {})
    }

    private suspend fun conflictGroup(conflict: ConflictEntity) = database.conflictDao().all().filter {
        if (conflict.groupId == null) it.operationId == conflict.operationId else it.groupId == conflict.groupId
    }

    suspend fun keepServerConflict(conflict: ConflictEntity) = syncMutex.withLock {
        database.withTransaction {
            conflictGroup(conflict).forEach { item ->
                database.outboxDao().deleteForEntity(item.entityType, item.entityId)
                database.conflictDao().deleteForEntity(item.entityType, item.entityId)
                val server = item.serverEntityJson?.let { json.decodeFromString(EntityDto.serializer(), it) }
                if (server != null) applyEntity(server, force = true) else removeLocal(item.entityType, item.entityId)
            }
        }
    }

    suspend fun overrideConflict(conflict: ConflictEntity) = syncMutex.withLock {
        database.withTransaction {
            val members = conflictGroup(conflict)
            val actionable = members.filter { it.localPayloadJson != "{}" || it.serverEntityJson != null }
            val group = if (actionable.size > 1) UUID.randomUUID().toString() else null
            members.forEach { item ->
                val server = item.serverEntityJson?.let { json.decodeFromString(EntityDto.serializer(), it) }
                val revision = server?.serverRevision ?: 0
                val kind = if (item.localPayloadJson == "{}") "tombstone" else "upsert"
                database.outboxDao().deleteForEntity(item.entityType, item.entityId)
                database.conflictDao().deleteForEntity(item.entityType, item.entityId)
                if (item in actionable) {
                    val payload = json.parseToJsonElement(item.localPayloadJson).jsonObject
                    if (kind == "upsert") applyLocalDraft(item.entityType, item.entityId, payload, revision)
                    else applyEntity(requireNotNull(server).copy(deleted = true), force = true)
                    enqueue(item.entityType, item.entityId, kind, revision, payload, group, group?.let { actionable.size })
                } else removeLocal(item.entityType, item.entityId)
            }
        }
    }

    private suspend fun removeLocal(type: String, id: String) {
        when (type) {
            "reading" -> database.readingDao().delete(id)
            "tariff" -> database.tariffDao().delete(id)
            "recharge" -> database.rechargeDao().delete(id)
        }
    }

    /** Take complete groups and only the ready head of each entity queue. */
    private suspend fun readyOperations(): List<OutboxEntity> {
        val all = database.outboxDao().all()
        val ready = all.filter { it.previousOperationId == null }
        val selected = mutableListOf<OutboxEntity>()
        ready.forEach { head ->
            if (selected.none { it.operationId == head.operationId }) {
                val group = if (head.groupId == null) listOf(head) else all.filter { it.groupId == head.groupId }
                if (group.all { it.previousOperationId == null } && group.size == (head.groupSize ?: 1) && selected.size + group.size <= 100) selected.addAll(group)
            }
        }
        return selected
    }

    suspend fun sync(): SyncResult = syncMutex.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncResult { return try {
        val endpoint = database.endpointDao().active() ?: return syncActionRequired("No active endpoint")
        val token = secretStore.token() ?: return syncActionRequired("No token configured")
        val api = client(endpoint.baseUrl, token)
        val meta = api.meta()
        if (meta.syncProtocolVersion < 2 || meta.minSyncProtocolVersion > 2) return syncActionRequired("Server or app upgrade required")
        val current = database.syncStateDao().current() ?: SyncStateEntity()
        if (current.backendInstanceId != null && current.backendInstanceId != meta.backendInstanceId) throw EndpointValidationException("Active endpoint identity changed")
        database.withTransaction { mergeMeta(meta) }
        var cursor = current.cursorRevision
        var readOnly = false
        var hasMore: Boolean
        do {
            val operations = database.withTransaction {
                (if (readOnly) emptyList() else readyOperations()).also { database.outboxDao().markSent(it.map { row -> row.operationId }) }
            }
            val request = SyncRequestDto(backendInstanceId = meta.backendInstanceId, cursorRevision = cursor,
                deviceId = installationId(), mutations = operations.map { it.toDto() })
            val response = try { api.sync(request) } catch (error: HttpException) {
                if (error.code() == 507) { readOnly = true; api.sync(request.copy(mutations = emptyList())) } else throw error
            }
            require(response.nextCursorRevision >= cursor) { "Server returned a backwards cursor" }
            database.withTransaction {
                response.results.forEach { result ->
                    val sent = operations.firstOrNull { it.operationId == result.operationId } ?: error("Unknown acknowledgement")
                    when (result.status) {
                        "accepted", "duplicate" -> {
                            val entity = requireNotNull(result.entity)
                            database.outboxDao().delete(result.operationId)
                            database.outboxDao().acknowledge(result.operationId, requireNotNull(entity.serverRevision))
                            applyEntity(entity)
                        }
                        "conflict" -> if (result.reason != "batch_aborted") {
                            val latest = database.outboxDao().forEntity(sent.entityType, sent.entityId).lastOrNull() ?: sent
                            database.conflictDao().deleteForEntity(sent.entityType, sent.entityId)
                            database.conflictDao().upsert(ConflictEntity(operationId = sent.operationId, entityType = sent.entityType,
                                entityId = sent.entityId, localPayloadJson = latest.payloadJson,
                                serverEntityJson = result.entity?.let { json.encodeToString(EntityDto.serializer(), it) },
                                createdAt = Instant.now().toString(), groupId = sent.groupId))
                            database.outboxDao().deleteForEntity(sent.entityType, sent.entityId)
                        }
                        else -> error("Unknown acknowledgement status")
                    }
                }
                response.changes.forEach { applyChange(it.entity) }
                val conflictsRemain = database.conflictDao().all().isNotEmpty()
                database.syncStateDao().upsert((database.syncStateDao().current() ?: SyncStateEntity()).copy(
                    backendInstanceId = meta.backendInstanceId, cursorRevision = response.nextCursorRevision,
                    lastSuccessAt = System.currentTimeMillis(), lastError = when {
                        readOnly -> "Server writes are temporarily disabled"
                        conflictsRemain -> "Conflict needs resolution"
                        else -> null
                    }))
            }
            cursor = response.nextCursorRevision
            hasMore = response.hasMore || (!readOnly && readyOperations().isNotEmpty())
        } while (hasMore)
        try {
            val status = api.status()
            database.withTransaction {
                database.syncStateDao().upsert((database.syncStateDao().current() ?: SyncStateEntity()).copy(serverStatusJson = status.toString()))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Last observed status remains visible with its timestamp. */ }
        when {
            readOnly -> SyncResult.ActionRequired("Server writes are temporarily disabled")
            database.conflictDao().all().isNotEmpty() -> SyncResult.ConflictDetected
            else -> SyncResult.Success
        }
    } catch (error: HttpException) {
        val detail = when (error.code()) {
            401, 403 -> "Token is invalid or revoked"
            409 -> "Backend identity or revision changed"
            422 -> "A pending change is invalid"
            426 -> "Server or app upgrade required"
            507 -> "Server writes are temporarily disabled"
            else -> "Server error (${error.code()})"
        }
        if (error.code() >= 500 && error.code() != 507) syncRetryable(detail) else syncActionRequired(detail)
    } catch (error: IOException) {
        syncRetryable("Network connection failed: ${error.javaClass.simpleName}")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { syncActionRequired(error.message ?: "Sync needs attention") }
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

    private suspend fun applyEntity(entity: EntityDto, force: Boolean = false) {
        val conflict = database.conflictDao().forEntity(entity.entityType, entity.id)
        if (conflict != null && !force) {
            val previousRevision = conflict.serverEntityJson?.let { json.decodeFromString(EntityDto.serializer(), it).serverRevision } ?: 0
            if ((entity.serverRevision ?: 0) >= previousRevision) database.conflictDao().upsert(conflict.copy(serverEntityJson = json.encodeToString(EntityDto.serializer(), entity)))
            return
        }
        if (!force && database.outboxDao().forEntity(entity.entityType, entity.id).isNotEmpty()) return
        val existingRevision = when (entity.entityType) {
            "reading" -> database.readingDao().byId(entity.id)?.serverRevision
            "tariff" -> database.tariffDao().byId(entity.id)?.serverRevision
            "recharge" -> database.rechargeDao().byId(entity.id)?.serverRevision
            else -> error("Unsupported entity type")
        } ?: 0
        if (!force && (entity.serverRevision ?: 0) < existingRevision) return
        val revision = requireNotNull(entity.serverRevision)
        when (entity.entityType) {
            "reading" -> database.readingDao().upsert(ReadingEntity(entity.id, requireNotNull(entity.meterId), requireNotNull(entity.valueDecimal), requireNotNull(entity.recordedAt), entity.note, entity.deleted, revision))
            "tariff" -> database.tariffDao().upsert(TariffEntity(entity.id, requireNotNull(entity.meterId), requireNotNull(entity.priceDecimal), entity.currency ?: "CNY", requireNotNull(entity.effectiveFrom), entity.deleted, revision))
            "recharge" -> database.rechargeDao().upsert(RechargeEntity(entity.id, requireNotNull(entity.meterId), requireNotNull(entity.amountDecimal), requireNotNull(entity.unitPriceDecimal), requireNotNull(entity.quantityDecimal), entity.currency ?: "CNY", requireNotNull(entity.creditedAt), entity.note, entity.deleted, revision))
        }
    }

    private suspend fun applyLocalDraft(type: String, id: String, payload: JsonObject, revision: Long) {
        fun field(key: String) = requireNotNull(payload[key]).jsonPrimitive.content
        val note = payload["note"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
        when (type) {
            "reading" -> database.readingDao().upsert(ReadingEntity(id, field("meter_id"), field("value_decimal"), field("recorded_at"), note, false, revision))
            "tariff" -> database.tariffDao().upsert(TariffEntity(id, field("meter_id"), field("price_decimal"), "CNY", field("effective_from"), false, revision))
            "recharge" -> database.rechargeDao().upsert(RechargeEntity(id, field("meter_id"), field("amount_decimal"), field("unit_price_decimal"), field("quantity_decimal"), "CNY", field("credited_at"), note, false, revision))
        }
    }

    suspend fun exportCsv(output: java.io.OutputStream, kind: String = "readings"): Unit = withContext(Dispatchers.IO) {
        val endpoint = database.endpointDao().active() ?: throw EndpointValidationException("No active endpoint")
        val token = secretStore.token() ?: throw EndpointValidationException("No token configured")
        val response = client(endpoint.baseUrl, token).exportLedger(kind)
        response.byteStream().use { input -> input.copyTo(output) }
        Unit
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

    private fun OutboxEntity.toDto() = MutationDto(operationId, entityType, entityId, kind, baseRevision, json.parseToJsonElement(payloadJson), groupId, groupSize)

    private fun client(url: String, token: String? = null): BackendApi {
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
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
            if (response.status == "ok" && response.database == "open") EndpointHealth(true, "Healthy", latency)
            else EndpointHealth(false, "Server is not accepting writes", latency)
        } catch (_: Exception) {
            EndpointHealth(false, "Cannot reach a healthy Utility Sync server")
        }
    }

    private fun canonicalDecimal(value: String): String {
        require(value.length <= 128) { "Number exceeds supported precision" }
        val decimal = value.toBigDecimalOrNull() ?: throw IllegalArgumentException("Reading must be a decimal")
        require(decimal.scale() <= 24 && decimal.precision() - decimal.scale() <= 25) { "Number exceeds supported precision" }
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
