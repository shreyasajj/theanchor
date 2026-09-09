package com.anchor.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionStateTest {

    private val all = PermissionState(
        accessibilityEnabled = true,
        overlayGranted = true,
        usageStatsGranted = true,
        notificationsGranted = true,
        exportFolderChosen = true,
    )

    @Test
    fun `fully configured when everything is granted`() {
        assertThat(all.isFullyConfigured).isTrue()
        assertThat(all.missing).isEmpty()
    }

    @Test
    fun `accessibility is required`() {
        val state = all.copy(accessibilityEnabled = false)
        assertThat(state.isFullyConfigured).isFalse()
        assertThat(state.missing).contains("Accessibility service")
    }

    @Test
    fun `overlay permission is required`() {
        assertThat(all.copy(overlayGranted = false).missing).contains("Display over other apps")
    }

    @Test
    fun `usage access is required`() {
        assertThat(all.copy(usageStatsGranted = false).missing).contains("Usage access")
    }

    @Test
    fun `notifications are required for the foreground service`() {
        assertThat(all.copy(notificationsGranted = false).missing).contains("Notifications")
    }

    @Test
    fun `a missing export folder is listed but does not block operation`() {
        val state = all.copy(exportFolderChosen = false)
        assertThat(state.missing).contains("Markdown export folder")
        assertThat(state.canEnforce).isTrue()
    }

    @Test
    fun `enforcement requires accessibility and overlay only`() {
        assertThat(
            PermissionState(
                accessibilityEnabled = true, overlayGranted = true,
                usageStatsGranted = false, notificationsGranted = false, exportFolderChosen = false,
            ).canEnforce
        ).isTrue()

        assertThat(all.copy(accessibilityEnabled = false).canEnforce).isFalse()
        assertThat(all.copy(overlayGranted = false).canEnforce).isFalse()
    }

    @Test
    fun `missing entries are listed in setup order`() {
        val none = PermissionState(false, false, false, false, false)
        assertThat(none.missing).containsExactly(
            "Accessibility service",
            "Display over other apps",
            "Usage access",
            "Notifications",
            "Markdown export folder",
        ).inOrder()
    }

    @Test
    fun `every missing item has a reason`() {
        PermissionState(false, false, false, false, false).missing.forEach {
            assertThat(PermissionState.reason(it)).isNotEmpty()
        }
    }
}
