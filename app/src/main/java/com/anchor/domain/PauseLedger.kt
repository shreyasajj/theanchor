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

    /** A pause screen went up for this app. */
    fun begin(packageName: String) {
        if (packageName.isEmpty()) return
        unfinished[packageName] = true
        satisfiedUntil.remove(packageName)
    }

    /** The user sat through the wait, or meditated instead. Let them in. */
    fun complete(packageName: String, nowMillis: Long) {
        if (packageName.isEmpty()) return
        satisfiedUntil[packageName] = nowMillis + SATISFIED_WINDOW_MILLIS
        unfinished.remove(packageName)
        abandoned.remove(packageName)
    }

    /**
     * The pause screen went away without being finished: the user pressed
     * home, switched away, or backed out. The pause is still owed.
     */
    fun abandon(packageName: String) {
        if (packageName.isEmpty()) return
        satisfiedUntil.remove(packageName)
        abandoned[packageName] = true
    }

    fun isSatisfied(packageName: String, nowMillis: Long): Boolean =
        (satisfiedUntil[packageName] ?: Long.MIN_VALUE) > nowMillis

    /**
     * True while a pause has been shown for this app but never sat through.
     * [LimitGate] uses it to refuse a session rejoin: an open whose pause was
     * never served is not an open worth continuing.
     */
    fun hasUnfinishedPause(packageName: String): Boolean = unfinished.containsKey(packageName)

    /**
     * True once per abandonment. The service uses it to clear its debounce, so
     * a quick return is re-paused immediately rather than being swallowed as a
     * repeat event.
     */
    fun consumeAbandonment(packageName: String): Boolean = abandoned.remove(packageName) != null

    /** Test seam. */
    fun reset() {
        satisfiedUntil.clear()
        unfinished.clear()
        abandoned.clear()
    }
}
