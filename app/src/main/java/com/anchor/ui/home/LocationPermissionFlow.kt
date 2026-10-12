package com.anchor.ui.home

/** The next location permission to ask for, if any. */
enum class LocationAsk { PRECISE, BACKGROUND, NONE }

/**
 * The phone source needs both precise and "all the time" location. Precise
 * comes first: Android 12+ lets "Approximate" be granted, even with
 * background access, and its ~2 km fixes would make every reading unknown.
 */
object LocationPermissionFlow {
    fun next(precise: Boolean, background: Boolean): LocationAsk = when {
        !precise -> LocationAsk.PRECISE
        !background -> LocationAsk.BACKGROUND
        else -> LocationAsk.NONE
    }
}
