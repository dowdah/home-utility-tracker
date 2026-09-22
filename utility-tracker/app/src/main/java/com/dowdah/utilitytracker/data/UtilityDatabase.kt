package com.dowdah.utilitytracker.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        EndpointEntity::class, MeterEntity::class, ReadingEntity::class, TariffEntity::class,
        OutboxEntity::class, ConflictEntity::class, SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class UtilityDatabase : RoomDatabase() {
    abstract fun endpointDao(): EndpointDao
    abstract fun meterDao(): MeterDao
    abstract fun readingDao(): ReadingDao
    abstract fun tariffDao(): TariffDao
    abstract fun outboxDao(): OutboxDao
    abstract fun conflictDao(): ConflictDao
    abstract fun syncStateDao(): SyncStateDao
}

@Dao
interface EndpointDao {
    @Query("SELECT * FROM endpoints ORDER BY active DESC, label COLLATE NOCASE")
    fun observeAll(): Flow<List<EndpointEntity>>

    @Query("SELECT * FROM endpoints ORDER BY active DESC, label COLLATE NOCASE")
    suspend fun all(): List<EndpointEntity>

    @Query("SELECT * FROM endpoints WHERE active = 1 LIMIT 1")
    suspend fun active(): EndpointEntity?

    @Query("SELECT * FROM endpoints WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): EndpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(endpoint: EndpointEntity)

    @Query("UPDATE endpoints SET active = 0")
    suspend fun clearActive()

    @Query("UPDATE endpoints SET active = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setOnlyActive(id: String)

    @Query("DELETE FROM endpoints WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MeterDao {
    @Query("SELECT * FROM meters WHERE deleted = 0 ORDER BY meterType")
    fun observeActive(): Flow<List<MeterEntity>>
    @Query("SELECT * FROM meters WHERE deleted = 0 ORDER BY meterType")
    suspend fun active(): List<MeterEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<MeterEntity>)
}

@Dao
interface ReadingDao {
    @Query("SELECT * FROM readings WHERE deleted = 0 ORDER BY recordedAt DESC")
    fun observeActive(): Flow<List<ReadingEntity>>
    @Query("SELECT * FROM readings WHERE meterId = :meterId AND deleted = 0 ORDER BY recordedAt DESC LIMIT 1")
    suspend fun latest(meterId: String): ReadingEntity?
    @Query("SELECT * FROM readings WHERE deleted = 0 ORDER BY recordedAt")
    suspend fun active(): List<ReadingEntity>
    @Query("SELECT * FROM readings WHERE id = :id LIMIT 1") suspend fun byId(id: String): ReadingEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ReadingEntity)
    @Query("DELETE FROM readings WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface TariffDao {
    @Query("SELECT * FROM tariffs WHERE deleted = 0 ORDER BY effectiveFrom DESC")
    fun observeActive(): Flow<List<TariffEntity>>
    @Query("SELECT * FROM tariffs WHERE deleted = 0 ORDER BY effectiveFrom")
    suspend fun active(): List<TariffEntity>
    @Query("SELECT * FROM tariffs WHERE id = :id LIMIT 1") suspend fun byId(id: String): TariffEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: TariffEntity)
    @Query("DELETE FROM tariffs WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt LIMIT :limit") suspend fun next(limit: Int): List<OutboxEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: OutboxEntity)
    @Query("DELETE FROM outbox WHERE operationId = :operationId") suspend fun delete(operationId: String)
    @Query("DELETE FROM outbox WHERE entityType = :entityType AND entityId = :entityId") suspend fun deleteForEntity(entityType: String, entityId: String)
    @Query("SELECT COUNT(*) FROM outbox") fun observeCount(): Flow<Int>
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts ORDER BY createdAt DESC") fun observeAll(): Flow<List<ConflictEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ConflictEntity)
    @Query("DELETE FROM conflicts WHERE operationId = :operationId") suspend fun delete(operationId: String)
    @Query("DELETE FROM conflicts WHERE entityType = :entityType AND entityId = :entityId") suspend fun deleteForEntity(entityType: String, entityId: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0") fun observe(): Flow<SyncStateEntity?>
    @Query("SELECT * FROM sync_state WHERE id = 0") suspend fun current(): SyncStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(state: SyncStateEntity)
}
