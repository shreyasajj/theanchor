package com.anchor.data.usage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One open of a limited app: the moment the pause let the user in, by
 * Continue or by finishing a breathe. The usage log cannot tell this apart
 * from the app flashing in front before the pause covers it, so opens are
 * recorded here instead of derived from it.
 *
 * Keyed by the limit's [AppLimit.subject], so a group's members share one
 * count; [packageName] is kept for the dashboard's per-app breakdown.
 */
@Entity(
    tableName = "limit_open",
    indices = [Index(value = ["subject", "atMillis"])],
)
data class LimitOpen(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val packageName: String,
    val atMillis: Long,
)

@Dao
interface LimitOpenDao {

    @Insert
    suspend fun insert(open: LimitOpen)

    @Query("SELECT * FROM limit_open WHERE subject = :subject AND atMillis >= :fromMillis ORDER BY atMillis ASC")
    suspend fun since(subject: String, fromMillis: Long): List<LimitOpen>

    @Query("DELETE FROM limit_open WHERE atMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
