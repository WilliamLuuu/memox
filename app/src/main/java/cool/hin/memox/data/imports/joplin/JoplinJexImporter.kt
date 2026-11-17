package cool.hin.memox.data.imports.joplin

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import cool.hin.memox.R
import cool.hin.memox.data.imports.ExternalImporter
import cool.hin.memox.data.imports.ImportException
import cool.hin.memox.data.imports.ImportProgress
import cool.hin.memox.data.imports.ImportStage
import cool.hin.memox.data.model.Audio
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.ContentFormat
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Type
import cool.hin.memox.data.model.newNoteUuid
import java.io.File
import java.util.UUID

/**
 * Imports a standard Joplin JEX archive: Markdown source stays Markdown, resources are copied
 * next to the note and `:/<resource-id>` links are rewritten to the copied file name.
 *
 * Joplin-only concepts (encryption, revisions, notebook hierarchy beyond tags) are reported as
 * skipped rather than silently converted.
 */
class JoplinJexImporter : ExternalImporter {

    override fun import(
        app: Application,
        source: Uri,
        destination: File,
        progress: MutableLiveData<ImportProgress>?,
    ): Pair<List<BaseNote>, File?> {
        progress?.postValue(ImportProgress(indeterminate = true, stage = ImportStage.EXTRACT_FILES))
        val archive =
            try {
                app.contentResolver.openInputStream(source)?.use { JexParser.parse(it) }
                    ?: throw ImportException(
                        R.string.invalid_joplin,
                        IllegalStateException("empty JEX input"),
                    )
            } catch (e: ImportException) {
                throw e
            } catch (e: Exception) {
                throw ImportException(R.string.invalid_joplin, e)
            }
        if (archive.notes.isEmpty()) {
            throw ImportException(
                R.string.invalid_joplin,
                IllegalStateException("no notes in JEX"),
            )
        }

        val dataFolder = File(destination, "joplin").apply { mkdirs() }
        val resourceNames = mutableMapOf<String, String>()
        archive.resources.forEach { (id, resource) ->
            val extension = resource.fileName.substringAfterLast('.', "bin")
            val localName = "${UUID.randomUUID()}.$extension"
            File(dataFolder, localName).writeBytes(resource.bytes)
            resourceNames[id] = localName
        }

        val total = archive.notes.size
        progress?.postValue(ImportProgress(0, total, stage = ImportStage.IMPORT_NOTES))
        var counter = 1
        val notes =
            archive.notes.map { note ->
                val body = rewriteResourceLinks(note.body, resourceNames)
                val attachments =
                    resourceNames.values.filter { body.contains(it) }.map { name ->
                        val mime = mimeFor(name)
                        FileAttachment(localName = name, originalName = name, mimeType = mime)
                    }
                val images = attachments.filter { it.mimeType.startsWith("image/") }
                val audios =
                    attachments.filter { it.mimeType.startsWith("audio/") }.map { attachment ->
                        Audio(
                            name = attachment.localName,
                            duration = null,
                            timestamp = note.created,
                        )
                    }
                val audioNames =
                    attachments
                        .filter { it.mimeType.startsWith("audio/") }
                        .map { it.localName }
                        .toSet()
                val files =
                    attachments.filter { attachment ->
                        !attachment.mimeType.startsWith("image/") &&
                            attachment.localName !in audioNames
                    }
                progress?.postValue(
                    ImportProgress(counter++, total, stage = ImportStage.IMPORT_NOTES)
                )
                BaseNote(
                    id = 0,
                    type = Type.NOTE,
                    folder = Folder.NOTES,
                    color = BaseNote.COLOR_DEFAULT,
                    title = note.title,
                    pinned = false,
                    timestamp = note.created,
                    modifiedTimestamp = note.updated,
                    labels = note.tags,
                    body = body,
                    spans = emptyList(),
                    items = emptyList(),
                    images = images,
                    files = files,
                    audios = audios,
                    reminders = emptyList(),
                    isPinnedToStatus = false,
                    uuid = newNoteUuid(),
                    version = 1,
                    contentFormat = ContentFormat.MARKDOWN,
                )
            }
        return Pair(notes, dataFolder)
    }

    private fun rewriteResourceLinks(body: String, names: Map<String, String>): String {
        if (names.isEmpty()) return body
        var result = body
        names.forEach { (id, name) -> result = result.replace(":/$id", name) }
        return result
    }

    private fun mimeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> "audio/webm"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
