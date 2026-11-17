package cool.hin.memox.presentation.viewmodel

import cool.hin.memox.data.model.Audio
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.ContentFormat
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.ListItem
import cool.hin.memox.data.model.NoteProtection
import cool.hin.memox.data.model.Reminder
import cool.hin.memox.data.model.Repetition
import cool.hin.memox.data.model.RepetitionTimeUnit
import cool.hin.memox.data.model.SpanRepresentation
import cool.hin.memox.data.model.Type
import cool.hin.memox.presentation.viewmodel.BaseNoteModel.HomeFilter
import cool.hin.memox.presentation.viewmodel.commitTimestamp
import cool.hin.memox.presentation.viewmodel.hasSameBusinessState
import cool.hin.memox.presentation.viewmodel.withFolderChange
import cool.hin.memox.presentation.viewmodel.withLabelChange
import cool.hin.memox.presentation.viewmodel.matchesDateRange
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic extracted from [BaseNoteModel] and [MemoXModel] save/filter paths. */
class NoteSaveAndFilterLogicTest {

    private fun note(
        body: String = "body",
        modified: Long = 100L,
        labels: List<String> = listOf("label"),
        folderId: String = "folder",
        protection: NoteProtection = NoteProtection.NONE,
        files: List<FileAttachment> = listOf(FileAttachment("a.txt", "a.txt", "text/plain")),
        images: List<FileAttachment> = emptyList(),
        audios: List<Audio> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        spans: List<SpanRepresentation> = listOf(SpanRepresentation(0, 2, bold = true)),
        items: List<ListItem> = emptyList(),
    ) =
        BaseNote(
            id = 1L,
            type = Type.NOTE,
            folder = Folder.NOTES,
            color = BaseNote.COLOR_DEFAULT,
            title = "title",
            pinned = false,
            timestamp = 50L,
            modifiedTimestamp = modified,
            labels = labels,
            body = body,
            spans = spans,
            items = items,
            images = images,
            files = files,
            audios = audios,
            reminders = reminders,
            isPinnedToStatus = false,
            locked = protection == NoteProtection.ENCRYPTED,
            folderId = folderId,
            uuid = "uuid",
            version = 3L,
            contentFormat = ContentFormat.RICH_TEXT,
            protection = protection,
        )

    @Test
    fun `identical plaintext keeps original modified time`() {
        val original = note(modified = 100L)
        val current = note(modified = 900L)
        assertTrue(current.hasSameBusinessState(original))
        assertEquals(100L, commitTimestamp(current, original))
    }

    @Test
    fun `encrypted note gaining plaintext body advances modified time`() {
        // Sealed notes read back with an empty body; adding text is a real content change.
        val original = note(body = "", modified = 100L, protection = NoteProtection.ENCRYPTED)
        val current = note(body = "new text", modified = 900L, protection = NoteProtection.ENCRYPTED)
        assertFalse(current.hasSameBusinessState(original))
        assertEquals(900L, commitTimestamp(current, original))
    }

    @Test
    fun `label folder and attachment changes advance modified time`() {
        val original = note(modified = 100L)
        assertNotEquals(100L, commitTimestamp(note(modified = 900L, labels = listOf("other")), original))
        assertNotEquals(100L, commitTimestamp(note(modified = 900L, folderId = "other"), original))
        assertNotEquals(
            100L,
            commitTimestamp(note(modified = 900L, files = emptyList()), original),
        )
        assertNotEquals(
            100L,
            commitTimestamp(
                note(modified = 900L, images = listOf(FileAttachment("i.jpg", "i.jpg", "image/jpeg"))),
                original,
            ),
        )
        assertNotEquals(
            100L,
            commitTimestamp(note(modified = 900L, audios = listOf(Audio("a.m4a", 10L, 50L))), original),
        )
    }

    @Test
    fun `reminder span and format changes advance modified time`() {
        val original = note(modified = 100L)
        assertNotEquals(
            100L,
            commitTimestamp(
                note(modified = 900L, reminders = listOf(Reminder(1, Date(1000), Repetition(1, RepetitionTimeUnit.DAYS)))),
                original,
            ),
        )
        assertNotEquals(100L, commitTimestamp(note(modified = 900L, spans = emptyList()), original))
        assertNotEquals(
            100L,
            commitTimestamp(note(modified = 900L).copy(contentFormat = ContentFormat.MARKDOWN), original),
        )
    }

    @Test
    fun `encryption envelope version and id changes do not count as business changes`() {
        val original =
            note(modified = 100L, protection = NoteProtection.ENCRYPTED).copy(
                payload = null,
                version = 3L,
            )
        val committed =
            original.copy(
                id = 2L,
                version = 4L,
                modifiedTimestamp = 100L,
                payload = null,
            )
        assertTrue(committed.hasSameBusinessState(original))
        assertEquals(100L, commitTimestamp(committed, original))
    }

    @Test
    fun `first save uses current modified time`() {
        assertEquals(900L, commitTimestamp(note(modified = 900L), null))
    }

    @Test
    fun `label rename migrates matching home filter and leaves others untouched`() {
        val filter = HomeFilter(label = "old", folderId = "folder")
        assertEquals("new", filter.withLabelChange("old", "new").label)
        assertEquals("folder", filter.withLabelChange("old", "new").folderId)
        assertEquals("old", filter.withLabelChange("other", "new").label)
        assertNull(filter.withLabelChange("old", null).label)
    }

    @Test
    fun `folder rename and deletion migrate matching home filter`() {
        val filter = HomeFilter(label = "label", folderId = "old")
        assertEquals("new", filter.withFolderChange("old", "new").folderId)
        assertEquals("label", filter.withFolderChange("old", "new").label)
        assertEquals("old", filter.withFolderChange("other", "new").folderId)
        assertNull(filter.withFolderChange("old", null).folderId)
    }

    @Test
    fun `date filter uses half-open range so next day midnight is excluded`() {
        assertTrue(matchesDateRange(100L, 100L, 200L))
        assertTrue(matchesDateRange(199L, 100L, 200L))
        assertFalse(matchesDateRange(200L, 100L, 200L))
        assertFalse(matchesDateRange(99L, 100L, 200L))
        assertTrue(matchesDateRange(500L, null, null))
    }
}
