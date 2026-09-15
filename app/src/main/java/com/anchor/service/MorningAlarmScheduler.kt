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

/** Pure next-fire-time arithmetic, shared by the morning and evening alarms. */
object NextMorningAlarm {

    /**
     * The next instant at which a window opens, strictly after [nowMillis].
     * At exactly the start minute we schedule tomorrow, because "now" means
     * the alarm for today has already fired.
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

    private fun pendingIntent(kind: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, MorningAlarmReceiver::class.java).putExtra(MorningAlarmReceiver.EXTRA_KIND, kind),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Arms the morning alarm, and the evening one when the evening sit is on. */
    fun schedule(settings: AnchorSettings) {
        arm(settings.morningStartMinute, MorningAlarmReceiver.KIND_MORNING, MORNING_REQUEST_CODE)
        if (settings.eveningSitRequired || settings.eveningPromptOnItsOwn) {
            arm(settings.eveningStartMinute, MorningAlarmReceiver.KIND_EVENING, EVENING_REQUEST_CODE)
        } else {
            alarmManager.cancel(pendingIntent(MorningAlarmReceiver.KIND_EVENING, EVENING_REQUEST_CODE))
        }
    }

    private fun arm(startMinute: Int, kind: String, requestCode: Int) {
        val triggerAt = NextMorningAlarm.nextTriggerMillis(
            nowMillis = clock.millis(),
            zone = clock.zone,
            morningStartMinute = startMinute,
        )
        // setAlarmClock survives Doze, which setExactAndAllowWhileIdle does
        // not reliably do on all OEM builds.
        runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, pendingIntent(kind, requestCode)),
                pendingIntent(kind, requestCode),
            )
        }
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent(MorningAlarmReceiver.KIND_MORNING, MORNING_REQUEST_CODE))
        alarmManager.cancel(pendingIntent(MorningAlarmReceiver.KIND_EVENING, EVENING_REQUEST_CODE))
    }

    private companion object {
        const val MORNING_REQUEST_CODE = 4201
        const val EVENING_REQUEST_CODE = 4202
    }
}
