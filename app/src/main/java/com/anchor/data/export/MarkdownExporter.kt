package com.anchor.data.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.Phase
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ExportResult {
    data class Written(val fileName: String, val content: String) : ExportResult
    data object NoDirectoryConfigured : ExportResult
    data class Failed(val reason: String) : ExportResult
}

/**
 * The seam between the export logic and Android's Storage Access Framework.
 * Unit tests substitute an in-memory implementation.
 */
interface DocumentStore {
    fun read(fileName: String): String?

    /** Returns false on any failure; never throws. */
    fun write(fileName: String, content: String): Boolean
}

/**
 * Builds a [DocumentStore] for a tree URI string, or returns null when the
 * URI is unusable (permission revoked, folder deleted).
 */
fun interface DocumentStoreFactory {
    operator fun invoke(treeUri: String): DocumentStore?
}

/** SAF-backed store rooted at a persisted tree URI. */
class SafDocumentStore(
    private val context: Context,
    private val treeUri: Uri,
) : DocumentStore {

    private fun root(): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory && it.canWrite() }

    override fun read(fileName: String): String? = runCatching {
        val file = root()?.findFile(fileName) ?: return null
        context.contentResolver.openInputStream(file.uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    override fun write(fileName: String, content: String): Boolean = runCatching {
        val dir = root() ?: return false
        val file = dir.findFile(fileName)
            ?: dir.createFile("text/markdown", fileName)
            ?: return false
        // "wt" truncates; we always write the whole merged document.
        context.contentResolver.openOutputStream(file.uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    }.getOrDefault(false)
}

/**
 * Writes one Markdown file per day into the user's chosen folder, merging the
 * morning and evening sections into a single document.
 */
@Singleton
open class MarkdownExporter @Inject constructor(
    private val storeFactory: DocumentStoreFactory,
) {
    open suspend fun export(
        log: DailyLog,
        questions: List<CustomQuestion>,
        phase: Phase,
        treeUri: String?,
        format: NoteFormat = NoteFormat.PLAIN,
    ): ExportResult {
        if (treeUri.isNullOrBlank()) return ExportResult.NoDirectoryConfigured
        val store = storeFactory(treeUri) ?: return ExportResult.NoDirectoryConfigured

        val fileName = "${log.date}.md"
        val section = MarkdownRenderer.renderSection(phase, log, questions)
            ?: return ExportResult.Failed("No answers to write for $phase")

        val existing = store.read(fileName)
        val content = if (existing.isNullOrBlank()) {
            MarkdownRenderer.render(log, questions, format)
        } else {
            MarkdownRenderer.mergeInto(existing, section, phase)
        }

        return if (store.write(fileName, content)) {
            ExportResult.Written(fileName, content)
        } else {
            ExportResult.Failed("Could not write $fileName")
        }
    }
}
