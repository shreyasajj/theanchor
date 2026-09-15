package com.anchor.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which pauses were actually *finished*, so leaving the pause
 * screen and coming back cannot be used to skip it.
 *
 * The accessibility service launches the pause and then sees the app reach the
 * foreground again when the pause ends, which would pause it forever. The fix
 * is not to suppress for a while after *showing* a pause (that is the bypass:
 * walk away, come back, the suppression is still running). It is to suppress
 * only after the user has sat through it.
 *
 * Keyed by the pause's *subject*: a limit's [com.anchor.data.usage.AppLimit.subject]
 * for a limited app, so every member of a group shares the debt, or the
 * package name for an evening-only pause.
 *
 * Written by the pause and meditation screens, read by the service and by
 * [LimitGate]. All three live in the same process; this is deliberately a
 * plain object, like [LockdownState].
 */
object PauseLedger {

    /**
     * How long a finished pause keeps the app free. Only long enough to cover
     * the hand-off back to the app; after that the usual open and rejoin rules
     * decide, so a genuinely new open is paused again.
     */
    const val SATISFIED_WINDOW_MILLIS = 60_000L

    private val satisfiedUntil = ConcurrentHashMap<String, Long>()

    /** Shown but not yet sat through. Cleared only by finishing one. */
    private val unfinished = ConcurrentHashMap<String, Boolean>()

    private val abandoned = ConcurrentHashMap<String, Boolean>()

    /**
     * After an early lock the app is on its way out: the confirmation sheet
     * closes over it and home is launched, and in between the app is in
     * front for a moment. Evaluating that moment showed the pause to someone
     * who had just chosen to leave. Ignore the subject briefly instead.
     */
    private val ignoredUntil = ConcurrentHashMap<String, Long>()
    const val EARLY_LOCK_GRACE_MILLIS = 5_000L

    fun ignoreAfterEarlyLock(subject: String, nowMillis: Long) {
        if (subject.isEmpty()) return
        ignoredUntil[subject] = nowMillis + EARLY_LOCK_GRACE_MILLIS
        satisfiedUntil.remove(subject)
    }

    fun isIgnored(subject: String, nowMillis: Long): Boolean =
        (ignoredUntil[subject] ?: Long.MIN_VALUE) > nowMillis

    /** A pause screen went up for this app. */
    fun begin(subject: String) {
        if (subject.isEmpty()) return
        unfinished[subject] = true
        // The lock's grace was for the moment before this screen; a walk-away
        // from here must be judged by the gate, not waved through.
        ignoredUntil.remove(subject)
        satisfiedUntil.remove(subject)
    }

    /** The user sat through the wait, or meditated instead. Let them in. */
    fun complete(subject: String, nowMillis: Long) {
        if (subject.isEmpty()) return
        satisfiedUntil[subject] = nowMillis + SATISFIED_WINDOW_MILLIS
        unfinished.remove(subject)
        abandoned.remove(subject)
    }

    /**
     * The pause screen went away without being finished: the user pressed
     * home, switched away, or backed out. The pause is still owed.
     */
    fun abandon(subject: String) {
        if (subject.isEmpty()) return
        satisfiedUntil.remove(subject)
        abandoned[subject] = true
    }

    fun isSatisfied(subject: String, nowMillis: Long): Boolean =
        (satisfiedUntil[subject] ?: Long.MIN_VALUE) > nowMillis

    /**
     * True while a pause has been shown for this app but never sat through.
     * [LimitGate] uses it to refuse a session rejoin: an open whose pause was
     * never served is not an open worth continuing.
     */
    fun hasUnfinishedPause(subject: String): Boolean = unfinished.containsKey(subject)

    /**
     * True once per abandonment. The service uses it to clear its debounce, so
     * a quick return is re-paused immediately rather than being swallowed as a
     * repeat event.
     */
    fun consumeAbandonment(subject: String): Boolean = abandoned.remove(subject) != null

    /** Test seam. */
    fun reset() {
        satisfiedUntil.clear()
        unfinished.clear()
        abandoned.clear()
        ignoredUntil.clear()
    }
}
