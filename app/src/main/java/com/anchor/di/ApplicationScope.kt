package com.anchor.di

import javax.inject.Qualifier

/**
 * A scope that lives as long as the process. For writes that must outlive
 * the screen that starts them: the pause records an open and finishes in
 * the same breath, which would cancel anything on its lifecycleScope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
