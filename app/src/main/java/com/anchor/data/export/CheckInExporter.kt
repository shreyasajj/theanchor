package com.anchor.data.export

import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.Phase
import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for "this check-in is done, persist it outward".
 * Local file first (it is the durable copy), Joplin second and optional.
 */
@Singleton
open class CheckInExporter @Inject constructor(
    private val markdown: MarkdownExporter,
    private val joplin: JoplinExporter,
) {
    open suspend fun export(
        log: DailyLog,
        questions: List<CustomQuestion>,
        phase: Phase,
        settings: AnchorSettings,
    ): ExportResult {
        val local = markdown.export(log, questions, phase, settings.exportTreeUri, settings.noteFormat)

        // Prefer the exact bytes we wrote; fall back to a fresh render so a
        // missing or unwritable folder still produces a Joplin note.
        val body = (local as? ExportResult.Written)?.content
            ?: MarkdownRenderer.render(log, questions, settings.noteFormat)

        joplin.push(
            title = "Daily Anchor - ${log.date}",
            body = body,
            settings = settings,
        )

        return local
    }
}
