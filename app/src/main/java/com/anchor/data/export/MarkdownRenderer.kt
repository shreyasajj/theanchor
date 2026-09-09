package com.anchor.data.export

import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

private val extrasJson = Json { ignoreUnknownKeys = true }

/** Reads an answer by slot key, from a named column or the extras map. */
fun DailyLog.answerFor(slotKey: String): String? = when (slotKey) {
    SlotKey.MISSION -> mission
    SlotKey.AVOIDING -> avoiding
    SlotKey.LED -> led
    SlotKey.SOFTENED -> softened
    SlotKey.FAKED -> faked
    else -> extras()[slotKey]
}

/** Writes an answer by slot key, returning a new log. */
fun DailyLog.withAnswer(slotKey: String, answer: String): DailyLog = when (slotKey) {
    SlotKey.MISSION -> copy(mission = answer)
    SlotKey.AVOIDING -> copy(avoiding = answer)
    SlotKey.LED -> copy(led = answer)
    SlotKey.SOFTENED -> copy(softened = answer)
    SlotKey.FAKED -> copy(faked = answer)
    else -> {
        val next = extras() + (slotKey to answer)
        copy(
            extraAnswersJson = extrasJson.encodeToString(
                JsonObject.serializer(),
                JsonObject(next.mapValues { JsonPrimitive(it.value) }),
            )
        )
    }
}

private fun DailyLog.extras(): Map<String, String> {
    val raw = extraAnswersJson ?: return emptyMap()
    return runCatching {
        extrasJson.decodeFromString(JsonObject.serializer(), raw)
            .mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())
}

/**
 * Renders a [DailyLog] into the Markdown document the spec describes.
 * Pure and Android-free so it can be unit-tested directly.
 */
object MarkdownRenderer {

    private const val MORNING_HEADING = "## Morning"
    private const val EVENING_HEADING = "## Evening"

    /** The short labels the spec uses for the five default slots. */
    private val NAMED_LABELS = mapOf(
        SlotKey.MISSION to "Mission",
        SlotKey.AVOIDING to "Avoiding",
        SlotKey.LED to "Led",
        SlotKey.SOFTENED to "Softened",
        SlotKey.FAKED to "Faked",
    )

    fun heading(phase: Phase): String =
        if (phase == Phase.MORNING) MORNING_HEADING else EVENING_HEADING

    fun title(date: String): String = "# Daily Anchor - $date"

    /**
     * A `## Morning` or `## Evening` block, or null when the log has no
     * answers for that phase.
     */
    fun renderSection(
        phase: Phase,
        log: DailyLog,
        questions: List<CustomQuestion>,
    ): String? {
        val lines = questions
            .filter { it.phase == phase }
            .sortedBy { it.sortOrder }
            .mapNotNull { question ->
                val answer = log.answerFor(question.slotKey)?.trim()
                if (answer.isNullOrBlank()) return@mapNotNull null
                val label = NAMED_LABELS[question.slotKey] ?: question.prompt
                // Flatten newlines so each answer stays one list item.
                "- **$label:** ${answer.replace(Regex("\\s*\\R\\s*"), " ")}"
            }
        if (lines.isEmpty()) return null
        return (listOf(heading(phase)) + lines).joinToString("\n")
    }

    fun render(
        log: DailyLog,
        questions: List<CustomQuestion>,
        format: NoteFormat = NoteFormat.PLAIN,
    ): String {
        val sections = listOfNotNull(
            renderSection(Phase.MORNING, log, questions),
            renderSection(Phase.EVENING, log, questions),
        )
        return buildString {
            if (format == NoteFormat.OBSIDIAN) append(frontmatter(log.date))
            append(title(log.date)).append("\n")
            if (format == NoteFormat.OBSIDIAN) {
                // Directly under the title, not at the end: appending an
                // Evening section later must not strand it below the content.
                append("\n").append("Previous: [[${previousDate(log.date)}]]").append("\n")
            }
            sections.forEach { append("\n").append(it).append("\n") }
        }
    }

    private fun frontmatter(date: String): String =
        "---\ndate: $date\ntags: [anchor]\n---\n\n"

    private fun previousDate(date: String): String =
        LocalDate.parse(date).minusDays(1).toString()

    /**
     * Folds [section] into [existing] file content: replaces the phase's
     * block if present, otherwise inserts it in Morning-then-Evening order.
     * Any other content in the file (hand-written notes, frontmatter) is
     * preserved.
     */
    fun mergeInto(existing: String, section: String, phase: Phase): String {
        val heading = heading(phase)
        val otherHeading = heading(if (phase == Phase.MORNING) Phase.EVENING else Phase.MORNING)

        val start = existing.indexOf(heading)
        if (start >= 0) {
            // Replace from this heading up to the next "## " heading or EOF.
            val after = existing.indexOf("\n## ", start + heading.length)
            val end = if (after >= 0) after + 1 else existing.length
            return existing.substring(0, start).trimEnd('\n') +
                "\n\n" + section + "\n" +
                existing.substring(end).let { if (it.isBlank()) "" else "\n$it" }
        }

        // Morning must land before an existing Evening block.
        val otherStart = existing.indexOf(otherHeading)
        if (phase == Phase.MORNING && otherStart >= 0) {
            return existing.substring(0, otherStart).trimEnd('\n') +
                "\n\n" + section + "\n\n" + existing.substring(otherStart)
        }

        return existing.trimEnd('\n') + "\n\n" + section + "\n"
    }
}
