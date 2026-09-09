package com.anchor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyLogDao {

    @Query("SELECT * FROM daily_log WHERE date = :date LIMIT 1")
    suspend fun findByDate(date: String): DailyLog?

    @Query("SELECT * FROM daily_log WHERE date = :date LIMIT 1")
    fun observeByDate(date: String): Flow<DailyLog?>

    @Query("SELECT * FROM daily_log ORDER BY date DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DailyLog>

    /**
     * REPLACE on the unique `date` index. Callers that want to preserve the
     * other half of the day MUST read the existing row and `copy()` it first.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: DailyLog): Long
}
