package com.anchor.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimitDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.EveningDecision
import com.anchor.domain.EveningGate
import com.anchor.domain.ForegroundAction
import com.anchor.domain.ForegroundAppDecider
import com.anchor.domain.LimitDecision
import com.anchor.domain.LimitGate
import com.anchor.domain.LimitReason
import com.anchor.domain.LockdownEnforcer
import com.anchor.domain.SessionCapWatcher
import com.anchor.domain.SkipReason
import com.anchor.ui.lock.EveningLockActivity
import com.anchor.ui.lock.LimitBlockedActivity
import com.anchor.ui.lock.PauseActivity
import com.anchor.ui.lock.PauseCoalescing
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which screen, if any, a single app launch produces. */
sealed interface Route {
    data object None : Route
    data class Pause(val seconds: Int) : Route
    data class Blocked(val reason: LimitReason, val resetsAtMillis: Long) : Route
    data object StrictEvening : Route
}

/**
 * Combines the two gates into one screen. Order of precedence:
 * hard limit > strict evening overlay > pause. The user never sees two
 * interstitials for a single app launch.
 */
object LimitRouting {
    fun route(limit: LimitDecision, evening: EveningDecision): Route = when {
        limit is LimitDecision.Blocked -> Route.Blocked(limit.reason, limit.resetsAtMillis)
        evening is EveningDecision.Strict -> Route.StrictEvening
        else -> {
            val seconds = PauseCoalescing.secondsFor(evening, limit)
            if (seconds > 0) Route.Pause(seconds) else Route.None
        }
    }
}

/**
 * Suppresses repeat handling of the same package within a short window.
 * Android emits several TYPE_WINDOW_STATE_CHANGED events per app launch;
 * without this the gates would be queried (and HA hit) several times per open.
 */
class PackageDebounce(private val windowMillis: Long = 3_000) {
    private var lastPackage: String? = null
    private var lastAtMillis: Long = Long.MIN_VALUE

    fun shouldHandle(packageName: String, nowMillis: Long): Boolean {
        val repeat = packageName == lastPackage && nowMillis - lastAtMillis < windowMillis
        lastPackage = packageName
        lastAtMillis = nowMillis
        return !repeat
    }

    fun clear() {
        lastPackage = null
        lastAtMillis = Long.MIN_VALUE
    }
}

/**
 * Watches foreground-app changes and routes them through the decision engine.
 * Contains no policy of its own: see [ForegroundAppDecider], [LimitGate] and
 * [EveningGate].
 */
@AndroidEntryPoint
class AnchorAccessibilityService : AccessibilityService() {

    @Inject lateinit var eveningGate: EveningGate
    @Inject lateinit var limitGate: LimitGate
    @Inject lateinit var appLimitDao: AppLimitDao
    @Inject lateinit var anchorDate: AnchorDate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val debounce = PackageDebounce()

    private val sessionCapWatcher by lazy {
        SessionCapWatcher(
            scope = scope,
            limitGate = limitGate,
            appLimitDao = appLimitDao,
            anchorDate = anchorDate,
            onCapReached = { _, now ->
                startActivity(LimitBlockedActivity.intent(this, LimitReason.SESSION_CAP, now))
            },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        scope.launch {
            val settings = settingsRepository.current()
            when (
                val action = ForegroundAppDecider.decide(
                    packageName = packageName,
                    morningLockActive = enforcer.isActive,
                    settings = settings,
                )
            ) {
                is ForegroundAction.Ignore -> {
                    // Leaving a capped app for the dialer must stop its timer.
                    sessionCapWatcher.cancel()
                }

                is ForegroundAction.ReassertMorningLock -> {
                    sessionCapWatcher.cancel()
                    // Bypasses the debounce: escaping the lock must always
                    // bring it straight back, however fast the user taps.
                    enforcer.reassert()
                }

                is ForegroundAction.EvaluateEvening -> {
                    val target = action.packageName

                    // The session cap watches every foreground change, even
                    // debounced ones, so its timer tracks reality.
                    sessionCapWatcher.onForegroundApp(target)

                    if (!debounce.shouldHandle(target, System.currentTimeMillis())) return@launch

                    // Limits first: a spent budget outranks the evening ritual.
                    val limit = limitGate.decide(target)
                    val evening = if (limit is LimitDecision.Blocked) {
                        EveningDecision.Allow(SkipReason.NOT_IN_SCOPE)   // not consulted
                    } else {
                        eveningGate.decide(target)
                    }

                    when (val route = LimitRouting.route(limit, evening)) {
                        is Route.None -> Unit
                        is Route.StrictEvening -> launchLock(EveningLockActivity::class.java, target)
                        is Route.Pause ->
                            startActivity(PauseActivity.intent(this@AnchorAccessibilityService, route.seconds, target))
                        is Route.Blocked ->
                            startActivity(
                                LimitBlockedActivity.intent(
                                    this@AnchorAccessibilityService, route.reason, route.resetsAtMillis,
                                )
                            )
                    }
                }
            }
        }
    }

    private fun launchLock(target: Class<*>, blockedPackage: String) {
        startActivity(
            Intent(this, target).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
            }
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        sessionCapWatcher.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"
    }
}
