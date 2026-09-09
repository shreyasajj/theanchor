package com.anchor.data.ha

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single entity state from `GET /api/states/<entity_id>`.
 * Attributes are left as raw JSON because Home Assistant's attribute set is
 * entity-specific and we only ever read `friendly_name`.
 */
@Serializable
data class HaStateDto(
    @SerialName("entity_id") val entityId: String,
    val state: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
) {
    val friendlyName: String?
        get() = runCatching { attributes["friendly_name"]?.jsonPrimitive?.content }.getOrNull()
}
