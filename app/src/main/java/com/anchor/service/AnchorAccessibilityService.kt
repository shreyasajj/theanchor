package com.anchor.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimit
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
import com.anchor.domain.MorningDecision
import com.anchor.domain.MorningGate
import com.anchor.domain.SessionCapWatcher
import com.anchor.domain.SkipReason
import com.anchor.ui.lock.ConfirmLockActivity
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
/**
 * After a pause screen is shown for an app, the app comes back to the front
 * when the countdown ends, which is itself a foreground change. Without this
 * the same launch would be paused again, forever.
 */
class PauseGrace(private val extraMillis: Long = 60_000) {
    private var packageName: String? = null
    private var untilMillis: Long = Long.MIN_VALUE

    fun noteShown(packageName: String, seconds: Int, nowMillis: Long) {
        this.packageName = packageName
        untilMillis = nowMillis + seconds * 1000L + extraMillis
    }

    fun suppresses(packageName: String, nowMillis: Long): Boolean =
        packageName == this.packageName && nowMillis < untilMillis
}

/** Whether the relock button belongs on screen for the app in front. */
object RelockButton {
    fun shouldShowFor(limit: AppLimit?): Boolean = limit != null && limit.enabled && limit.hasAnyLimit
}

/**
 * Throttles the morning re-check to once a minute. The gate's cheap checks
 * (window, already done) cost nothing, but past them it calls Home Assistant.
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
    @Inject lateinit var limitGate: LimitGate
    @Inject lateinit var appLimitDao: AppLimitDao
    @Inject lateinit var anchorDate: AnchorDate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val debounce = PackageDebounce()
    private val pauseGrace = PauseGrace()
    private val morningRecheck = MorningRecheck()

    /** The most recent non-system foreground package, for the relock button. */
    @Volatile private var lastForegroundPackage: String? = null

    /**
     * The accessibility button (the small person icon in the navigation bar,
     * or the floating shortcut) is only requested while a limited app is in
     * front. Tapping it opens a confirmation sheet; confirming locks the app
     * early, which ends its session and makes the next open cost half.
     */
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            val target = lastForegroundPackage ?: return
            startActivity(ConfirmLockActivity.intent(this@AnchorAccessibilityService, target))
        }
    }

    @Volatile private var buttonRequested = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback) }
        setRelockButtonVisible(false)
    }

    /** Toggles the request flag so the system shows the button only when it is useful. */
    private fun setRelockButtonVisible(visible: Boolean) {
        if (buttonRequested == visible) return
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        info.flags = if (visible) info.flags or flag else info.flags and flag.inv()
        runCatching { serviceInfo = info }
        buttonRequested = visible
    }

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

            // The alarm is one shot at the window start. If that moment was
            // missed (not home yet, HA down, phone off), keep asking while the
            // phone is in use during the window, once a minute at most.
            if (!enforcer.isActive && packageName != EveningGate.OWN_PACKAGE &&
                morningRecheck.shouldCheck(System.currentTimeMillis()) &&
                morningGate.decide() is MorningDecision.Lock
            ) {
                enforcer.begin()
                return@launch
            }

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
                    // Our own screens count as Ignore, so the confirmation
                    // sheet keeps the button while it is up.
                    if (packageName != EveningGate.OWN_PACKAGE) setRelockButtonVisible(false)
                }

                is ForegroundAction.ReassertMorningLock -> {
                    sessionCapWatcher.cancel()
                    setRelockButtonVisible(false)
                    // Bypasses the debounce: escaping the lock must always
                    // bring it straight back, however fast the user taps.
                    enforcer.reassert()
                }

                is ForegroundAction.EvaluateEvening -> {
                    val target = action.packageName
                    lastForegroundPackage = target
                    setRelockButtonVisible(RelockButton.shouldShowFor(appLimitDao.find(target)))

                    // The session cap watches every foreground change, even
                    // debounced ones, so its timer tracks reality.
                    sessionCapWatcher.onForegroundApp(target)

                    val now = System.currentTimeMillis()
                    if (!debounce.shouldHandle(target, now)) return@launch
                    if (pauseGrace.suppresses(target, now)) return@launch

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
                        is Route.Pause -> {
                            pauseGrace.noteShown(target, route.seconds, now)
                            startActivity(PauseActivity.intent(this@AnchorAccessibilityService, route.seconds, target))
                        }
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
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback) }
        sessionCapWatcher.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"
    }
}
