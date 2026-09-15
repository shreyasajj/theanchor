package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
import com.anchor.domain.LimitDecision
import com.anchor.domain.LimitGate
import com.anchor.service.PauseSubject

/**
 * The screen that follows a session ending, whether the clock ran out or
 * the user pressed the lock button: the pause (a question, or a countdown)
 * and Continue is a new open, or the blocked screen when a new open cannot
 * happen. One place, so both roads lead to the same door.
 */
object SessionEndScreens {
    suspend fun intentFor(context: Context, limitGate: LimitGate, packageName: String): Intent? {
        val subject = PauseSubject.of(packageName, limitGate.limitFor(packageName))
        return when (val next = limitGate.afterSessionCap(packageName)) {
            is LimitDecision.Pause -> PauseActivity.intent(context, next.seconds, packageName, subject)
            is LimitDecision.Blocked -> LimitBlockedActivity.intent(context, next.reason, next.resetsAtMillis, packageName)
            LimitDecision.Allow -> null
        }
    }
}
