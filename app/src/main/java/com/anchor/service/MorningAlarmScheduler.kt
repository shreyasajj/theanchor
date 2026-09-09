package com.anchor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.anchor.data.settings.AnchorSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Pure next-fire-time arithmetic. */
object NextMorningAlarm {

    /**
     * The next instant at which the morning window opens, strictly after
     * [nowMillis]. At exactly the start minute we schedule tomorrow, because
     * "now" means the alarm for today has already fired.
     */
    fun nextTriggerMillis(nowMillis: Long, zone: ZoneId, morningStartMinute: Int): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val startTime = LocalTime.of(morningStartMinute / 60, morningStartMinute % 60)
        val todayStart = now.toLocalDate().atTime(startTime)
        val target = if (now.isBefore(todayStart)) todayStart else todayStart.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }
}

@Singleton
class MorningAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, MorningAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(settings: AnchorSettings) {
        val triggerAt = NextMorningAlarm.nextTriggerMillis(
            nowMillis = clock.millis(),
            zone = clock.zone,
            morningStartMinute = settings.morningStartMinute,
        )
        // setAlarmClock survives Doze, which setExactAndAllowWhileIdle does
        // not reliably do on all OEM builds.
        runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, pendingIntent()),
                pendingIntent(),
            )
        }
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent())
    }

    private companion object {
        const val REQUEST_CODE = 4201
    }
}
