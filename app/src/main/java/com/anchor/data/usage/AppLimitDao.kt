package com.anchor.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLimitDao {

    @Query("SELECT * FROM app_limit WHERE packageName = :packageName LIMIT 1")
    suspend fun find(packageName: String): AppLimit?

    @Query("SELECT * FROM app_limit ORDER BY packageName ASC")
    fun observeAll(): Flow<List<AppLimit>>

    @Query("SELECT * FROM app_limit ORDER BY packageName ASC")
    suspend fun all(): List<AppLimit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(limit: AppLimit)

    @Query("DELETE FROM app_limit WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
