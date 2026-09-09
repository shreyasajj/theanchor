package com.anchor.di

import com.anchor.domain.AccessibilityLockdownEnforcer
import com.anchor.domain.LockdownEnforcer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Swap the binding here to change enforcement strategy. A Device Owner
 * lock-task implementation would plug in at this seam.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnforcerModule {

    @Binds
    @Singleton
    abstract fun bindLockdownEnforcer(impl: AccessibilityLockdownEnforcer): LockdownEnforcer
}
