package com.anchor.data.ha

import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KillSwitchTest {

    private fun okState(state: String) =
        HaResult.Ok(HaStateDto("input_boolean.anchor_override", state, emptyMap()))

    private fun enabledSettings(overrideState: String = "on") = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
        killSwitchEnabled = true,
        killSwitchEntityId = "input_boolean.anchor_override",
        killSwitchOverrideState = overrideState,
    )

    @Test
    fun `matching state means the override is ACTIVE`() = runTest {
        val switch = KillSwitch(FakeHaClient(okState("on")))
        assertThat(switch.check(enabledSettings("on"))).isEqualTo(OverrideStatus.ACTIVE)
    }

    @Test
    fun `non-matching state means INACTIVE`() = runTest {
        val switch = KillSwitch(FakeHaClient(okState("off")))
        assertThat(switch.check(enabledSettings("on"))).isEqualTo(OverrideStatus.INACTIVE)
    }

    @Test
    fun `the override state is configurable to off`() = runTest {
        val switch = KillSwitch(FakeHaClient(okState("off")))
        assertThat(switch.check(enabledSettings("off"))).isEqualTo(OverrideStatus.ACTIVE)
    }

    @Test
    fun `state comparison ignores case and surrounding whitespace`() = runTest {
        val switch = KillSwitch(FakeHaClient(okState("ON")))
        assertThat(switch.check(enabledSettings(" on "))).isEqualTo(OverrideStatus.ACTIVE)
    }

    @Test
    fun `a disabled kill switch is INACTIVE and makes no request`() = runTest {
        val fake = FakeHaClient(okState("on"))
        val status = KillSwitch(fake).check(enabledSettings("on").copy(killSwitchEnabled = false))

        assertThat(status).isEqualTo(OverrideStatus.INACTIVE)
        assertThat(fake.calls).isEmpty()
    }

    @Test
    fun `an enabled kill switch with a blank entity id is INACTIVE`() = runTest {
        val fake = FakeHaClient(okState("on"))
        val status = KillSwitch(fake).check(enabledSettings("on").copy(killSwitchEntityId = "  "))

        assertThat(status).isEqualTo(OverrideStatus.INACTIVE)
        assertThat(fake.calls).isEmpty()
    }

    @Test
    fun `an HA outage is UNKNOWN and does not disable blocking by default`() = runTest {
        val switch = KillSwitch(FakeHaClient(HaResult.Unavailable))
        val status = switch.check(enabledSettings("on"))

        assertThat(status).isEqualTo(OverrideStatus.UNKNOWN)
        assertThat(switch.isBlockingDisabled(status, AnchorSettings())).isFalse()
    }

    @Test
    fun `only ACTIVE disables blocking with default settings`() {
        val switch = KillSwitch(FakeHaClient(HaResult.Unavailable))
        val s = AnchorSettings()
        assertThat(switch.isBlockingDisabled(OverrideStatus.ACTIVE, s)).isTrue()
        assertThat(switch.isBlockingDisabled(OverrideStatus.INACTIVE, s)).isFalse()
        assertThat(switch.isBlockingDisabled(OverrideStatus.UNKNOWN, s)).isFalse()
    }

    @Test
    fun `it queries the configured kill switch entity, not the device tracker`() = runTest {
        val fake = FakeHaClient(okState("off"))
        KillSwitch(fake).check(enabledSettings("on"))
        assertThat(fake.calls).containsExactly("input_boolean.anchor_override")
    }
}
