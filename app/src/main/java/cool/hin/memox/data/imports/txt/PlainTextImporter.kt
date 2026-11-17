package cool.hin.memox.data.imports.txt

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import cool.hin.memox.data.imports.ExternalImporter
import cool.hin.memox.data.imports.ImportProgress
import cool.hin.memox.data.imports.markdown.parseBodyAndSpansFromMarkdown
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.ListItem
import cool.hin.memox.data.model.toCheckboxBodyAndSpans
import cool.hin.memox.data.model.Type
import cool.hin.memox.presentation.viewmodel.ExportMimeType
import cool.hin.memox.utils.MIME_TYPE_JSON
import cool.hin.memox.utils.log
import cool.hin.memox.utils.readFileContents
import java.io.File

class PlainTextImporter : ExternalImporter {

    override fun import(
        app: Application,
        source: Uri,
        destination: File,
        progress: MutableLiveData<ImportProgress>?,
    ): Pair<List<BaseNote>, File?> {
        val notes = mutableListOf<BaseNote>()
        fun readTxtFiles(file: DocumentFile, parent: DocumentFile? = null) {
            when {
                file.isDirectory -> {
                    file.listFiles().forEach { readTxtFiles(it, file) }
                }

                file.isFile -> {
                    if (file.type?.isTextMimeType() == false) {
                        return
                    }
                    val fileNameWithoutExtension = file.name?.substringBeforeLast(".") ?: ""
                    var content = app.contentResolver.readFileContents(file.uri)
                    val listItems = mutableListOf<ListItem>()
                    // If content contains recognizable list syntax, prefer importing as LIST
                    // If its markdown there could only be a headerline, ignore it for LISTs
                    val listContent =
                        if (file.isMarkdownFile() && content.startsWith("#")) {
                            content.lines().drop(1).joinToString("\n")
                        } else content
                    listContent.findListSyntaxRegex()?.let { listSyntaxRegex ->
                        listItems.addAll(listContent.extractListItems(listSyntaxRegex))
                        content = ""
                    }
                    val timestamp = System.currentTimeMillis()

                    if (file.isMarkdownFile() && listItems.isEmpty() && content.isNotEmpty()) {
                        // Markdown stays Markdown: never convert the source into rich-text spans,
                        // otherwise a round trip through memoX cannot preserve the original file.
                        val images = mutableListOf<cool.hin.memox.data.model.FileAttachment>()
                        val files = mutableListOf<cool.hin.memox.data.model.FileAttachment>()
                        val linkPattern = Regex("""!?\[[^\]]*]\(([^)]+)\)""")
                        var rewritten = content
                        linkPattern.findAll(content).forEach { match ->
                            val rawTarget = match.groupValues[1].trim()
                            val target =
                                rawTarget.substringBefore('#').substringBefore('?').trim()
                            if (target.isEmpty() ||
                                target.startsWith("http://") ||
                                target.startsWith("https://") ||
                                target.startsWith("data:") ||
                                target.startsWith("/")
                            ) {
                                return@forEach
                            }
                            val document =
                                parent?.let { resolveRelativeDocument(it, target) }
                                    ?: return@forEach
                            val extension = target.substringAfterLast('.', "bin")
                            val localName = "${java.util.UUID.randomUUID()}.$extension"
                            try {
                                app.contentResolver.openInputStream(document.uri)?.use { input ->
                                    File(destination, localName).outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                } ?: return@forEach
                            } catch (_: Exception) {
                                return@forEach
                            }
                            val mime = document.type ?: "application/octet-stream"
                            val attachment =
                                cool.hin.memox.data.model.FileAttachment(
                                    localName = localName,
                                    originalName = target.substringAfterLast('/'),
                                    mimeType = mime,
                                )
                            if (mime.startsWith("image/")) images.add(attachment)
                            else files.add(attachment)
                            rewritten = rewritten.replace(rawTarget, localName)
                        }
                        notes.add(
                            BaseNote(
                                id = 0L,
                                type = Type.NOTE,
                                folder = Folder.NOTES,
                                color = BaseNote.COLOR_DEFAULT,
                                title = fileNameWithoutExtension,
                                pinned = false,
                                timestamp = timestamp,
                                modifiedTimestamp = timestamp,
                                labels = emptyList(),
                                body = rewritten,
                                spans = emptyList(),
                                items = emptyList(),
                                images = images,
                                files = files,
                                audios = emptyList(),
                                reminders = emptyList(),
                                isPinnedToStatus = false,
                                contentFormat = cool.hin.memox.data.model.ContentFormat.MARKDOWN,
                            )
                        )
                        return
                    }
                    val (body, spans) =
                        if (file.isMarkdownFile() && listItems.isEmpty()) {
                            // Parse Markdown into body + spans, ignoring unsupported formatting
                            try {
                                parseBodyAndSpansFromMarkdown(content)
                            } catch (e: Exception) {
                                app.log(
                                    TAG,
                                    msg =
                                        "Parsing from Markdown content failed, will import as raw text. Markdown Content:\n$content",
                                    throwable = e,
                                )
                                // Fallback to plain text if parser fails
                                Pair(content, emptyList())
                            }
                        } else Pair(content, emptyList())

                    val (finalBody, finalSpans) = listItems.toCheckboxBodyAndSpans(body, spans)
                    notes.add(
                        BaseNote(
                            id = 0L, // Auto-generated
                            type = Type.NOTE,
                            folder = Folder.NOTES,
                            color = BaseNote.COLOR_DEFAULT,
                            title = fileNameWithoutExtension,
                            pinned = false,
                            timestamp = timestamp,
                            modifiedTimestamp = timestamp,
                            labels = listOf(),
                            body = finalBody,
                            spans = finalSpans,
                            items = listOf(),
                            images = listOf(),
                            files = listOf(),
                            audios = listOf(),
                            reminders = listOf(),
                            false,
                        )
                    )
                }
            }
        }
        val file =
            if (source.pathSegments.firstOrNull() == "tree") {
                DocumentFile.fromTreeUri(app, source)
            } else DocumentFile.fromSingleUri(app, source)
        file?.let { readTxtFiles(it) }
        return Pair(notes, null)
    }

    private fun String.isTextMimeType(): Boolean {
        return startsWith("text/") || this in APPLICATION_TEXT_MIME_TYPES
    }

    private fun DocumentFile.isMarkdownFile(): Boolean {
        return (type?.equals(ExportMimeType.MD.mimeType, ignoreCase = true) == true) ||
            (name
                ?.substringAfterLast('.', "")
                ?.equals(ExportMimeType.MD.fileExtension, ignoreCase = true) == true)
    }

    /** Resolve a relative Markdown link against the note's folder. Parent traversal is refused. */
    private fun resolveRelativeDocument(base: DocumentFile, path: String): DocumentFile? {
        var current: DocumentFile = base
        for (part in path.split('/')) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") return null
            current = current.findFile(part) ?: return null
        }
        return current.takeIf { it.isFile }
    }

    companion object {
        private const val TAG = "PlainTextImporter"
    }
}

val APPLICATION_TEXT_MIME_TYPES =
    arrayOf(
        MIME_TYPE_JSON,
        "application/xml",
        "application/javascript",
        "application/xhtml+xml",
        "application/yaml",
    )
