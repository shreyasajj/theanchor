package com.anchor.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class Phase { MORNING, EVENING }

/**
 * Stable identifiers linking a question to where its answer is stored.
 * The five named slots map to real columns on [DailyLog]; everything else
 * is a `custom:<uuid>` key inside `DailyLog.extraAnswersJson`.
 */
object SlotKey {
    const val MISSION = "mission"
    const val AVOIDING = "avoiding"
    const val LED = "led"
    const val SOFTENED = "softened"
    const val FAKED = "faked"

    const val CUSTOM_PREFIX = "custom:"

    val NAMED = setOf(MISSION, AVOIDING, LED, SOFTENED, FAKED)

    fun custom(): String = CUSTOM_PREFIX + UUID.randomUUID()

    fun isCustom(key: String): Boolean = key.startsWith(CUSTOM_PREFIX)
}

/**
 * A question rendered on a lock screen. The user can add, edit, reorder,
 * disable and delete these from Settings without rebuilding the app.
 */
@Entity(tableName = "custom_question")
data class CustomQuestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phase: Phase,
    val slotKey: String,
    val prompt: String,
    val sortOrder: Int,
    val enabled: Boolean = true,
)
