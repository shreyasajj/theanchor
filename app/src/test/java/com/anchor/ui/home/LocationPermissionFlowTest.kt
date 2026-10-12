package com.anchor.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocationPermissionFlowTest {

    @Test
    fun `precise location is asked for first`() {
        assertThat(LocationPermissionFlow.next(precise = false, background = false)).isEqualTo(LocationAsk.PRECISE)
    }

    @Test
    fun `approximate plus all the time still needs precise`() {
        // Android 12+ lets "Approximate" be granted with background: fixes are
        // ~2 km wide and every reading would be unknown.
        assertThat(LocationPermissionFlow.next(precise = false, background = true)).isEqualTo(LocationAsk.PRECISE)
    }

    @Test
    fun `precise but only while using asks for all the time`() {
        assertThat(LocationPermissionFlow.next(precise = true, background = false)).isEqualTo(LocationAsk.BACKGROUND)
    }

    @Test
    fun `precise and all the time is done`() {
        assertThat(LocationPermissionFlow.next(precise = true, background = true)).isEqualTo(LocationAsk.NONE)
    }

    @Test
    fun `a denial with no rationale left needs the app settings page`() {
        assertThat(LocationPermissionFlow.needsAppSettings(granted = false, showRationale = false)).isTrue()
    }

    @Test
    fun `a denial Android will still ask about does not`() {
        assertThat(LocationPermissionFlow.needsAppSettings(granted = false, showRationale = true)).isFalse()
    }

    @Test
    fun `a grant never does`() {
        assertThat(LocationPermissionFlow.needsAppSettings(granted = true, showRationale = false)).isFalse()
    }
}
