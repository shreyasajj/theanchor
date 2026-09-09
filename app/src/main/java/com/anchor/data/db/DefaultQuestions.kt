package com.anchor.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Seeded once, on database creation. The user is free to edit or delete
 * any of these afterwards: they are defaults, not invariants.
 */
object DefaultQuestions {
    val ALL: List<CustomQuestion> = listOf(
        CustomQuestion(
            phase = Phase.MORNING,
            slotKey = SlotKey.MISSION,
            prompt = "What is my mission today?",
            sortOrder = 0,
        ),
        CustomQuestion(
            phase = Phase.MORNING,
            slotKey = SlotKey.AVOIDING,
            prompt = "What am I currently avoiding?",
            sortOrder = 1,
        ),
        CustomQuestion(
            phase = Phase.EVENING,
            slotKey = SlotKey.LED,
            prompt = "One moment I led: (What decision did I make without seeking approval?)",
            sortOrder = 0,
        ),
        CustomQuestion(
            phase = Phase.EVENING,
            slotKey = SlotKey.SOFTENED,
            prompt = "One moment I softened: (When did I express a feeling or show genuine appreciation?)",
            sortOrder = 1,
        ),
        CustomQuestion(
            phase = Phase.EVENING,
            slotKey = SlotKey.FAKED,
            prompt = "One moment I faked it: (When did I act to get approval rather than express truth?)",
            sortOrder = 2,
        ),
    )

    /**
     * Inserts the defaults with raw SQL. Used from Room's onCreate callback,
     * where the DAOs are not yet available. Shared with the seeding test so
     * the two cannot drift apart.
     */
    fun seed(db: SupportSQLiteDatabase) {
        ALL.forEach { q ->
            db.execSQL(
                "INSERT INTO custom_question (phase, slotKey, prompt, sortOrder, enabled) " +
                    "VALUES (?, ?, ?, ?, 1)",
                arrayOf(q.phase.name, q.slotKey, q.prompt, q.sortOrder),
            )
        }
    }
}
