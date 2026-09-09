package com.anchor.data.settings

/**
 * Reads a fresh settings snapshot. Gates take this rather than the
 * repository so tests can hand them a constant, and so no gate is tempted
 * to cache a snapshot across decisions.
 */
fun interface SettingsProvider {
    suspend operator fun invoke(): AnchorSettings
}
