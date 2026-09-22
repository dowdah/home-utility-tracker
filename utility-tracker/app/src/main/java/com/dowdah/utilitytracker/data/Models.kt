package com.dowdah.utilitytracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "endpoints")
data class EndpointEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val label: String,
    val baseUrl: String,
    val active: Boolean = false,
    val healthStatus: String = "UNKNOWN",
    val healthDetail: String? = null,
    val healthCheckedAt: Long? = null,
    val healthLatencyMs: Long? = null,
)

@Entity(tableName = "meters")
data class MeterEntity(
    @PrimaryKey val id: String,
    val meterType: String,
    val generation: Int,
    val unit: String,
    val active: Boolean,
    val deleted: Boolean,
    val createdRevision: Long,
)

@Entity(tableName = "readings")
data class ReadingEntity(
    @PrimaryKey val id: String,
    val meterId: String,
    val valueDecimal: String,
    val recordedAt: String,
    val note: String?,
    val deleted: Boolean,
    val serverRevision: Long,
)

@Entity(tableName = "tariffs")
data class TariffEntity(
    @PrimaryKey val id: String,
    val meterId: String,
    val priceDecimal: String,
    val currency: String,
    val effectiveFrom: String,
    val deleted: Boolean,
    val serverRevision: Long,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val operationId: String = UUID.randomUUID().toString(),
    val entityType: String,
    val entityId: String,
    val kind: String,
    val baseRevision: Long,
    val payloadJson: String,
    val createdAt: String,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey val operationId: String,
    val entityType: String,
    val entityId: String,
    val localPayloadJson: String,
    val serverEntityJson: String?,
    val createdAt: String,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val backendInstanceId: String? = null,
    val cursorRevision: Long = 0,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)
