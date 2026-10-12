package com.anchor.data.settings

import com.anchor.data.location.SavedPlace
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PresenceSourceTest {

    private val place = SavedPlace(1.0, 2.0)

    @Test
    fun `defaults to Home Assistant`() {
        assertThat(AnchorSettings().presenceSource).isEqualTo(PresenceSource.HOME_ASSISTANT)
    }

    @Test
    fun `phone source uses the home place`() {
        assertThat(AnchorSettings(locationSource = LocationSource.PHONE).presenceSource)
            .isEqualTo(PresenceSource.PHONE_HOME)
    }

    @Test
    fun `vacation with a place beats both other sources`() {
        val onHa = AnchorSettings(vacationMode = true, vacationPlace = place)
        val onPhone = onHa.copy(locationSource = LocationSource.PHONE)
        assertThat(onHa.presenceSource).isEqualTo(PresenceSource.VACATION)
        assertThat(onPhone.presenceSource).isEqualTo(PresenceSource.VACATION)
    }

    @Test
    fun `vacation without a place changes nothing`() {
        assertThat(AnchorSettings(vacationMode = true).presenceSource).isEqualTo(PresenceSource.HOME_ASSISTANT)
        assertThat(AnchorSettings(vacationMode = true, locationSource = LocationSource.PHONE).presenceSource)
            .isEqualTo(PresenceSource.PHONE_HOME)
    }

    @Test
    fun `phone location is needed for the phone source or vacation mode`() {
        assertThat(AnchorSettings().usesPhoneLocation).isFalse()
        assertThat(AnchorSettings(locationSource = LocationSource.PHONE).usesPhoneLocation).isTrue()
        assertThat(AnchorSettings(vacationMode = true).usesPhoneLocation).isTrue()
    }
}
