package com.anchor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Waits, in real time, until [condition] holds. ViewModel tests need this
 * because Room and DataStore resume their suspend calls on real threads that
 * a virtual-time test scheduler cannot advance.
 */
suspend fun awaitUntil(timeoutMillis: Long = 5_000, condition: suspend () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(15)
        }
    }
}
