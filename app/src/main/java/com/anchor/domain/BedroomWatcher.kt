package com.anchor.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the watcher needs; [BedroomGate] and the usage log answer, tests fake it. */
interface BedroomQueries {
    suspend fun watches(packageName: String): Boolean
    suspend fun isInForeground(packageName: String): Boolean
    suspend fun decide(packageName: String): BedroomDecision
}

/**
 * Catches a blocked app that was already open when you walked into the
 * bedroom. While one is in front it asks [BedroomGate] again every
 * [INTERVAL_MILLIS]. Like [SessionCapWatcher] it trusts no window event:
 * the keyboard, the shade and our own overlay all raise one, so nothing but
 * another blocked app or a lockdown stops it; instead each tick checks the
 * usage log, and stops once the app has left the front.
 */
class BedroomWatcher(
    private val scope: CoroutineScope,
    private val queries: BedroomQueries,
    private val onBlocked: (packageName: String, decision: BedroomDecision.Block) -> Unit,
) {
    private val lock = Mutex()
    private var timer: Job? = null
    private var watched: String? = null

    /** Call on every foreground-app change; only blocked apps arm it. */
    fun onForegroundApp(packageName: String) {
        scope.launch {
            if (!queries.watches(packageName)) return@launch
            lock.withLock {
                if (packageName == watched && timer?.isActive == true) return@launch
                timer?.cancel()
                watched = packageName
                timer = scope.launch { watch(packageName) }
            }
        }
    }

    private suspend fun watch(packageName: String) {
        while (true) {
            delay(INTERVAL_MILLIS)
            if (!queries.isInForeground(packageName)) return
            val decision = queries.decide(packageName)
            if (decision is BedroomDecision.Block) {
                onBlocked(packageName, decision)
                return
            }
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        watched = null
    }

    companion object {
        const val INTERVAL_MILLIS = 2 * 60_000L
    }
}
