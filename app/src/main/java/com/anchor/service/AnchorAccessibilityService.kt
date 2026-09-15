package com.anchor.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimit
import com.anchor.domain.AnchorDate
import com.anchor.domain.EveningDecision
import com.anchor.domain.EveningGate
import com.anchor.domain.EveningSitGate
import com.anchor.domain.ForegroundAction
import com.anchor.domain.ForegroundAppDecider
import com.anchor.domain.LimitDecision
import com.anchor.domain.LimitGate
import com.anchor.domain.LimitReason
import com.anchor.domain.LockKind
import com.anchor.domain.LockdownEnforcer
import com.anchor.domain.MorningDecision
import com.anchor.domain.MorningGate
import com.anchor.domain.PauseLedger
import com.anchor.domain.SessionCapWatcher
import com.anchor.domain.SitDecision
import com.anchor.domain.SkipReason
import com.anchor.ui.lock.ConfirmLockActivity
import com.anchor.ui.lock.EveningLockActivity
import com.anchor.ui.lock.LimitBlockedActivity
import com.anchor.ui.lock.PauseActivity
import com.anchor.ui.lock.SessionEndScreens
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
        PauseCoalescing.wanted(evening, limit) -> Route.Pause(PauseCoalescing.secondsFor(evening, limit))
        else -> Route.None
    }
}

/** Whether the relock button belongs on screen for the app in front. */
object RelockButton {
    fun shouldShowFor(limit: AppLimit?, enabledInSettings: Boolean = true): Boolean =
        enabledInSettings && limit != null && limit.isActive
}

/**
 * What the pause and blocked screens key their ledgers by: the limit's
 * subject when the app has one (so a group shares the debt), otherwise the
 * package, for an evening-only pause.
 */
object PauseSubject {
    fun of(packageName: String, limit: AppLimit?): String = limit?.subject ?: packageName
}

/**
 * Throttles the lockdown re-checks to once a minute. The gates' cheap checks
 * (window, already done) cost nothing, but past them they call Home Assistant.
 */
class MorningRecheck(private val intervalMillis: Long = 60_000) {
    private var lastAtMillis: Long? = null

    fun shouldCheck(nowMillis: Long): Boolean {
        val last = lastAtMillis
        if (last != null && nowMillis - last < intervalMillis) return false
        lastAtMillis = nowMillis
        return true
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
    @Inject lateinit var morningGate: MorningGate
    @Inject lateinit var eveningSitGate: EveningSitGate
    @Inject lateinit var limitGate: LimitGate
    @Inject lateinit var anchorDate: AnchorDate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val debounce = PackageDebounce()
    private val lockRecheck = MorningRecheck()

    /** The most recent non-system foreground package, for the relock button. */
    @Volatile private var lastForegroundPackage: String? = null

    /**
     * Our own floating lock button, rather than Android's accessibility
     * shortcut. The system shortcut is assigned by the user and its button is
     * shown by the system, so a service has no dependable way to hide it for
     * one app and show it for another; toggling
     * FLAG_REQUEST_ACCESSIBILITY_BUTTON at runtime did not hide it in
     * practice. An overlay we own appears exactly where it is useful.
     *
     * Tapping it opens a confirmation sheet; confirming ends the app's session
     * early, exactly as if its time had run out.
     */
    private val relockBubble: RelockBubble by lazy {
        RelockBubble(this, ::onBubbleTapped)
    }

    /** The button was tapped: swap it for the confirmation sheet. */
    private fun onBubbleTapped(target: String) {
        relockBubble.hideNow()
        startActivity(ConfirmLockActivity.intent(this, target))
    }

    private val sessionCapWatcher by lazy {
        SessionCapWatcher(
            scope = scope,
            queries = limitGate,
            anchorDate = anchorDate,
            onCapReached = { packageName, _ ->
                relockBubble.hideNow()
                scope.launch {
                    // The session is over: ask again, with the pause. Unless a
                    // new open cannot happen, in which case say why.
                    SessionEndScreens.intentFor(this@AnchorAccessibilityService, limitGate, packageName)
                        ?.let { startActivity(it) }
                }
            },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        scope.launch {
            val settings = settingsRepository.current()

            // The alarms are one shot at each window start. If that moment
            // was missed (not home yet, HA down, phone off), keep asking
            // while the phone is in use during the window, once a minute.
            if (!enforcer.isActive && packageName != EveningGate.OWN_PACKAGE &&
                lockRecheck.shouldCheck(System.currentTimeMillis())
            ) {
                when {
                    morningGate.decide() is MorningDecision.Lock -> {
                        enforcer.begin(LockKind.MORNING)
                        return@launch
                    }
                    eveningSitGate.decide() is SitDecision.Lock -> {
                        enforcer.begin(LockKind.EVENING_SIT)
                        return@launch
                    }
                    // Not a lockdown, but asked again a minute later if it
                    // was dismissed, so a spent limit never buries it.
                    eveningGate.decideUnprompted() is EveningDecision.Strict -> {
                        relockBubble.hideNow()
                        startActivity(EveningLockActivity.unpromptedIntent(this@AnchorAccessibilityService))
                        return@launch
                    }
                }
            }

            when (
                val action = ForegroundAppDecider.decide(
                    packageName = packageName,
                    lockActive = enforcer.isActive,
                    settings = settings,
                )
            ) {
                is ForegroundAction.Ignore -> {
                    // Neither the session timer nor the button is touched
                    // here. Ignore covers system windows, the keyboard and
                    // toasts, which come and go in an instant, and our own
                    // overlay; none of them mean the user left. The timer
                    // checks the usage log when it fires, which is how a
                    // phone call is told apart from the keyboard.
                    //
                    // The button is deliberately left alone here. Ignore covers
                    // system windows, the keyboard and toasts, which come and
                    // go in an instant, and -- the trap -- our own overlay:
                    // adding the button raises a window event for com.anchor,
                    // so hiding on that made the button hide itself about a
                    // second after appearing. Departures arrive instead as
                    // EvaluateEvening for the launcher or the next app, and
                    // our own full-screen screens hide it where they are
                    // launched, below.
                }

                is ForegroundAction.ReassertLock -> {
                    sessionCapWatcher.cancel()
                    relockBubble.hide()
                    // Bypasses the debounce: escaping the lock must always
                    // bring it straight back, however fast the user taps.
                    enforcer.reassert()
                }

                is ForegroundAction.EvaluateEvening -> {
                    val target = action.packageName
                    lastForegroundPackage = target
                    val limit = limitGate.limitFor(target)
                    if (RelockButton.shouldShowFor(limit, settings.showRelockBubble)) {
                        relockBubble.show(target)
                    } else {
                        relockBubble.hide()
                    }

                    // The session cap watches every foreground change, even
                    // debounced ones, so its timer tracks reality.
                    sessionCapWatcher.onForegroundApp(target)

                    val now = System.currentTimeMillis()
                    val subject = PauseSubject.of(target, limit)

                    // Just locked early: the app is on its way out, not in.
                    if (PauseLedger.isIgnored(subject, now)) return@launch

                    // A pause the user actually sat through lets the app in.
                    // One they walked away from does not, and clears the
                    // debounce so the retry is not swallowed as a repeat.
                    if (PauseLedger.isSatisfied(subject, now)) return@launch
                    if (PauseLedger.consumeAbandonment(subject)) debounce.clear()
                    if (!debounce.shouldHandle(target, now)) return@launch

                    // Limits first: a spent budget outranks the evening ritual.
                    val decision = limitGate.decide(target)
                    val evening = if (decision is LimitDecision.Blocked) {
                        EveningDecision.Allow(SkipReason.NOT_IN_SCOPE)   // not consulted
                    } else {
                        eveningGate.decide(target)
                    }

                    when (val route = LimitRouting.route(decision, evening)) {
                        is Route.None -> Unit
                        is Route.StrictEvening -> {
                            relockBubble.hideNow()
                            launchLock(EveningLockActivity::class.java, target)
                        }
                        is Route.Pause -> {
                            relockBubble.hideNow()
                            startActivity(
                                PauseActivity.intent(this@AnchorAccessibilityService, route.seconds, target, subject)
                            )
                        }
                        is Route.Blocked -> {
                            relockBubble.hideNow()
                            startActivity(
                                LimitBlockedActivity.intent(
                                    this@AnchorAccessibilityService, route.reason, route.resetsAtMillis, target,
                                )
                            )
                        }
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
        relockBubble.hideNow()
        sessionCapWatcher.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"
    }
}
