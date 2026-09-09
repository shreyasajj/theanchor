package com.anchor.data.export

import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort push to Joplin. Every failure is swallowed: the spec requires
 * a silent fallback to local-file-only export, and a check-in must never be
 * blocked by an unreachable note server.
 */
@Singleton
open class JoplinExporter @Inject constructor(
    private val api: JoplinApi,
) {
    open suspend fun push(title: String, body: String, settings: AnchorSettings): Boolean {
        if (settings.joplinBaseUrl.isBlank() || settings.joplinToken.isBlank()) return false
        val url = "${settings.joplinBaseUrl.trimEnd('/')}/notes?token=${settings.joplinToken}"
        return try {
            api.createNote(url, JoplinNoteRequest(title = title, body = body))
            true
        } catch (t: Throwable) {
            false
        }
    }
}
