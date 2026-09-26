package com.dowdah.utilitytracker.data

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.dowdah.utilitytracker.sync.SyncScheduler
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.*
import org.junit.Assert.*

/** Opt-in only. Credentials arrive from an ephemeral host-memory broker, never runner arguments. */
class LiveLedgerAcceptanceTest {
    @Test fun runStep() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val broker = args.getString("live_config_url").orEmpty()
        Assume.assumeTrue("Live acceptance is opt-in", broker.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.startsWith("com.dowdah.utilitytracker.acceptance"))
        val config = withContext(Dispatchers.IO) {
            OkHttpClient().newCall(Request.Builder().url(broker).build()).execute().use { response ->
                check(response.isSuccessful); Json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
            }
        }
        fun config(key: String) = config.getValue(key).jsonPrimitive.content
        val entry = EntryPointAccessors.fromApplication(context, LiveLedgerEntryPoint::class.java)
        val repo = entry.repository(); val db = entry.database()
        entry.secrets().saveToken(config("token"))
        val marker = config("marker")
        val step = args.getString("live_step").orEmpty()
        suspend fun rows() = db.readingDao().active().filter { it.note?.startsWith(marker) == true }
        suspend fun credits() = db.rechargeDao().observeActive().first().filter { it.note?.startsWith(marker) == true }
        suspend fun meter(type: String) = db.meterDao().active().first { it.meterType == type }.id
        suspend fun syncSuccess() { assertEquals(SyncResult.Success, repo.sync()) }
        suspend fun editCredit(amount: String) {
            val item = credits().first { it.note == "$marker:electric" }
            repo.saveRecharge(item.id, item.meterId, amount, "0.6", item.creditedAt, item.note)
        }
        when (step) {
            "setup" -> {
                repo.saveEndpoint(null, "Acceptance", config("endpoint"))
                val endpoint = db.endpointDao().all().first { it.baseUrl == config("endpoint") }
                repo.enableEndpoint(endpoint.id); syncSuccess()
            }
            "seedElectric" -> {
                val meter = meter("ELECTRICITY")
                val before = db.tariffDao().active().map { it.id }.toSet()
                repo.saveTariff(meterId = meter, price = "0.6", effectiveFrom = config("tariff_time"))
                val added = db.tariffDao().active().filter { it.id !in before }.map { it.id }.toSet()
                context.getSharedPreferences("live_acceptance", 0).edit().putStringSet("$marker:tariffs", added).commit()
                repo.saveReading(meterId = meter, value = "100", recordedAt = "2098-01-01T00:00:00Z", note = "$marker:baseline")
                repo.saveRecharge(meterId = meter, amount = "60", price = "0.6", creditedAt = "2098-01-02T00:00:00Z", note = "$marker:electric", remaining = "150")
                syncSuccess()
            }
            "verifyElectric" -> {
                syncSuccess()
                val meter = meter("ELECTRICITY")
                val stats = statisticsForRange(rows().filter { it.meterId == meter }, db.tariffDao().active().filter { it.meterId == meter }, null, null, credits().filter { it.meterId == meter })
                assertEquals(0, "50".toBigDecimal().compareTo(stats.consumption))
                assertEquals(0, "30".toBigDecimal().compareTo(stats.cost))
                assertEquals(0, "60".toBigDecimal().compareTo(stats.rechargeSpend))
            }
            "offlineCreditB" -> editCredit("66")
            "editCreditA" -> { editCredit("72"); syncSuccess() }
            "overrideB" -> {
                assertEquals(SyncResult.ConflictDetected, repo.sync())
                repo.overrideConflict(db.conflictDao().all().single()); syncSuccess()
                assertEquals("66", credits().single { it.note == "$marker:electric" }.amountDecimal)
            }
            "offlineCreditA" -> { syncSuccess(); editCredit("78") }
            "editCreditB" -> { editCredit("84"); syncSuccess() }
            "keepA" -> {
                assertEquals(SyncResult.ConflictDetected, repo.sync())
                repo.keepServerConflict(db.conflictDao().all().single()); syncSuccess()
                assertEquals("84", credits().single { it.note == "$marker:electric" }.amountDecimal)
            }
            "waterA" -> {
                val meter = meter("COLD_WATER")
                repo.saveReading(meterId = meter, value = "20", recordedAt = "2098-01-01T00:00:00Z", note = "$marker:water-base")
                repo.saveRecharge(meterId = meter, amount = "18", price = "3", creditedAt = "2098-01-03T00:00:00Z", note = "$marker:water")
                syncSuccess()
            }
            "waterB" -> {
                syncSuccess()
                val meter = meter("COLD_WATER")
                assertEquals(1, rows().count { it.meterId == meter })
                val before = statisticsForRange(rows().filter { it.meterId == meter }, emptyList(), null, null, credits().filter { it.meterId == meter })
                assertNull(before.consumption)
                repo.saveReading(meterId = meter, value = "24", recordedAt = "2098-01-04T00:00:00Z", note = "$marker:water-after")
                syncSuccess()
                val after = statisticsForRange(rows().filter { it.meterId == meter }, emptyList(), null, null, credits().filter { it.meterId == meter })
                assertEquals(0, "2".toBigDecimal().compareTo(after.consumption))
            }
            "deleteRechargeA" -> { syncSuccess(); repo.deleteRecharge(credits().single { it.note == "$marker:water" }); syncSuccess() }
            "verifyDeleteB" -> {
                syncSuccess(); assertTrue(credits().none { it.note == "$marker:water" })
                val meter = meter("COLD_WATER")
                assertEquals(2, rows().count { it.meterId == meter })
                assertTrue(statisticsForRange(rows().filter { it.meterId == meter }, emptyList(), null, null).hasIncrease)
            }
            "queueRestartB" -> {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.executeShellCommand("svc wifi disable").close()
                automation.executeShellCommand("svc data disable").close()
                delay(2000)
                repo.saveReading(meterId = meter("ELECTRICITY"), value = "125", recordedAt = "2098-01-03T00:00:00Z", note = "$marker:restart")
                SyncScheduler(context).enqueue()
                assertTrue(db.outboxDao().all().isNotEmpty())
            }
            "verifyRestartB" -> {
                withTimeout(45000) { while (db.outboxDao().all().isNotEmpty()) delay(500) }
                assertTrue(rows().single { it.note == "$marker:restart" }.serverRevision > 0)
                assertNull(db.syncStateDao().current()!!.lastError)
            }
            "exportA" -> {
                syncSuccess()
                for (kind in listOf("readings", "recharges", "tariffs")) {
                    val buffer = ByteArrayOutputStream()
                    withContext(Dispatchers.IO) { repo.exportCsv(buffer, kind) }
                    val csv = buffer.toString("UTF-8")
                    assertTrue(csv.startsWith("id,meter_id,")); assertFalse(csv.contains(config("token")))
                    if (kind != "tariffs") assertTrue(csv.contains(marker))
                }
            }
            "cleanupA" -> {
                syncSuccess()
                rows().forEach { repo.deleteReading(it) }
                credits().forEach { repo.deleteRecharge(it) }
                val ids = context.getSharedPreferences("live_acceptance", 0).getStringSet("$marker:tariffs", emptySet()).orEmpty()
                db.tariffDao().active().filter { it.id in ids }.forEach { repo.deleteTariff(it) }
                syncSuccess()
            }
            "verifyCleanupB" -> { syncSuccess(); assertTrue(rows().isEmpty()); assertTrue(credits().isEmpty()) }
            else -> error("Unknown live acceptance step")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "\nLIVE_ACCEPTANCE_OK step=$step\n") })
    }
}
