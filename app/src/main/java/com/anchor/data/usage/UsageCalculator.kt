package com.anchor.data.usage

/**
 * Derives usage from the raw event log and the recorded opens. Pure by
 * design. Opens are recorded, not derived: the log cannot tell an open from
 * the app flashing in front before the pause covers it. Time and "in front"
 * still come from the log, where the flash adds well under a second.
 */
object UsageCalculator {

    /**
     * How long the user may step away from an open without a session length
     * and come back to the same one. The gate's in-memory view of this lives
     * in [com.anchor.domain.PauseLedger]; this is the same rule read from the
     * log, for where the open was left.
     */
    const val RETURN_GRACE_MILLIS = 60_000L

    private data class Session(val start: Long, val end: Long?)

    /** Single-app convenience. */
    fun summarize(
        events: List<UsageEvent>,
        packageName: String,
        windowStartMillis: Long,
        nowMillis: Long,
        opensMillis: List<Long>,
        sessionWindowMillis: Long? = null,
        earlyLocksMillis: List<Long> = emptyList(),
    ): AppUsageSummary = summarize(
        events, setOf(packageName), windowStartMillis, nowMillis, opensMillis, sessionWindowMillis, earlyLocksMillis,
    )

    /**
     * Whether a return [openAgeMillis] after the open began continues it. A
     * session is wall clock from the open: the app is joinable, unasked, for
     * that long, and afterwards the next entry is a new open, even a second
     * later. Only the daily minute budget counts foreground time.
     */
    fun rejoins(openAgeMillis: Long, sessionWindowMillis: Long): Boolean = openAgeMillis < sessionWindowMillis

    /**
     * @param packageNames the apps sharing one limit; their sessions are
     *   merged and counted as one.
     * @param opensMillis when the pause let the user in, from before the
     *   window too, so a session or cooldown straddling the reset is seen.
     * @param sessionWindowMillis the limit's session length, if it has one:
     *   returns inside it belong to the open.
     * @param earlyLocksMillis times the user locked the app early. One
     *   ends the open it falls in, which then costs half; one after the
     *   latest open also ends that open's run of sessions there.
     */
    fun summarize(
        events: List<UsageEvent>,
        packageNames: Set<String>,
        windowStartMillis: Long,
        nowMillis: Long,
        opensMillis: List<Long>,
        sessionWindowMillis: Long? = null,
        earlyLocksMillis: List<Long> = emptyList(),
    ): AppUsageSummary {
        val sessions = buildSessions(events, packageNames, nowMillis)

        // Time: each session clipped to the window and to now.
        val foregroundMillis = sessions.sumOf { session ->
            val end = minOf(session.end ?: nowMillis, nowMillis)
            (end - maxOf(session.start, windowStartMillis)).coerceAtLeast(0)
        }

        val opens = opensMillis.count { it >= windowStartMillis }
        val openUnits = openUnits(opensMillis, windowStartMillis, earlyLocksMillis)
        val lastOpen = opensMillis.maxOrNull()

        val lastClose = lastOpen?.let { openRunEnd(sessions, it, sessionWindowMillis, earlyLocksMillis) }

        return AppUsageSummary(
            foregroundMillis = foregroundMillis,
            opens = opens,
            openUnits = openUnits,
            lastForegroundEndAtMillis = lastClose,
            currentSessionStartAtMillis = sessions.lastOrNull()?.takeIf { it.end == null }?.start,
            lastOpenStartAtMillis = lastOpen,
        )
    }

    /**
     * What the opens inside the window cost: one each, or half for one the
     * user ended early with the lock button, rewarding stopping before the
     * session made them. A lock belongs to the open it falls in, between
     * that open and the next.
     */
    private fun openUnits(opensMillis: List<Long>, windowStartMillis: Long, earlyLocksMillis: List<Long>): Double {
        val sorted = opensMillis.sorted()
        return sorted.withIndex().sumOf { (i, open) ->
            if (open < windowStartMillis) return@sumOf 0.0
            val next = sorted.getOrNull(i + 1) ?: Long.MAX_VALUE
            if (earlyLocksMillis.any { it >= open && it < next }) EARLY_LOCK_COST else 1.0
        }
    }

    /** What an open ended early with the lock button costs. */
    const val EARLY_LOCK_COST = 0.5

    /**
     * Where the user last left the open that began at [open]. A session is
     * part of the open while it is a return the gate lets straight back in:
     * inside the session when the limit has one, otherwise within the return
     * grace of the previous one. The flash before Continue ends before the
     * open; a later launch's flash, closed from the pause, starts outside it.
     * Neither can start or stretch a cooldown. An early lock ends the run.
     * Null while the open's first session is still running, or if the app
     * never came up after Continue.
     */
    private fun openRunEnd(
        sessions: List<Session>,
        open: Long,
        sessionWindowMillis: Long?,
        earlyLocksMillis: List<Long>,
    ): Long? {
        val lockedAt = earlyLocksMillis.filter { it >= open }.minOrNull()
        var previousEnd = open
        var close: Long? = null
        for (session in sessions) {
            if (session.end != null && session.end <= open) continue
            if (lockedAt != null && session.start > lockedAt) break
            val belongs = if (sessionWindowMillis != null) {
                session.start - open < sessionWindowMillis
            } else {
                session.start - previousEnd < RETURN_GRACE_MILLIS
            }
            if (!belongs || session.end == null) break
            close = session.end
            previousEnd = session.end
        }
        return close
    }

    /**
     * Sessions of every member app, merged where they touch or overlap: a
     * group's members hand over to each other with the next one's FOREGROUND
     * sometimes logged before the previous one's BACKGROUND.
     */
    private fun buildSessions(events: List<UsageEvent>, packageNames: Set<String>, nowMillis: Long): List<Session> {
        val perPackage = events
            .filter { it.packageName in packageNames }
            .groupBy { it.packageName }
            .values
            .flatMap { buildSessionsFor(it) }
            .sortedBy { it.start }
        if (perPackage.size <= 1) return perPackage

        val merged = mutableListOf<Session>()
        perPackage.forEach { session ->
            val last = merged.lastOrNull()
            if (last != null && session.start < (last.end ?: Long.MAX_VALUE)) {
                val end = if (last.end == null || session.end == null) null else maxOf(last.end, session.end)
                merged[merged.lastIndex] = Session(last.start, end)
            } else {
                merged += session
            }
        }
        return merged
    }

    private fun buildSessionsFor(events: List<UsageEvent>): List<Session> {
        val ordered = events.sortedBy { it.timestampMillis }
        val sessions = mutableListOf<Session>()
        var openStart: Long? = null

        ordered.forEach { event ->
            when (event.type) {
                // A second FOREGROUND with no BACKGROUND between is the same
                // session; Android emits these for configuration changes.
                UsageEvent.Type.FOREGROUND ->
                    if (openStart == null) openStart = event.timestampMillis

                UsageEvent.Type.BACKGROUND -> openStart?.let { start ->
                    sessions += Session(start, event.timestampMillis)
                    openStart = null
                }
            }
        }
        openStart?.let { sessions += Session(it, null) }
        return sessions
    }
}
