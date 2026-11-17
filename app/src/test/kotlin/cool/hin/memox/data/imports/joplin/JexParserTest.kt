package cool.hin.memox.data.imports.joplin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JexParserTest {

    private fun jex(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun parsesFrontMatterBodyTagsAndResources() {
        val note =
            """
            ---
            title: Meeting notes
            created_time: 2024-01-02T03:04:05Z
            updated_time: 2024-01-03T00:00:00Z
            tags:
              - work
              - plan
            ---

            # Agenda

            See :/abc123 for the diagram.
            """
                .trimIndent()
        val archive =
            JexParser.parse(
                ByteArrayInputStream(
                    jex(
                        mapOf(
                            "note1.md" to note.toByteArray(),
                            "abc123.png" to byteArrayOf(1, 2, 3),
                        )
                    )
                )
            )

        assertEquals(1, archive.notes.size)
        val parsed = archive.notes.single()
        assertEquals("Meeting notes", parsed.title)
        assertTrue(parsed.body.startsWith("# Agenda"))
        assertTrue(parsed.body.contains(":/abc123"))
        assertEquals(listOf("work", "plan"), parsed.tags)
        assertEquals(1704164645000L, parsed.created)
        assertEquals(1, archive.resources.size)
        assertEquals("abc123.png", archive.resources["abc123"]!!.fileName)
        assertEquals(listOf<Byte>(1, 2, 3), archive.resources["abc123"]!!.bytes.toList())
    }

    @Test
    fun skipsNonMarkdownRootEntriesAndNotesWithoutFrontMatter() {
        val archive =
            JexParser.parse(
                ByteArrayInputStream(
                    jex(
                        mapOf(
                            "plain.md" to "no front matter".toByteArray(),
                            "nested/inside.md" to "x".toByteArray(),
                        )
                    )
                )
            )
        assertEquals(0, archive.notes.size)
        assertEquals(0, archive.resources.size)
        assertEquals(2, archive.skippedEntries)
    }

    @Test
    fun parsesInlineTagsAndMillisecondTime() {
        val note =
            """
            ---
            title: Inline
            created_time: 2024-05-06T07:08:09.123Z
            tags: [alpha, "beta gamma"]
            ---
            body
            """
                .trimIndent()
        val parsed =
            JexParser.parse(ByteArrayInputStream(jex(mapOf("n.md" to note.toByteArray()))))
                .notes
                .single()
        assertEquals(listOf("alpha", "beta gamma"), parsed.tags)
        assertTrue(parsed.created > 0)
        // updated_time missing -> falls back to created_time
        assertEquals(parsed.created, parsed.updated)
    }
}
