package com.anchor.data.usage

/**
 * Derives usage from the raw event log and the recorded opens. Pure by
 * design. Opens are recorded, not derived: the log cannot tell an open from
 * the app flashing in front before the pause covers it. Time and "in front"
 * still come from the log, where the flash adds well under a second.
 */
object UsageCalculator {

    private data class Session(val start: Long, val end: Long?)

    /** Single-app convenience. */
    fun summarize(
        events: List<UsageEvent>,
        packageName: String,
        windowStartMillis: Long,
        nowMillis: Long,
        opensMillis: List<Long>,
    ): AppUsageSummary = summarize(events, setOf(packageName), windowStartMillis, nowMillis, opensMillis)

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
     */
    fun summarize(
        events: List<UsageEvent>,
        packageNames: Set<String>,
        windowStartMillis: Long,
        nowMillis: Long,
        opensMillis: List<Long>,
    ): AppUsageSummary {
        val sessions = buildSessions(events, packageNames, nowMillis)

        // Time: each session clipped to the window and to now.
        val foregroundMillis = sessions.sumOf { session ->
            val end = minOf(session.end ?: nowMillis, nowMillis)
            (end - maxOf(session.start, windowStartMillis)).coerceAtLeast(0)
        }

        val opens = opensMillis.count { it >= windowStartMillis }
        val lastOpen = opensMillis.maxOrNull()

        // Only a departure after the open is a close; the flash before it is not.
        val lastClose = lastOpen?.let { open ->
            sessions.lastOrNull { it.end != null && it.end > open }?.end
        }

        return AppUsageSummary(
            foregroundMillis = foregroundMillis,
            opens = opens,
            openUnits = opens.toDouble(),
            lastForegroundEndAtMillis = lastClose,
            currentSessionStartAtMillis = sessions.lastOrNull()?.takeIf { it.end == null }?.start,
            lastOpenStartAtMillis = lastOpen,
        )
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
