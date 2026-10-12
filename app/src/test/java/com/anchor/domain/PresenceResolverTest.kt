package com.anchor.domain

import com.anchor.data.db.Phase
import com.anchor.data.ha.FakeHaClient
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.ha.Presence
import com.anchor.data.location.FakePhoneLocationSource
import com.anchor.data.location.SavedPlace
import com.anchor.data.location.fixAt
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.LocationSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PresenceResolverTest {

    private val home = SavedPlace(37.7749, -122.4194, 150)
    private val cabin = SavedPlace(39.0968, -120.0324, 150)
    private val atHome = fixAt(37.7749, -122.4194)
    private val atCabin = fixAt(39.0968, -120.0324)

    private val ha = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
    )

    private fun haSays(state: String) = FakeHaClient(HaResult.Ok(HaStateDto("device_tracker.pixel", state)))

    @Test
    fun `Home Assistant source asks HA and never the phone`() = runTest {
        val client = haSays("home")
        val phone = FakePhoneLocationSource(atHome)
        val result = PresenceResolver(client, phone).resolve(ha, Phase.MORNING)
        assertThat(result).isEqualTo(Presence.IN_SCOPE)
        assertThat(client.calls).containsExactly("device_tracker.pixel")
        assertThat(phone.calls).isEqualTo(0)
    }

    @Test
    fun `Home Assistant source uses the phase's state list`() = runTest {
        val settings = ha.copy(morningHomeStates = listOf("Bedroom"), eveningHomeStates = listOf("Office"))
        val resolver = PresenceResolver(haSays("Office"), FakePhoneLocationSource())
        assertThat(resolver.resolve(settings, Phase.MORNING)).isEqualTo(Presence.OUT_OF_SCOPE)
        assertThat(resolver.resolve(settings, Phase.EVENING)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `Home Assistant source uses the phase's mode and rooms`() = runTest {
        val settings = ha.copy(
            eveningLocationMode = LocationMode.SPECIFIC_ROOMS,
            eveningAllowedRooms = listOf("Bedroom"),
        )
        val resolver = PresenceResolver(haSays("Bedroom"), FakePhoneLocationSource())
        assertThat(resolver.resolve(settings, Phase.EVENING)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `phone source compares the phone with the home place and never asks HA`() = runTest {
        val client = haSays("not_home")
        val settings = ha.copy(locationSource = LocationSource.PHONE, homePlace = home)
        val inside = PresenceResolver(client, FakePhoneLocationSource(atHome)).resolve(settings, Phase.MORNING)
        val outside = PresenceResolver(client, FakePhoneLocationSource(atCabin)).resolve(settings, Phase.EVENING)
        assertThat(inside).isEqualTo(Presence.IN_SCOPE)
        assertThat(outside).isEqualTo(Presence.OUT_OF_SCOPE)
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `phone source with no home place is unknown and does not wake the GPS`() = runTest {
        val phone = FakePhoneLocationSource(atHome)
        val settings = AnchorSettings(locationSource = LocationSource.PHONE)
        assertThat(PresenceResolver(haSays("home"), phone).resolve(settings, Phase.MORNING))
            .isEqualTo(Presence.UNKNOWN)
        assertThat(phone.calls).isEqualTo(0)
    }

    @Test
    fun `phone source with no fix is unknown`() = runTest {
        val settings = AnchorSettings(locationSource = LocationSource.PHONE, homePlace = home)
        assertThat(PresenceResolver(haSays("home"), FakePhoneLocationSource(null)).resolve(settings, Phase.MORNING))
            .isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `vacation mode uses the vacation place, not home and not HA`() = runTest {
        val client = haSays("home")
        val settings = ha.copy(
            locationSource = LocationSource.PHONE,
            homePlace = home,
            vacationMode = true,
            vacationPlace = cabin,
        )
        assertThat(PresenceResolver(client, FakePhoneLocationSource(atCabin)).resolve(settings, Phase.MORNING))
            .isEqualTo(Presence.IN_SCOPE)
        assertThat(PresenceResolver(client, FakePhoneLocationSource(atHome)).resolve(settings, Phase.MORNING))
            .isEqualTo(Presence.OUT_OF_SCOPE)
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `vacation mode without a place falls through to Home Assistant`() = runTest {
        val client = haSays("home")
        val phone = FakePhoneLocationSource(atCabin)
        val settings = ha.copy(vacationMode = true)
        assertThat(PresenceResolver(client, phone).resolve(settings, Phase.MORNING)).isEqualTo(Presence.IN_SCOPE)
        assertThat(phone.calls).isEqualTo(0)
    }
}
