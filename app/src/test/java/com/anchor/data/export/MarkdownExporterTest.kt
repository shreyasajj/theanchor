package com.anchor.data.export

import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MarkdownExporterTest {

    /** An in-memory DocumentStore: no Android, no SAF, no disk. */
    private class FakeStore(
        seed: Map<String, String> = emptyMap(),
        private val failWrites: Boolean = false,
    ) : DocumentStore {
        val files = seed.toMutableMap()
        override fun read(fileName: String): String? = files[fileName]
        override fun write(fileName: String, content: String): Boolean {
            if (failWrites) return false
            files[fileName] = content
            return true
        }
    }

    private val questions = DefaultQuestions.ALL

    private val morningLog = DailyLog(date = "2026-09-09", mission = "Ship the plan", avoiding = "The invoice email")

    private val fullLog = morningLog.copy(
        led = "Chose the schema",
        softened = "Called my sister",
        faked = "Nodded in standup",
    )

    private fun exporter(store: DocumentStore?) = MarkdownExporter { store }

    @Test
    fun `writes a new file named by the date`() = runTest {
        val store = FakeStore()
        val result = exporter(store).export(morningLog, questions, Phase.MORNING, treeUri = "content://tree")

        assertThat(result).isInstanceOf(ExportResult.Written::class.java)
        assertThat((result as ExportResult.Written).fileName).isEqualTo("2026-09-09.md")
        assertThat(store.files["2026-09-09.md"]).contains("# Daily Anchor - 2026-09-09")
        assertThat(store.files["2026-09-09.md"]).contains("**Mission:** Ship the plan")
    }

    @Test
    fun `appends the evening section to an existing morning file`() = runTest {
        val existing = MarkdownRenderer.render(morningLog, questions)
        val store = FakeStore(mapOf("2026-09-09.md" to existing))

        exporter(store).export(fullLog, questions, Phase.EVENING, treeUri = "content://tree")

        val content = store.files["2026-09-09.md"]!!
        assertThat(content).contains("## Morning")
        assertThat(content).contains("## Evening")
        assertThat(content).contains("**Led:** Chose the schema")
        assertThat(content.split("## Morning")).hasSize(2)
    }

    @Test
    fun `re-exporting the same phase replaces rather than duplicates`() = runTest {
        val store = FakeStore()
        val ex = exporter(store)
        ex.export(morningLog, questions, Phase.MORNING, "content://tree")
        ex.export(morningLog.copy(mission = "Revised mission"), questions, Phase.MORNING, "content://tree")

        val content = store.files["2026-09-09.md"]!!
        assertThat(content.split("## Morning")).hasSize(2)
        assertThat(content).contains("Revised mission")
        assertThat(content).doesNotContain("Ship the plan")
    }

    @Test
    fun `preserves hand-written content already in the file`() = runTest {
        val store = FakeStore(mapOf("2026-09-09.md" to "# Daily Anchor - 2026-09-09\n\nA note I typed in Joplin.\n"))

        exporter(store).export(morningLog, questions, Phase.MORNING, "content://tree")

        assertThat(store.files["2026-09-09.md"]).contains("A note I typed in Joplin.")
    }

    @Test
    fun `a new file honours the Obsidian format`() = runTest {
        val store = FakeStore()
        exporter(store).export(morningLog, questions, Phase.MORNING, "content://tree", NoteFormat.OBSIDIAN)
        assertThat(store.files["2026-09-09.md"]).startsWith("---\n")
    }

    @Test
    fun `returns NoDirectoryConfigured when the tree uri is null`() = runTest {
        val result = exporter(FakeStore()).export(morningLog, questions, Phase.MORNING, treeUri = null)
        assertThat(result).isEqualTo(ExportResult.NoDirectoryConfigured)
    }

    @Test
    fun `returns NoDirectoryConfigured when the store cannot be opened`() = runTest {
        val result = MarkdownExporter { null }
            .export(morningLog, questions, Phase.MORNING, treeUri = "content://revoked")
        assertThat(result).isEqualTo(ExportResult.NoDirectoryConfigured)
    }

    @Test
    fun `a failed write returns Failed and does not throw`() = runTest {
        val result = exporter(FakeStore(failWrites = true))
            .export(morningLog, questions, Phase.MORNING, "content://tree")
        assertThat(result).isInstanceOf(ExportResult.Failed::class.java)
    }

    @Test
    fun `the returned content matches what was written, for Joplin to reuse`() = runTest {
        val store = FakeStore()
        val result = exporter(store)
            .export(fullLog, questions, Phase.EVENING, "content://tree") as ExportResult.Written

        assertThat(result.content).isEqualTo(store.files["2026-09-09.md"])
    }
}
