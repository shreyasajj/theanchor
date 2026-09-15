package com.anchor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.data.settings.SettingsRepository
import com.anchor.domain.EveningDecision
import com.anchor.domain.EveningGate
import com.anchor.domain.EveningSitGate
import com.anchor.domain.LockKind
import com.anchor.domain.LockdownEnforcer
import com.anchor.domain.SitDecision
import com.anchor.ui.lock.EveningLockActivity
import com.anchor.domain.MorningDecision
import com.anchor.domain.MorningGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires when the morning window opens, and when the evening window opens if
 * the evening sit is on. Asks the matching gate whether to lock and, either
 * way, schedules the next alarms before finishing.
 */
@AndroidEntryPoint
class MorningAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var morningGate: MorningGate
    @Inject lateinit var eveningSitGate: EveningSitGate
    @Inject lateinit var eveningGate: EveningGate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var scheduler: MorningAlarmScheduler
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_MORNING
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (!enforcer.isActive) {
                    when (kind) {
                        KIND_EVENING -> when {
                            eveningSitGate.decide() is SitDecision.Lock -> enforcer.begin(LockKind.EVENING_SIT)
                            eveningGate.decideUnprompted() is EveningDecision.Strict ->
                                context.startActivity(EveningLockActivity.unpromptedIntent(context))
                        }
                        else ->
                            if (morningGate.decide() is MorningDecision.Lock) enforcer.begin(LockKind.MORNING)
                    }
                }
                // Always re-arm, so a skipped morning does not stop tomorrow's.
                scheduler.schedule(settingsRepository.current())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "com.anchor.extra.ALARM_KIND"
        const val KIND_MORNING = "morning"
        const val KIND_EVENING = "evening"
    }
}
