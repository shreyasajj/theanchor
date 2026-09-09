package com.anchor.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the morning lock screen is currently up. Read by the accessibility
 * service on every foreground-app change, so it must be cheap and lock-free.
 */
object LockdownState {
    private val _activeFlow = MutableStateFlow(false)
    val activeFlow: StateFlow<Boolean> = _activeFlow.asStateFlow()

    val active: Boolean get() = _activeFlow.value

    fun begin() { _activeFlow.value = true }
    fun end() { _activeFlow.value = false }
}

/**
 * How the morning lockdown is actually enforced. The shipped implementation
 * is [AccessibilityLockdownEnforcer]; a Device Owner lock-task implementation
 * could be bound in its place without touching the decision logic.
 */
interface LockdownEnforcer {
    /** Put the lock up and remember that it is up. */
    fun begin()

    /** Take the lock down. */
    fun end()

    val isActive: Boolean

    /** Bring the lock screen back to the foreground. */
    fun reassert()
}
