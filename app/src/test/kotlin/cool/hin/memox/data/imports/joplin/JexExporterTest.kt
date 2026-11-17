package cool.hin.memox.data.imports.joplin

import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.ContentFormat
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.NoteProtection
import cool.hin.memox.data.model.Type
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JexExporterTest {

    private fun note(
        uuid: String,
        title: String,
        body: String,
        protection: NoteProtection = NoteProtection.NONE,
        images: List<FileAttachment> = emptyList(),
    ) =
        BaseNote(
            id = 1,
            type = Type.NOTE,
            folder = Folder.NOTES,
            color = BaseNote.COLOR_DEFAULT,
            title = title,
            pinned = false,
            timestamp = 1704164645000,
            modifiedTimestamp = 1704164645000,
            labels = listOf("work", "plan"),
            body = body,
            spans = emptyList(),
            items = emptyList(),
            images = images,
            files = emptyList(),
            audios = emptyList(),
            reminders = emptyList(),
            isPinnedToStatus = false,
            uuid = uuid,
            version = 1,
            contentFormat = ContentFormat.MARKDOWN,
            protection = protection,
        )

    @Test
    fun writesMarkdownFrontMatterResourcesAndSkipsEncryptedNotes() {
        val image = FileAttachment("local.png", "original.png", "image/png")
        val result =
            JexExporter.build(
                listOf(
                    note(
                        "uuid-1",
                        "Meeting notes",
                        "See local.png for the diagram",
                        images = listOf(image),
                    ),
                    note("uuid-2", "Secret", "secret body", NoteProtection.ENCRYPTED),
                )
            ) { _, name -> if (name == "local.png") byteArrayOf(9, 8, 7) else null }

        assertEquals(1, result.exported)
        assertEquals(1, result.skippedEncrypted)

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(result.archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val noteEntry = entries["uuid-1.md"]!!
        val text = noteEntry.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("---"))
        assertTrue(text.contains("title: Meeting notes"))
        assertTrue(text.contains("tags: [work, plan]"))
        assertTrue(text.contains("created_time: 2024-01-02T03:04:05Z"))
        assertTrue(text.contains(":/"))
        assertTrue(entries.keys.any { it.endsWith(".png") })
        assertTrue(entries.keys.none { it == "uuid-2.md" })
        assertTrue(entries.values.any { it.contentEquals(byteArrayOf(9, 8, 7)) })
    }

    @Test
    fun convertsRichTextNotesToMarkdownSource() {
        val rich =
            note("uuid-3", "Rich", "plain rich body").copy(
                contentFormat = ContentFormat.RICH_TEXT
            )
        val text = extractNote(JexExporter.build(listOf(rich)) { _, _ -> null }.archive, "uuid-3.md")
        assertTrue(text.contains("plain rich body"))
    }

    private fun extractNote(archive: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw AssertionError("missing $name")
    }
}
