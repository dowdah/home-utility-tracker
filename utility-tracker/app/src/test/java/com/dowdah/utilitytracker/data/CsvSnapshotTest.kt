package com.dowdah.utilitytracker.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class CsvSnapshotTest {
    @Test fun csvPreservesDecimalPrecisionAndEscapesUnicodeCommaQuotesAndNewlines() {
        val output = ByteArrayOutputStream()
        CsvSnapshot(listOf("amount", "note", "sync_status"), listOf(listOf("0.123456789012", "中文,\"备注\"\r\nnext", "pending"), listOf("0", null, "synced"))).writeTo(output)
        assertEquals("amount,note,sync_status\r\n0.123456789012,\"中文,\"\"备注\"\"\r\nnext\",pending\r\n0,,synced\r\n", output.toString("UTF-8"))
    }
    @Test(expected = IOException::class) fun writerFailureIsNotReportedAsSuccess() {
        CsvSnapshot(listOf("id"), listOf(listOf("fixture"))).writeTo(object : OutputStream() {
            override fun write(b: Int) { throw IOException("synthetic failure") }
        })
    }
}
