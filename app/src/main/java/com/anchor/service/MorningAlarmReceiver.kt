package com.anchor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.data.settings.SettingsRepository
import com.anchor.domain.LockdownEnforcer
import com.anchor.domain.MorningDecision
import com.anchor.domain.MorningGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires when the morning window opens. Asks [MorningGate] whether to lock and,
 * either way, schedules tomorrow's alarm before finishing.
 */
@AndroidEntryPoint
class MorningAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var morningGate: MorningGate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var scheduler: MorningAlarmScheduler
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (morningGate.decide() is MorningDecision.Lock) {
                    enforcer.begin()
                }
                // Always re-arm, so a skipped morning does not stop tomorrow's.
                scheduler.schedule(settingsRepository.current())
            } finally {
                pending.finish()
            }
        }
    }
}
