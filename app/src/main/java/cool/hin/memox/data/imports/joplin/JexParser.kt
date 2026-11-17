package cool.hin.memox.data.imports.joplin

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * Minimal, read-only parser for a standard Joplin JEX export.
 *
 * A JEX file is a ZIP containing `<id>.md` notes with YAML front matter and resources named
 * `<id>.<ext>` at the archive root. The parser keeps the original Markdown source; it does not
 * pretend to understand Joplin-internal fields that memoX cannot represent.
 */
object JexParser {

    data class Resource(
        val id: String,
        val fileName: String,
        val bytes: ByteArray,
    )

    data class Note(
        val title: String,
        val body: String,
        val created: Long,
        val updated: Long,
        val tags: List<String>,
    )

    data class Archive(
        val notes: List<Note>,
        val resources: Map<String, Resource>,
        val skippedEntries: Int,
    )

    fun parse(input: InputStream): Archive {
        val notes = mutableListOf<Note>()
        val resources = mutableMapOf<String, Resource>()
        var skipped = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.contains('/')) {
                    skipped++
                    continue
                }
                val bytes = zip.readBytes()
                when {
                    name.endsWith(".md", ignoreCase = true) -> {
                        val note = parseNote(bytes.toString(Charsets.UTF_8))
                        if (note == null) skipped++ else notes.add(note)
                    }
                    name.substringAfterLast('.', "").isNotEmpty() -> {
                        val id = name.substringBeforeLast('.')
                        resources[id] = Resource(id, name, bytes)
                    }
                    else -> skipped++
                }
            }
        }
        return Archive(notes, resources, skipped)
    }

    private fun parseNote(raw: String): Note? {
        val normalized = raw.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return null
        val end = normalized.indexOf("\n---", startIndex = 4)
        if (end < 0) return null
        val frontMatter = normalized.substring(4, end)
        val body = normalized.substring(end + 4).trimStart('\n')
        val fields = parseFrontMatter(frontMatter)
        val title = fields["title"].orEmpty().ifBlank { "Untitled" }
        return Note(
            title = title,
            body = body,
            created = parseTime(fields["created_time"]) ?: System.currentTimeMillis(),
            updated = parseTime(fields["updated_time"]) ?: parseTime(fields["created_time"])
                ?: System.currentTimeMillis(),
            tags = fields["tags"].orEmpty().let(::parseList),
        )
    }

    /** Handles both `key: value` and simple YAML lists (`tags:\n  - one`). */
    private fun parseFrontMatter(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var currentKey: String? = null
        val listBuffer = mutableListOf<String>()
        fun flush() {
            val key = currentKey
            if (key != null && listBuffer.isNotEmpty()) {
                result[key] = listBuffer.joinToString(",")
                listBuffer.clear()
            }
        }
        text.lines().forEach { line ->
            when {
                line.startsWith("  - ") || line.startsWith("- ") -> {
                    listBuffer.add(line.removePrefix("  ").removePrefix("- ").trim())
                }
                line.contains(':') -> {
                    flush()
                    val key = line.substringBefore(':').trim()
                    val value = line.substringAfter(':').trim()
                    currentKey = key
                    if (value.isNotEmpty()) result[key] = value
                }
            }
        }
        flush()
        return result
    }

    private fun parseList(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value
            .trim('[', ']', '"', '\'')
            .split(',')
            .map { it.trim().trim('"', '\'', ' ') }
            .filter { it.isNotEmpty() }
    }

    private fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.trim().trim('"', '\'')
        val patterns =
            listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd HH:mm:ss",
            )
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                if (pattern.contains("'Z'")) format.timeZone = TimeZone.getTimeZone("UTC")
                return format.parse(cleaned)?.time
            } catch (_: Exception) {
                // try next pattern
            }
        }
        return null
    }
}
