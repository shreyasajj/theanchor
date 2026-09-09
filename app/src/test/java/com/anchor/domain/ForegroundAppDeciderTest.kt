package com.anchor.domain

import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundAppDeciderTest {

    private val settings = AnchorSettings(
        blockedPackages = setOf("com.google.android.youtube"),
        allowlistPackages = setOf("com.android.dialer", "com.google.android.apps.messaging"),
    )

    private fun decide(pkg: String, locked: Boolean) =
        ForegroundAppDecider.decide(pkg, morningLockActive = locked, settings = settings)

    // --- Morning lockdown active ---

    @Test
    fun `reasserts the lock when an ordinary app comes to the foreground`() {
        assertThat(decide("com.google.android.youtube", locked = true))
            .isEqualTo(ForegroundAction.ReassertMorningLock)
    }

    @Test
    fun `reasserts the lock for the launcher`() {
        assertThat(decide("com.google.android.apps.nexuslauncher", locked = true))
            .isEqualTo(ForegroundAction.ReassertMorningLock)
    }

    @Test
    fun `does NOT interrupt the dialer`() {
        assertThat(decide("com.android.dialer", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt the messaging app`() {
        assertThat(decide("com.google.android.apps.messaging", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt an in-call UI even when not explicitly allowlisted`() {
        assertThat(decide("com.android.incallui", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt the emergency dialer`() {
        assertThat(decide("com.android.emergency", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt system UI`() {
        assertThat(decide("com.android.systemui", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt itself`() {
        assertThat(decide("com.anchor", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt a user-added allowlist entry`() {
        val withTorch = settings.copy(allowlistPackages = settings.allowlistPackages + "com.torch.app")
        assertThat(ForegroundAppDecider.decide("com.torch.app", true, withTorch)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `ignores a blank package name`() {
        assertThat(decide("", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `prefix matching respects the dot boundary`() {
        assertThat(ForegroundAppDecider.isAlwaysAllowed("com.android.dialer.foo")).isTrue()
        assertThat(ForegroundAppDecider.isAlwaysAllowed("com.android.dialerapp")).isFalse()
    }

    // --- No morning lockdown ---

    @Test
    fun `defers to the evening gate when not locked down`() {
        assertThat(decide("com.google.android.youtube", locked = false))
            .isEqualTo(ForegroundAction.EvaluateEvening("com.google.android.youtube"))
    }

    @Test
    fun `an allowlisted app is never evaluated for the evening block`() {
        assertThat(decide("com.android.dialer", locked = false)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `system packages are never evaluated for the evening block`() {
        assertThat(decide("com.android.systemui", locked = false)).isEqualTo(ForegroundAction.Ignore)
    }

    // --- Default allowlist construction ---

    @Test
    fun `the default allowlist includes the resolved dialer and sms apps`() {
        val allowlist = ForegroundAppDecider.defaultAllowlist(
            dialer = "com.android.dialer",
            sms = "com.google.android.apps.messaging",
        )
        assertThat(allowlist).contains("com.android.dialer")
        assertThat(allowlist).contains("com.google.android.apps.messaging")
    }

    @Test
    fun `the default allowlist tolerates unresolvable defaults`() {
        assertThat(ForegroundAppDecider.defaultAllowlist(dialer = null, sms = null)).isEmpty()
    }
}
