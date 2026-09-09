package com.anchor.data.export

import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A JoplinApi that must never be called. */
object NoJoplinApi : JoplinApi {
    override suspend fun createNote(url: String, body: JoplinNoteRequest): JoplinNoteResponse =
        error("NoJoplinApi should not be called")
}

class CheckInExporterTest {

    private class RecordingMarkdown(private val result: ExportResult) : MarkdownExporter({ null }) {
        var calls = 0
        var lastFormat: NoteFormat? = null
        override suspend fun export(
            log: DailyLog,
            questions: List<CustomQuestion>,
            phase: Phase,
            treeUri: String?,
            format: NoteFormat,
        ): ExportResult {
            calls++
            lastFormat = format
            return result
        }
    }

    private class RecordingJoplin(private val ok: Boolean) : JoplinExporter(NoJoplinApi) {
        var pushedTitle: String? = null
        var pushedBody: String? = null
        override suspend fun push(title: String, body: String, settings: AnchorSettings): Boolean {
            pushedTitle = title
            pushedBody = body
            return ok
        }
    }

    private val log = DailyLog(date = "2026-09-09", mission = "Ship", avoiding = "Email")
    private val questions = DefaultQuestions.ALL

    @Test
    fun `writes locally then pushes the same content to Joplin`() = runTest {
        val written = ExportResult.Written("2026-09-09.md", "# Daily Anchor - 2026-09-09\n")
        val joplin = RecordingJoplin(ok = true)
        val exporter = CheckInExporter(RecordingMarkdown(written), joplin)

        val result = exporter.export(log, questions, Phase.MORNING, AnchorSettings())

        assertThat(result).isEqualTo(written)
        assertThat(joplin.pushedTitle).isEqualTo("Daily Anchor - 2026-09-09")
        assertThat(joplin.pushedBody).isEqualTo("# Daily Anchor - 2026-09-09\n")
    }

    @Test
    fun `a Joplin failure does not change the local result`() = runTest {
        val written = ExportResult.Written("2026-09-09.md", "content")
        val exporter = CheckInExporter(RecordingMarkdown(written), RecordingJoplin(ok = false))

        assertThat(exporter.export(log, questions, Phase.MORNING, AnchorSettings())).isEqualTo(written)
    }

    @Test
    fun `Joplin is still attempted when no local directory is configured`() = runTest {
        val joplin = RecordingJoplin(ok = true)
        val exporter = CheckInExporter(RecordingMarkdown(ExportResult.NoDirectoryConfigured), joplin)

        val result = exporter.export(log, questions, Phase.MORNING, AnchorSettings())

        assertThat(joplin.pushedBody).contains("**Mission:** Ship")
        assertThat(result).isEqualTo(ExportResult.NoDirectoryConfigured)
    }

    @Test
    fun `a local write failure still attempts Joplin`() = runTest {
        val joplin = RecordingJoplin(ok = true)
        val exporter = CheckInExporter(RecordingMarkdown(ExportResult.Failed("disk")), joplin)

        exporter.export(log, questions, Phase.MORNING, AnchorSettings())

        assertThat(joplin.pushedBody).isNotNull()
    }

    @Test
    fun `the configured note format is threaded through to the local exporter and the fallback render`() = runTest {
        val markdown = RecordingMarkdown(ExportResult.NoDirectoryConfigured)
        val joplin = RecordingJoplin(ok = true)
        val settings = AnchorSettings(noteFormat = NoteFormat.OBSIDIAN)

        CheckInExporter(markdown, joplin).export(log, questions, Phase.MORNING, settings)

        assertThat(markdown.lastFormat).isEqualTo(NoteFormat.OBSIDIAN)
        assertThat(joplin.pushedBody).startsWith("---\n")
    }
}
