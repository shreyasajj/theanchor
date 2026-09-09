package com.anchor.data.ha

/** A HomeAssistantApi that must never be called; fakes override fetchState instead. */
object FakeHaApi : HomeAssistantApi {
    override suspend fun state(url: String, authorization: String): HaStateDto =
        error("FakeHaApi should not be called")
}

/** A HomeAssistantClient stand-in that returns a canned result per entity id. */
open class FakeHaClient(private val byEntity: Map<String, HaResult>) : HomeAssistantClient(FakeHaApi) {
    val calls = mutableListOf<String>()

    constructor(single: HaResult) : this(emptyMap()) {
        fallback = single
    }

    private var fallback: HaResult = HaResult.Unavailable

    override suspend fun fetchState(baseUrl: String, token: String, entityId: String): HaResult {
        calls += entityId
        return byEntity[entityId] ?: fallback
    }
}
