package com.anchor.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two full-screen lockdowns the phone can be held in. */
enum class LockKind { MORNING, EVENING_SIT }

/**
 * Which lockdown is currently up, if any. Read by the accessibility service
 * on every foreground-app change, so it must be cheap and lock-free.
 */
object LockdownState {
    private val _kindFlow = MutableStateFlow<LockKind?>(null)
    val kindFlow: StateFlow<LockKind?> = _kindFlow.asStateFlow()

    val kind: LockKind? get() = _kindFlow.value
    val active: Boolean get() = kind != null

    fun begin(kind: LockKind) { _kindFlow.value = kind }
    fun end() { _kindFlow.value = null }
}

/**
 * How a lockdown is actually enforced. The shipped implementation is
 * [AccessibilityLockdownEnforcer]; a Device Owner lock-task implementation
 * could be bound in its place without touching the decision logic.
 */
interface LockdownEnforcer {
    /** Put the lock up and remember that it is up. */
    fun begin(kind: LockKind = LockKind.MORNING)

    /** Take the lock down. */
    fun end()

    val isActive: Boolean

    val activeKind: LockKind?

    /** Bring the lock screen back to the foreground. */
    fun reassert()
}
