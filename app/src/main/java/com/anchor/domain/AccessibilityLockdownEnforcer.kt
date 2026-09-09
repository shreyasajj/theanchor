package com.anchor.domain

import android.content.Context
import android.content.Intent
import com.anchor.ui.lock.MorningLockActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Relaunch-based enforcement: whenever the accessibility service sees another
 * app reach the foreground while [LockdownState] is active, it calls
 * [reassert], which brings the lock Activity straight back.
 *
 * Known limitation, accepted for a personal app: the user sees a brief flash
 * of whatever they switched to.
 */
@Singleton
class AccessibilityLockdownEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
) : LockdownEnforcer {

    override fun begin() {
        LockdownState.begin()
        reassert()
    }

    override fun end() {
        LockdownState.end()
    }

    override val isActive: Boolean get() = LockdownState.active

    override fun reassert() {
        val intent = Intent(context, MorningLockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        context.startActivity(intent)
    }
}
