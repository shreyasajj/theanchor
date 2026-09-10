package com.anchor.data.usage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A moment the user chose to lock an app before its session ran out, via the
 * accessibility button. Ends the open in progress; the next open costs half.
 */
@Entity(tableName = "early_lock")
data class EarlyLock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val atMillis: Long,
)

@Dao
interface EarlyLockDao {

    @Insert
    suspend fun insert(lock: EarlyLock)

    @Query("SELECT atMillis FROM early_lock WHERE packageName = :packageName AND atMillis >= :fromMillis ORDER BY atMillis ASC")
    suspend fun since(packageName: String, fromMillis: Long): List<Long>

    @Query("DELETE FROM early_lock WHERE atMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
