package com.anchor.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The seam that keeps UsageStatsManager out of every unit test. */
interface UsageStatsSource {
    /** Never throws; returns an empty list when usage access is not granted. */
    suspend fun events(fromMillis: Long, toMillis: Long): List<UsageEvent>
}

object UsageEventMapping {

    /**
     * How far before the usage-day start to query. A cooldown is a rolling
     * gap, so the last close may have happened before the daily reset and
     * still matter. Six hours comfortably exceeds any sane cooldown.
     */
    const val COOLDOWN_LOOKBACK_MILLIS = 6L * 60 * 60 * 1000

    fun queryFrom(usageDayStartMillis: Long): Long =
        usageDayStartMillis - COOLDOWN_LOOKBACK_MILLIS

    fun map(androidEventType: Int): UsageEvent.Type? = when (androidEventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Type.FOREGROUND
        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.ACTIVITY_STOPPED -> UsageEvent.Type.BACKGROUND
        else -> null
    }
}

/**
 * Reads raw foreground transitions from the system. A revoked usage-access
 * permission yields an empty list, so nothing is blocked by a limit. That is
 * the fail-open rule applied here: a broken permission must not lock the
 * user out of their phone.
 */
@Singleton
class AndroidUsageStatsSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsageStatsSource {

    private val manager: UsageStatsManager =
        context.getSystemService(UsageStatsManager::class.java)

    override suspend fun events(fromMillis: Long, toMillis: Long): List<UsageEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = mutableListOf<UsageEvent>()
                val cursor = manager.queryEvents(fromMillis, toMillis)
                val event = UsageEvents.Event()
                while (cursor.hasNextEvent()) {
                    cursor.getNextEvent(event)
                    val type = UsageEventMapping.map(event.eventType) ?: continue
                    val packageName = event.packageName ?: continue
                    result += UsageEvent(packageName, type, event.timeStamp)
                }
                result
            }.getOrDefault(emptyList())
        }
}
