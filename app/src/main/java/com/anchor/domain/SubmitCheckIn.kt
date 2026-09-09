package com.anchor.domain

import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DailyLogDao
import com.anchor.data.db.Phase
import com.anchor.data.export.CheckInExporter
import com.anchor.data.export.ExportResult
import com.anchor.data.export.withAnswer
import com.anchor.data.settings.SettingsProvider
import javax.inject.Inject
import javax.inject.Singleton

data class SubmitResult(
    val log: DailyLog,
    val export: ExportResult,
)

/**
 * The one path a completed check-in takes: merge the answers into the day's
 * row, stamp the phase's completion timestamp, persist, then export.
 *
 * Persistence happens before export, and an export failure never rolls it
 * back: the database is the source of truth, and losing an answer because a
 * folder permission expired would be unacceptable.
 */
@Singleton
class SubmitCheckIn @Inject constructor(
    private val dailyLogDao: DailyLogDao,
    private val questionDao: CustomQuestionDao,
    private val exporter: CheckInExporter,
    private val anchorDate: AnchorDate,
    private val settingsProvider: SettingsProvider,
) {
    /** @param answers slotKey -> answer, as collected by the lock screen. */
    suspend operator fun invoke(phase: Phase, answers: Map<String, String>): SubmitResult {
        val settings = settingsProvider()

        val date = when (phase) {
            Phase.MORNING -> anchorDate.today()
            Phase.EVENING -> anchorDate.eveningAnchorDate(settings.eveningEndMinute)
        }

        val now = anchorDate.nowMillis()
        val existing = dailyLogDao.findByDate(date) ?: DailyLog(date = date)
        val merged = answers
            .entries
            .fold(existing) { acc, (slot, answer) -> acc.withAnswer(slot, answer) }
            .let {
                when (phase) {
                    Phase.MORNING -> it.copy(morningCompletedAt = now)
                    Phase.EVENING -> it.copy(eveningCompletedAt = now)
                }
            }

        dailyLogDao.upsert(merged)
        val saved = dailyLogDao.findByDate(date) ?: merged

        val export = exporter.export(
            log = saved,
            questions = questionDao.list(phase),
            phase = phase,
            settings = settings,
        )
        return SubmitResult(log = saved, export = export)
    }
}
