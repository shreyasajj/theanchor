package com.anchor.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per calendar day. The five named columns are the spec's default
 * question slots; anything the user adds themselves is serialised into
 * [extraAnswersJson] as a JSON object of slotKey -> answer.
 *
 * Morning and evening write to the same row: the evening check-in reads the
 * existing row, copies it with the evening fields filled in, and upserts.
 */
@Entity(
    tableName = "daily_log",
    indices = [Index(value = ["date"], unique = true)],
)
data class DailyLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO yyyy-MM-dd in the device's default zone. */
    val date: String,
    val mission: String? = null,
    val avoiding: String? = null,
    val led: String? = null,
    val softened: String? = null,
    val faked: String? = null,
    /** JSON object: {"custom:<uuid>": "answer"}. Null when there are none. */
    val extraAnswersJson: String? = null,
    val morningCompletedAt: Long? = null,
    val eveningCompletedAt: Long? = null,
)
