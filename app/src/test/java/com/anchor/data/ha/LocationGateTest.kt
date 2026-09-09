package com.anchor.data.ha

import com.anchor.data.settings.LocationMode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

class LocationGateTest {

    private fun ok(state: String, friendlyName: String? = null): HaResult.Ok {
        val attrs: Map<String, JsonElement> = friendlyName
            ?.let { mapOf("friendly_name" to Json.parseToJsonElement("\"$it\"")) }
            ?: emptyMap()
        return HaResult.Ok(HaStateDto("device_tracker.pixel", state, attrs))
    }

    // --- AT_HOME mode ---

    @Test
    fun `at home mode is in scope when the state is home`() {
        assertThat(LocationGate.evaluate(ok("home"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `at home mode is out of scope when the state is not_home`() {
        assertThat(LocationGate.evaluate(ok("not_home"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `at home mode treats an arbitrary zone name as out of scope`() {
        assertThat(LocationGate.evaluate(ok("Work"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `at home mode ignores case on the state`() {
        assertThat(LocationGate.evaluate(ok("Home"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `unavailable is UNKNOWN in at home mode`() {
        assertThat(LocationGate.evaluate(HaResult.Unavailable, LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `an unavailable or unknown entity state is UNKNOWN`() {
        assertThat(LocationGate.evaluate(ok("unavailable"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.UNKNOWN)
        assertThat(LocationGate.evaluate(ok("unknown"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.UNKNOWN)
    }

    // --- SPECIFIC_ROOMS mode ---

    @Test
    fun `specific rooms matches a room name inside friendly_name`() {
        val result = LocationGate.evaluate(
            ok("home", "Bedroom Presence Sensor"),
            LocationMode.SPECIFIC_ROOMS,
            listOf("Bedroom", "Office"),
        )
        assertThat(result).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `specific rooms matching is case insensitive`() {
        val result = LocationGate.evaluate(
            ok("home", "shre's OFFICE tracker"),
            LocationMode.SPECIFIC_ROOMS,
            listOf("office"),
        )
        assertThat(result).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `specific rooms is out of scope when no room matches`() {
        val result = LocationGate.evaluate(
            ok("home", "Kitchen Sensor"),
            LocationMode.SPECIFIC_ROOMS,
            listOf("Bedroom", "Office"),
        )
        assertThat(result).isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `specific rooms falls back to the state value when friendly_name is absent`() {
        val result = LocationGate.evaluate(ok("Bedroom"), LocationMode.SPECIFIC_ROOMS, listOf("Bedroom"))
        assertThat(result).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `specific rooms with an empty room list is out of scope, never in scope`() {
        val result = LocationGate.evaluate(ok("home", "Bedroom"), LocationMode.SPECIFIC_ROOMS, emptyList())
        assertThat(result).isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `unavailable is UNKNOWN in specific rooms mode`() {
        val result = LocationGate.evaluate(HaResult.Unavailable, LocationMode.SPECIFIC_ROOMS, listOf("Bedroom"))
        assertThat(result).isEqualTo(Presence.UNKNOWN)
    }
}
