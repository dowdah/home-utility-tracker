package com.dowdah.utilitytracker.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Room + HTTP contract tests, isolated from the installed user's database and token. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.MediumTest
class LedgerRepositoryTest {
    private lateinit var db: UtilityDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: BackendRepository
    private lateinit var secrets: SecretStore
    private lateinit var ledger: LedgerDispatcher
    private val meterId = UUID.randomUUID().toString()
    private val backendId = UUID.randomUUID().toString()
    private val at = "2026-09-22T12:00:00Z"

    @Before fun setup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, UtilityDatabase::class.java).build()
        secrets = SecretStore(object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("test_$name", mode)
        })
        secrets.saveToken("isolated-test-token")
        server = MockWebServer()
        ledger = LedgerDispatcher()
        server.dispatcher = ledger
        server.start()
        db.endpointDao().upsert(EndpointEntity(label = "test", baseUrl = server.url("/").toString().removeSuffix("/"), active = true))
        db.meterDao().upsertAll(listOf(MeterEntity(meterId, "ELECTRICITY", 1, "kWh", true, false, 1)))
        db.syncStateDao().upsert(SyncStateEntity(backendInstanceId = backendId))
        repository = BackendRepository(db, secrets)
    }
    @After fun teardown() { server.shutdown(); db.close(); secrets.clearToken() }

    @Test fun queuedEditsAndMoreThanOneHundredMutationsDrain() = runBlocking {
        repeat(105) { repository.saveReading(meterId = meterId, value = "$it", recordedAt = at, note = null) }
        val id = db.readingDao().active().first().id
        repository.saveReading(id, meterId, "200", at, null)
        assertEquals(SyncResult.Success, repository.sync())
        assertTrue(db.outboxDao().all().isEmpty())
        assertEquals("200", db.readingDao().byId(id)!!.valueDecimal)
        assertEquals(105, ledger.entities.size)
    }

    @Test fun batchAbortedRemainsQueuedWhileRealConflictKeepsLatestDraft() = runBlocking {
        repository.saveReading(meterId = meterId, value = "100", recordedAt = at, note = null)
        repository.sync()
        val original = db.readingDao().active().single()
        ledger.remoteEdit(original.id, "90")
        repository.saveReading(original.id, meterId, "80", at, null)
        repository.saveReading(original.id, meterId, "70", at, null)
        repository.saveReading(meterId = meterId, value = "25", recordedAt = at, note = null)
        assertEquals(SyncResult.ConflictDetected, repository.sync())
        assertTrue(db.outboxDao().all().isEmpty())
        val conflict = db.conflictDao().all().single()
        assertTrue(conflict.localPayloadJson.contains("70"))
        assertEquals(2, ledger.entities.size)
        repository.overrideConflict(conflict)
        assertEquals(SyncResult.Success, repository.sync())
        assertEquals("70", db.readingDao().byId(original.id)!!.valueDecimal)
        ledger.remoteEdit(original.id, "65")
        repository.saveReading(original.id, meterId, "60", at, null)
        repository.sync()
        ledger.remoteEdit(original.id, "62")
        repository.sync()
        repository.keepServerConflict(db.conflictDao().all().single())
        assertEquals("62", db.readingDao().byId(original.id)!!.valueDecimal)
    }

    @Test fun editWhileRequestInFlightDoesNotLoseNewerDraftAndSyncIsSerialized() = runBlocking {
        repository.saveReading(meterId = meterId, value = "100", recordedAt = at, note = null)
        val id = db.readingDao().active().single().id
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        ledger.beforeSync = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val first = async(Dispatchers.IO) { repository.sync() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        repository.saveReading(id, meterId, "80", at, null)
        val second = async(Dispatchers.IO) { repository.sync() }
        ledger.beforeSync = null
        release.countDown()
        assertEquals(SyncResult.Success, first.await()); assertEquals(SyncResult.Success, second.await())
        assertEquals("80", db.readingDao().byId(id)!!.valueDecimal)
        assertTrue(db.outboxDao().all().isEmpty())
        assertEquals(2, ledger.revision)
    }

    @Test fun droppedAcceptedResponseRetriesSameOperationWithoutDuplicateWrite() = runBlocking {
        repository.saveReading(meterId = meterId, value = "100", recordedAt = at, note = null)
        val operation = db.outboxDao().all().single().operationId
        ledger.dropNext = true
        val first = repository.sync()
        if (first is SyncResult.Retryable) assertEquals(operation, db.outboxDao().all().single().operationId)
        assertEquals(SyncResult.Success, repository.sync())
        assertEquals(1, ledger.revision)
        assertTrue(db.outboxDao().all().isEmpty())
    }

    @Test fun droppedConflictResponseStillCreatesVisibleConflict() = runBlocking {
        repository.saveReading(meterId = meterId, value = "100", recordedAt = at, note = null)
        repository.sync()
        val id = db.readingDao().active().single().id
        ledger.remoteEdit(id, "90")
        repository.saveReading(id, meterId, "80", at, null)
        ledger.dropNext = true
        repository.sync()
        assertEquals(SyncResult.ConflictDetected, repository.sync())
        assertTrue(db.conflictDao().all().single().localPayloadJson.contains("80"))
    }

    @Test fun rechargeAndPostCreditReadingAreSentTogetherAndDeletePropagates() = runBlocking {
        repository.saveRecharge(meterId = meterId, amount = "60", price = "0.6", creditedAt = at, note = null, remaining = "150")
        val pending = db.outboxDao().all()
        assertEquals(2, pending.size); assertEquals(pending[0].groupId, pending[1].groupId)
        assertEquals(SyncResult.Success, repository.sync())
        assertEquals(setOf("reading", "recharge"), ledger.entities.values.map { it["entity_type"]!!.jsonPrimitive.content }.toSet())
        val recharge = db.rechargeDao().observeActive().first().single()
        repository.deleteRecharge(recharge)
        repository.sync()
        assertTrue(db.rechargeDao().observeActive().first().isEmpty())
        assertEquals(1, db.readingDao().active().size)
    }

    @Test fun lowSpacePullsRemoteDataAndRetainsOutbox() = runBlocking {
        repository.saveReading(meterId = meterId, value = "100", recordedAt = at, note = null)
        ledger.lowSpace = true
        assertTrue(repository.sync() is SyncResult.ActionRequired)
        assertEquals(1, db.outboxDao().all().size)
        ledger.lowSpace = false
        assertEquals(SyncResult.Success, repository.sync())
    }

    @Test fun groupConflictWithMissingServerMemberCanBeOverriddenTogether() = runBlocking {
        repository.saveRecharge(meterId = meterId, amount = "60", price = "0.6", creditedAt = at, note = null, remaining = "150")
        ledger.remoteCreate(db.outboxDao().all().first { it.entityType == "recharge" })
        assertEquals(SyncResult.ConflictDetected, repository.sync())
        val conflicts = db.conflictDao().all()
        assertEquals(2, conflicts.size)
        assertEquals(1, conflicts.map { it.groupId }.distinct().size)
        repository.overrideConflict(conflicts.first())
        assertEquals(SyncResult.Success, repository.sync())
        assertEquals(2, ledger.entities.size)
        assertTrue(db.conflictDao().all().isEmpty())
    }

    private inner class LedgerDispatcher : Dispatcher() {
        val entities = linkedMapOf<String, JsonObject>()
        val operations = mutableMapOf<String, JsonObject>()
        val changes = mutableListOf<JsonObject>()
        var revision = 0
        @Volatile var beforeSync: (() -> Unit)? = null
        @Volatile var dropNext = false
        var lowSpace = false
        fun remoteCreate(operation: OutboxEntity) {
            revision++
            val entity = JsonObject(Json.parseToJsonElement(operation.payloadJson).jsonObject + mapOf("id" to JsonPrimitive(operation.entityId), "entity_type" to JsonPrimitive(operation.entityType), "deleted" to JsonPrimitive(false), "server_revision" to JsonPrimitive(revision)))
            entities[operation.entityId] = entity
            changes += buildJsonObject { put("revision", revision); put("entity", entity) }
        }
        fun remoteEdit(id: String, value: String) {
            revision++
            val entity = JsonObject(entities.getValue(id) + mapOf("value_decimal" to JsonPrimitive(value), "server_revision" to JsonPrimitive(revision)))
            entities[id] = entity; changes += buildJsonObject { put("revision", revision); put("entity", entity) }
        }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            if (path.endsWith("/meta")) return response(buildJsonObject {
                put("backend_instance_id", backendId); put("current_revision", revision); put("sync_protocol_version", 2); put("min_sync_protocol_version", 2)
                put("meters", buildJsonArray { add(buildJsonObject { put("id", meterId); put("meter_type", "ELECTRICITY"); put("generation", 1); put("unit", "kWh"); put("active", true); put("deleted", false); put("created_revision", 1) }) })
            })
            if (path.endsWith("/status")) return response(buildJsonObject { put("alerts", buildJsonArray {}) })
            if (!path.endsWith("/sync")) return MockResponse().setResponseCode(404)
            beforeSync?.invoke()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(2, body["client_protocol_version"]!!.jsonPrimitive.int)
            val mutations = body["mutations"]!!.jsonArray.map { it.jsonObject }
            if (lowSpace && mutations.isNotEmpty()) return MockResponse().setResponseCode(507)
            val directConflicts = mutations.filter { item ->
                val saved = operations[item.id()]
                if (saved != null) saved["status"]!!.jsonPrimitive.content == "conflict" else
                    item["base_revision"]!!.jsonPrimitive.long != (entities[item.entityId()]?.get("server_revision")?.jsonPrimitive?.long ?: 0L)
            }.map { it.id() }.toSet()
            val groupsInConflict = mutations.filter { it.id() in directConflicts }.mapNotNull { it["group_id"]?.jsonPrimitive?.content }.toSet()
            val conflictIds = directConflicts + mutations.filter { it["group_id"]?.jsonPrimitive?.content in groupsInConflict }.map { it.id() }
            val results = mutations.map { item ->
                val saved = operations[item.id()]
                if (saved != null) JsonObject(saved + ("status" to JsonPrimitive(if (saved["status"]!!.jsonPrimitive.content == "accepted") "duplicate" else "conflict")))
                else if (item.id() in conflictIds) buildJsonObject { put("operation_id", item.id()); put("status", "conflict"); put("entity", entities[item.entityId()] ?: JsonNull) }.also { operations[item.id()] = it }
                else if (conflictIds.isNotEmpty()) buildJsonObject { put("operation_id", item.id()); put("status", "conflict"); put("reason", "batch_aborted") }
                else {
                    revision++
                    val payload = if (item["kind"]!!.jsonPrimitive.content == "tombstone") entities.getValue(item.entityId()) else item["payload"]!!.jsonObject
                    val entity = JsonObject(payload + mapOf("id" to JsonPrimitive(item.entityId()), "entity_type" to item.getValue("entity_type"), "deleted" to JsonPrimitive(item["kind"]!!.jsonPrimitive.content == "tombstone"), "server_revision" to JsonPrimitive(revision)))
                    entities[item.entityId()] = entity
                    changes += buildJsonObject { put("revision", revision); put("entity", entity) }
                    buildJsonObject { put("operation_id", item.id()); put("status", "accepted"); put("entity", entity) }.also { operations[item.id()] = it }
                }
            }
            val cursor = body["cursor_revision"]!!.jsonPrimitive.int
            val value = buildJsonObject {
                put("results", JsonArray(results)); put("changes", JsonArray(changes.filter { it["revision"]!!.jsonPrimitive.int > cursor }))
                put("next_cursor_revision", revision); put("high_water_revision", revision); put("has_more", false)
            }
            if (dropNext) { dropNext = false; return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            return response(value)
        }
        fun response(value: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
        fun JsonObject.id() = getValue("operation_id").jsonPrimitive.content
        fun JsonObject.entityId() = getValue("entity_id").jsonPrimitive.content
    }
}
