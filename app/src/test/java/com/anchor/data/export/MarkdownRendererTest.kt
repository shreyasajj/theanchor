package com.anchor.data.export

import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownRendererTest {

    private val defaults = DefaultQuestions.ALL

    private val fullLog = DailyLog(
        date = "2026-09-09",
        mission = "Ship the Anchor plan",
        avoiding = "The invoice email",
        led = "Chose the schema without asking",
        softened = "Told my sister I missed her",
        faked = "Nodded along in standup",
    )

    // --- Slot mapping ---

    @Test
    fun `answerFor reads the five named columns`() {
        assertThat(fullLog.answerFor(SlotKey.MISSION)).isEqualTo("Ship the Anchor plan")
        assertThat(fullLog.answerFor(SlotKey.AVOIDING)).isEqualTo("The invoice email")
        assertThat(fullLog.answerFor(SlotKey.LED)).isEqualTo("Chose the schema without asking")
        assertThat(fullLog.answerFor(SlotKey.SOFTENED)).isEqualTo("Told my sister I missed her")
        assertThat(fullLog.answerFor(SlotKey.FAKED)).isEqualTo("Nodded along in standup")
    }

    @Test
    fun `withAnswer writes named slots to columns and custom slots to extras`() {
        val custom = "custom:abc-123"
        val log = DailyLog(date = "2026-09-09")
            .withAnswer(SlotKey.MISSION, "Focus")
            .withAnswer(custom, "Call the plumber")

        assertThat(log.mission).isEqualTo("Focus")
        assertThat(log.answerFor(custom)).isEqualTo("Call the plumber")
        assertThat(log.extraAnswersJson).contains("Call the plumber")
    }

    @Test
    fun `withAnswer on a custom slot preserves other custom answers`() {
        val log = DailyLog(date = "2026-09-09")
            .withAnswer("custom:a", "one")
            .withAnswer("custom:b", "two")

        assertThat(log.answerFor("custom:a")).isEqualTo("one")
        assertThat(log.answerFor("custom:b")).isEqualTo("two")
    }

    @Test
    fun `answerFor returns null for an unknown custom slot`() {
        assertThat(DailyLog(date = "2026-09-09").answerFor("custom:nope")).isNull()
    }

    // --- Rendering ---

    @Test
    fun `renders the exact format from the spec`() {
        val expected = """
            # Daily Anchor - 2026-09-09

            ## Morning
            - **Mission:** Ship the Anchor plan
            - **Avoiding:** The invoice email

            ## Evening
            - **Led:** Chose the schema without asking
            - **Softened:** Told my sister I missed her
            - **Faked:** Nodded along in standup
        """.trimIndent() + "\n"

        assertThat(MarkdownRenderer.render(fullLog, defaults)).isEqualTo(expected)
    }

    @Test
    fun `omits the evening section when there are no evening answers`() {
        val morningOnly = fullLog.copy(led = null, softened = null, faked = null)
        val rendered = MarkdownRenderer.render(morningOnly, defaults)

        assertThat(rendered).contains("## Morning")
        assertThat(rendered).doesNotContain("## Evening")
    }

    @Test
    fun `renders a user-added question using its own prompt as the label`() {
        val custom = CustomQuestion(
            phase = Phase.MORNING, slotKey = "custom:x", prompt = "Who do I owe a reply?", sortOrder = 2,
        )
        val log = fullLog.withAnswer("custom:x", "Priya")

        val rendered = MarkdownRenderer.render(log, defaults + custom)

        assertThat(rendered).contains("- **Who do I owe a reply?:** Priya")
    }

    @Test
    fun `respects question sortOrder`() {
        val reordered = defaults.map {
            when (it.slotKey) {
                SlotKey.MISSION -> it.copy(sortOrder = 5)
                SlotKey.AVOIDING -> it.copy(sortOrder = 1)
                else -> it
            }
        }
        val rendered = MarkdownRenderer.render(fullLog, reordered)

        assertThat(rendered.indexOf("**Avoiding:**")).isLessThan(rendered.indexOf("**Mission:**"))
    }

    @Test
    fun `a multi-line answer is flattened so the bullet list stays valid`() {
        val log = fullLog.copy(mission = "Line one\nLine two")
        assertThat(MarkdownRenderer.render(log, defaults)).contains("- **Mission:** Line one Line two")
    }

    @Test
    fun `skips questions whose answer is missing or blank`() {
        val log = fullLog.copy(avoiding = "   ")
        val rendered = MarkdownRenderer.render(log, defaults)

        assertThat(rendered).contains("**Mission:**")
        assertThat(rendered).doesNotContain("**Avoiding:**")
    }

    // --- Merging into an existing file ---

    @Test
    fun `merging an evening section appends it to a morning-only file`() {
        val existing = MarkdownRenderer.render(fullLog.copy(led = null, softened = null, faked = null), defaults)
        val evening = MarkdownRenderer.renderSection(Phase.EVENING, fullLog, defaults)!!

        val merged = MarkdownRenderer.mergeInto(existing, evening, Phase.EVENING)

        assertThat(merged).isEqualTo(MarkdownRenderer.render(fullLog, defaults))
    }

    @Test
    fun `merging replaces an existing section rather than duplicating it`() {
        val existing = MarkdownRenderer.render(fullLog, defaults)
        val revised = MarkdownRenderer.renderSection(
            Phase.EVENING, fullLog.copy(led = "Actually, I deferred"), defaults
        )!!

        val merged = MarkdownRenderer.mergeInto(existing, revised, Phase.EVENING)

        assertThat(merged.split("## Evening")).hasSize(2)
        assertThat(merged).contains("Actually, I deferred")
        assertThat(merged).doesNotContain("Chose the schema without asking")
        assertThat(merged).contains("## Morning")
    }

    @Test
    fun `merging a morning section into an evening-only file puts morning first`() {
        val eveningOnly = MarkdownRenderer.render(fullLog.copy(mission = null, avoiding = null), defaults)
        val morning = MarkdownRenderer.renderSection(Phase.MORNING, fullLog, defaults)!!

        val merged = MarkdownRenderer.mergeInto(eveningOnly, morning, Phase.MORNING)

        assertThat(merged.indexOf("## Morning")).isLessThan(merged.indexOf("## Evening"))
    }

    @Test
    fun `merging into unrelated content preserves that content`() {
        val existing = "# Daily Anchor - 2026-09-09\n\nSome hand-written note.\n"
        val morning = MarkdownRenderer.renderSection(Phase.MORNING, fullLog, defaults)!!

        val merged = MarkdownRenderer.mergeInto(existing, morning, Phase.MORNING)

        assertThat(merged).contains("Some hand-written note.")
        assertThat(merged).contains("**Mission:**")
    }
}
