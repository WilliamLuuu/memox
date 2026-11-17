package cool.hin.memox.data.imports.joplin

import cool.hin.memox.data.imports.markdown.createMarkdownFromBodyAndSpans
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.ContentFormat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a standard JEX archive. Markdown notes keep their source; rich-text notes are converted
 * once through the shared Markdown exporter. Content-encrypted notes are skipped and counted:
 * exporting them would either leak plaintext or produce a note another client cannot read.
 */
object JexExporter {

    data class Result(val archive: ByteArray, val exported: Int, val skippedEncrypted: Int)

    fun build(
        notes: List<BaseNote>,
        resourceReader: (subfolder: String, localName: String) -> ByteArray?,
    ): Result {
        val output = ByteArrayOutputStream()
        var exported = 0
        var skipped = 0
        val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        ZipOutputStream(output).use { zip ->
            notes.forEach { note ->
                if (note.protection.isEncrypted) {
                    skipped++
                    return@forEach
                }
                val body =
                    if (note.contentFormat == ContentFormat.MARKDOWN) note.body
                    else createMarkdownFromBodyAndSpans(note.body, note.spans)
                val resources = mutableListOf<Pair<String, ByteArray>>()
                val resourceUsages = mutableMapOf<String, String>()

                fun addResource(subfolder: String, localName: String) {
                    if (resourceUsages.containsKey(localName)) return
                    val bytes = resourceReader(subfolder, localName) ?: return
                    val id = note.uuid.replace("-", "") + resources.size.toString(16).padStart(4, '0')
                    val extension = localName.substringAfterLast('.', "bin")
                    resources.add("$id.$extension" to bytes)
                    resourceUsages[localName] = id
                }
                note.images.forEach { addResource("Images", it.localName) }
                note.files.forEach { addResource("Files", it.localName) }
                note.audios.forEach { addResource("Audios", it.name) }

                var rewritten = body
                resourceUsages.forEach { (localName, id) ->
                    rewritten = rewritten.replace(localName, ":/$id")
                }

                val frontMatter =
                    buildString {
                        append("---\n")
                        append("title: ").append(sanitize(note.title)).append('\n')
                        append("created_time: ").append(timeFormat.format(Date(note.timestamp)))
                            .append('\n')
                        append("updated_time: ")
                            .append(timeFormat.format(Date(note.modifiedTimestamp)))
                            .append('\n')
                        if (note.labels.isNotEmpty()) {
                            append("tags: ")
                            append(note.labels.joinToString(", ", "[", "]") { sanitize(it) })
                            append('\n')
                        }
                        append("source_application: memoX\n")
                        append("---\n\n")
                    }
                zip.putNextEntry(ZipEntry("${note.uuid}.md"))
                zip.write((frontMatter + rewritten).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                resources.forEach { (id, bytes) ->
                    zip.putNextEntry(ZipEntry(id))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                exported++
            }
        }
        return Result(output.toByteArray(), exported, skipped)
    }

    private fun sanitize(value: String): String =
        value.replace("\n", " ").replace("\r", " ").trim()
}
