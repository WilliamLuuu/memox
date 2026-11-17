package cool.hin.memox.data.model

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSecurityTest {

    private fun samplePayload() =
        ProtectedPayload(
            keyId = "6a0c3a8e-6f3e-4c43-8f2d-2f6f0a9f1c11",
            wrappedLocal = "bG9jYWw=",
            wrappedRecovery = "cmVjb3Zlcnk=",
            recoverySalt = "c2FsdA==",
            recoveryIterations = 120_000,
            bodyNonce = "bm9uY2U=",
            bodyCiphertext = "Y2lwaGVy",
            itemsNonce = "aXRlbXM=",
            itemsCiphertext = "aXRlbWN0",
            metadataMac = "bWFj",
            attachments =
                mapOf(
                    "photo.jpg" to EncryptedAttachment(nonce = "cGhvdG8=", size = 42),
                    "voice.m4a" to EncryptedAttachment(nonce = "dm9pY2U=", size = 7),
                ),
        )

    @Test
    fun payloadJsonRoundTripPreservesEveryField() {
        val payload = samplePayload()
        val parsed = ProtectedPayload.fromJson(payload.toJson())
        assertEquals(payload, parsed)
        assertEquals(42L, parsed.attachments["photo.jpg"]!!.size)
    }

    @Test
    fun missingOptionalRecoveryFieldsParseAsNull() {
        val payload = samplePayload().copy(wrappedRecovery = null, recoverySalt = null, itemsNonce = null, itemsCiphertext = null)
        val parsed = ProtectedPayload.fromJson(payload.toJson())
        assertEquals(null, parsed.wrappedRecovery)
        assertEquals(null, parsed.recoverySalt)
        assertEquals(null, parsed.itemsNonce)
        assertEquals(null, parsed.itemsCiphertext)
    }

    @Test
    fun canonicalMetadataChangesWithPublicFields() {
        val note =
            BaseNote(
                id = 1,
                type = Type.NOTE,
                folder = Folder.NOTES,
                color = BaseNote.COLOR_DEFAULT,
                title = "One",
                pinned = false,
                timestamp = 10,
                modifiedTimestamp = 20,
                labels = listOf("a"),
                body = "",
                spans = emptyList(),
                items = emptyList(),
                images = emptyList(),
                files = emptyList(),
                audios = emptyList(),
                reminders = emptyList(),
                isPinnedToStatus = false,
                locked = true,
                uuid = "6a0c3a8e-6f3e-4c43-8f2d-2f6f0a9f1c11",
                version = 3,
                protection = NoteProtection.ENCRYPTED,
            )
        val payload = samplePayload()
        val baseline = payload.canonicalMetadata(note)
        assertNotEquals(baseline, payload.canonicalMetadata(note.copy(title = "Two")))
        assertNotEquals(baseline, payload.canonicalMetadata(note.copy(version = 4)))
        assertEquals(baseline, payload.canonicalMetadata(note.copy()))
    }

    @Test
    fun stableNoteUuidIsDeterministicAndWellFormed() {
        val first = stableNoteUuid("cool.hin.memox", 7)
        val second = stableNoteUuid("cool.hin.memox", 7)
        val other = stableNoteUuid("cool.hin.memox", 8)
        assertEquals(first, second)
        assertNotEquals(first, other)
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test
    fun newNoteUuidIsRandomAndParsable() {
        val a = newNoteUuid()
        val b = newNoteUuid()
        assertNotEquals(a, b)
        assertEquals(a, UUID.fromString(a).toString())
    }
}
