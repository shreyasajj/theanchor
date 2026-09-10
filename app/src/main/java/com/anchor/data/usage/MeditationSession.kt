package com.anchor.data.usage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A finished sit. Recorded whether it was chosen instead of opening an app or
 * started from the dashboard, so the dashboard can show the day's total.
 */
@Entity(tableName = "meditation_session")
data class MeditationSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The app the user chose not to open, or null when started deliberately. */
    val insteadOfPackage: String? = null,
    val startedAtMillis: Long,
    val seconds: Int,
)

@Dao
interface MeditationSessionDao {

    @Insert
    suspend fun insert(session: MeditationSession)

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM meditation_session WHERE startedAtMillis >= :fromMillis")
    suspend fun secondsSince(fromMillis: Long): Int

    @Query("SELECT COUNT(*) FROM meditation_session WHERE startedAtMillis >= :fromMillis")
    suspend fun countSince(fromMillis: Long): Int

    @Query("SELECT * FROM meditation_session ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MeditationSession>>
}
