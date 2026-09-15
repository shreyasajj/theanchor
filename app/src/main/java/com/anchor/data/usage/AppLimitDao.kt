package com.anchor.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLimitDao {

    @Query("SELECT * FROM app_limit WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): AppLimit?

    /**
     * The limit whose group contains [packageName]. Membership is a small set
     * per row and there are a handful of rows, so this is filtered in memory
     * rather than with a LIKE over the stored set.
     */
    suspend fun find(packageName: String): AppLimit? = all().firstOrNull { packageName in it }

    /** Every limit containing [packageName]; several when they have different windows. */
    suspend fun findAll(packageName: String): List<AppLimit> = all().filter { packageName in it }

    @Query("SELECT * FROM app_limit ORDER BY id ASC")
    fun observeAll(): Flow<List<AppLimit>>

    @Query("SELECT * FROM app_limit ORDER BY id ASC")
    suspend fun all(): List<AppLimit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(limit: AppLimit): Long

    @Query("DELETE FROM app_limit WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Removes the limit that contains [packageName], whole group included. */
    @Transaction
    suspend fun delete(packageName: String) {
        find(packageName)?.let { deleteById(it.id) }
    }
}
