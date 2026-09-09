package com.anchor.data.export

import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ObsidianFormatTest {

    private val questions = DefaultQuestions.ALL

    private val log = DailyLog(
        date = "2026-09-09",
        mission = "Ship the plan",
        avoiding = "The invoice email",
    )

    @Test
    fun `plain format is byte-for-byte unchanged`() {
        val expected = """
            # Daily Anchor - 2026-09-09

            ## Morning
            - **Mission:** Ship the plan
            - **Avoiding:** The invoice email
        """.trimIndent() + "\n"

        assertThat(MarkdownRenderer.render(log, questions, NoteFormat.PLAIN)).isEqualTo(expected)
    }

    @Test
    fun `plain is the default when no format is given`() {
        assertThat(MarkdownRenderer.render(log, questions))
            .isEqualTo(MarkdownRenderer.render(log, questions, NoteFormat.PLAIN))
    }

    @Test
    fun `obsidian opens with YAML frontmatter`() {
        val rendered = MarkdownRenderer.render(log, questions, NoteFormat.OBSIDIAN)

        assertThat(rendered).startsWith("---\n")
        assertThat(rendered).contains("date: 2026-09-09")
        assertThat(rendered).contains("tags: [anchor]")
    }

    @Test
    fun `obsidian links the previous day directly under the title`() {
        val rendered = MarkdownRenderer.render(log, questions, NoteFormat.OBSIDIAN)

        assertThat(rendered).contains("Previous: [[2026-09-08]]")
        assertThat(rendered.indexOf("Previous: [[2026-09-08]]")).isLessThan(rendered.indexOf("## Morning"))
    }

    @Test
    fun `the previous-day link rolls back across a month boundary`() {
        val rendered = MarkdownRenderer.render(log.copy(date = "2026-10-01"), questions, NoteFormat.OBSIDIAN)
        assertThat(rendered).contains("Previous: [[2026-09-30]]")
    }

    @Test
    fun `obsidian keeps the same section bodies as plain`() {
        val rendered = MarkdownRenderer.render(log, questions, NoteFormat.OBSIDIAN)

        assertThat(rendered).contains("## Morning")
        assertThat(rendered).contains("- **Mission:** Ship the plan")
    }

    @Test
    fun `merging an evening section preserves the frontmatter`() {
        val existing = MarkdownRenderer.render(log, questions, NoteFormat.OBSIDIAN)
        val full = log.copy(led = "Decided", softened = "Called", faked = "Nodded")
        val evening = MarkdownRenderer.renderSection(Phase.EVENING, full, questions)!!

        val merged = MarkdownRenderer.mergeInto(existing, evening, Phase.EVENING)

        assertThat(merged).startsWith("---\n")
        assertThat(merged).contains("date: 2026-09-09")
        assertThat(merged).contains("Previous: [[2026-09-08]]")
        assertThat(merged).contains("## Morning")
        assertThat(merged).contains("## Evening")
        assertThat(merged).contains("**Led:** Decided")
    }

    @Test
    fun `merging does not duplicate the frontmatter block`() {
        val existing = MarkdownRenderer.render(log, questions, NoteFormat.OBSIDIAN)
        val evening = MarkdownRenderer.renderSection(Phase.EVENING, log.copy(led = "Decided"), questions)!!

        val merged = MarkdownRenderer.mergeInto(existing, evening, Phase.EVENING)

        assertThat(merged.split("date: 2026-09-09")).hasSize(2)
    }
}
