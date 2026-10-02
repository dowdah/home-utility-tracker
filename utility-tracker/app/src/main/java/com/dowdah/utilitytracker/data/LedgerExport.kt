package com.dowdah.utilitytracker.data

import androidx.room.withTransaction
import java.io.OutputStream
import java.io.OutputStreamWriter

enum class ExportSource { LOCAL, SERVER }
data class ExportRequest(val kind: String, val source: ExportSource)

data class CsvSnapshot(val columns: List<String>, val rows: List<List<String?>>) {
    fun writeTo(output: OutputStream) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8).buffered()
        fun row(fields: List<String?>) {
            writer.write(fields.joinToString(",") { field ->
                val text = field.orEmpty()
                if (text.any { it in ",\"\r\n" }) "\"${text.replace("\"", "\"\"")}\"" else text
            })
            writer.write("\r\n")
        }
        row(columns); rows.forEach(::row); writer.flush()
    }
}

/** Capture entities and their queue/conflict flags under the same Room transaction. */
suspend fun localCsvSnapshot(database: UtilityDatabase, kind: String): CsvSnapshot = database.withTransaction {
    val pending = database.outboxDao().all().map { it.entityType to it.entityId }.toSet()
    val conflicts = database.conflictDao().all().map { it.entityType to it.entityId }.toSet()
    fun status(type: String, id: String) = when (type to id) {
        in conflicts -> "conflict"
        in pending -> "pending"
        else -> "synced"
    }
    when (kind) {
        "readings" -> CsvSnapshot("id,meter_id,value_decimal,recorded_at,note,deleted,server_revision,sync_status".split(","),
            database.readingDao().allForExport().map { listOf(it.id, it.meterId, it.valueDecimal, it.recordedAt, it.note, if (it.deleted) "1" else "0", it.serverRevision.toString(), status("reading", it.id)) })
        "recharges" -> CsvSnapshot("id,meter_id,amount_decimal,unit_price_decimal,quantity_decimal,currency,credited_at,note,deleted,server_revision,sync_status".split(","),
            database.rechargeDao().allForExport().map { listOf(it.id, it.meterId, it.amountDecimal, it.unitPriceDecimal, it.quantityDecimal, it.currency, it.creditedAt, it.note, if (it.deleted) "1" else "0", it.serverRevision.toString(), status("recharge", it.id)) })
        "tariffs" -> CsvSnapshot("id,meter_id,price_decimal,currency,effective_from,deleted,server_revision,sync_status".split(","),
            database.tariffDao().allForExport().map { listOf(it.id, it.meterId, it.priceDecimal, it.currency, it.effectiveFrom, if (it.deleted) "1" else "0", it.serverRevision.toString(), status("tariff", it.id)) })
        else -> throw IllegalArgumentException("Unknown export type")
    }
}
