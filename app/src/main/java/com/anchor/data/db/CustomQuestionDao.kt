package com.anchor.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomQuestionDao {

    @Query("SELECT * FROM custom_question WHERE phase = :phase AND enabled = 1 ORDER BY sortOrder ASC")
    fun observe(phase: Phase): Flow<List<CustomQuestion>>

    @Query("SELECT * FROM custom_question WHERE phase = :phase AND enabled = 1 ORDER BY sortOrder ASC")
    suspend fun list(phase: Phase): List<CustomQuestion>

    /** Includes disabled rows: Settings needs to show and re-enable them. */
    @Query("SELECT * FROM custom_question WHERE phase = :phase ORDER BY sortOrder ASC")
    fun observeIncludingDisabled(phase: Phase): Flow<List<CustomQuestion>>

    @Query("SELECT COUNT(*) FROM custom_question")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(question: CustomQuestion): Long

    @Delete
    suspend fun delete(question: CustomQuestion)
}
