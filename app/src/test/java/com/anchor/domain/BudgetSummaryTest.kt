package com.anchor.domain

import com.anchor.data.usage.LimitMode
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppUsageSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BudgetSummaryTest {

    private val app = "com.google.android.youtube"
    private val minute = 60_000L
    private val now = 1_757_000_000_000L

    @Test
    fun `an unlimited app has nothing to say`() {
        assertThat(BudgetSummary.describe(null, AppUsageSummary())).isEmpty()
        assertThat(BudgetSummary.describe(AppLimit(app), AppUsageSummary())).isEmpty()
    }

    @Test
    fun `a disabled limit says nothing`() {
        val limit = AppLimit(app, enabled = false, limitMode = LimitMode.TIME, dailyMinutes = 30)
        assertThat(BudgetSummary.describe(limit, AppUsageSummary())).isEmpty()
    }

    @Test
    fun `it reports the minutes left today`() {
        val limit = AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30)
        val summary = AppUsageSummary(foregroundMillis = 12 * minute)

        assertThat(BudgetSummary.describe(limit, summary)).containsExactly("18 of 30 minutes left today")
    }

    @Test
    fun `minutes left never goes negative`() {
        val limit = AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30)
        val summary = AppUsageSummary(foregroundMillis = 45 * minute)

        assertThat(BudgetSummary.describe(limit, summary)).containsExactly("0 of 30 minutes left today")
    }

    @Test
    fun `it reports the opens left today`() {
        val limit = AppLimit(app, dailyOpens = 5)
        val summary = AppUsageSummary(opens = 2, openUnits = 2.0)

        assertThat(BudgetSummary.describe(limit, summary)).containsExactly("3 of 5 opens left today")
    }

    @Test
    fun `fractional open units are shown as they are`() {
        val limit = AppLimit(app, dailyOpens = 5)
        val summary = AppUsageSummary(opens = 3, openUnits = 2.5)

        assertThat(BudgetSummary.describe(limit, summary)).containsExactly("2.5 of 5 opens left today")
    }

    @Test
    fun `it reports the time left in this session`() {
        val limit = AppLimit(app, sessionMinutes = 10)
        val summary = AppUsageSummary(currentSessionStartAtMillis = now - 4 * minute, lastOpenStartAtMillis = now - 4 * minute)

        assertThat(BudgetSummary.describe(limit, summary, now))
            .containsExactly("6 of 10 minutes left in this session")
    }

    @Test
    fun `with no session yet it states the cap instead`() {
        val limit = AppLimit(app, sessionMinutes = 10)

        assertThat(BudgetSummary.describe(limit, AppUsageSummary(), now))
            .containsExactly("10 minutes per session")
    }

    @Test
    fun `every configured mechanic gets its own line, in a fixed order`() {
        val limit = AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30, dailyOpens = 5, sessionMinutes = 10)
        val summary = AppUsageSummary(
            foregroundMillis = 10 * minute,
            openUnits = 1.0,
            currentSessionStartAtMillis = now - 2 * minute,
            lastOpenStartAtMillis = now - 2 * minute,
        )

        // Time mode: the open budget is not enforced, so it is not reported.
        assertThat(BudgetSummary.describe(limit, summary, now)).containsExactly(
            "20 of 30 minutes left today",
            "8 of 10 minutes left in this session",
        ).inOrder()

        val byOpens = limit.copy(limitMode = LimitMode.OPENS)
        assertThat(BudgetSummary.describe(byOpens, summary, now)).containsExactly(
            "4 of 5 opens left today",
            "8 of 10 minutes left in this session",
        ).inOrder()
    }

    @Test
    fun `a cooldown alone has no budget to report`() {
        assertThat(BudgetSummary.describe(AppLimit(app, cooldownMinutes = 20), AppUsageSummary())).isEmpty()
    }

    @Test
    fun `halves and whole numbers format differently`() {
        assertThat(BudgetSummary.formatOpens(2.0)).isEqualTo("2")
        assertThat(BudgetSummary.formatOpens(2.5)).isEqualTo("2.5")
        assertThat(BudgetSummary.formatOpens(0.0)).isEqualTo("0")
    }
}
