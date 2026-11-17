package cool.hin.memox.data.model

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Explicit authoring format. Legacy notes are always [RICH_TEXT]; Markdown is never inferred. */
enum class ContentFormat {
    RICH_TEXT,
    MARKDOWN,
}

/**
 * Protection is modeled separately from content format:
 * - [NONE]: ordinary note.
 * - [LEGACY_LOCK]: access gate only (the pre-1.3 behaviour); body and attachments stay plaintext.
 * - [ENCRYPTED]: body, checklist items and attachments are encrypted at rest.
 *
 * `BaseNote.locked` remains the access-lock flag. An encrypted note is always locked too; the
 * reverse is not true, which is what allows a controlled migration of legacy locked notes.
 */
enum class NoteProtection {
    NONE,
    LEGACY_LOCK,
    ENCRYPTED;

    val isEncrypted: Boolean
        get() = this == ENCRYPTED
}

/** One encrypted attachment. Ciphertext lives next to the plaintext name with a `.enc` suffix. */
data class EncryptedAttachment(
    val nonce: String,
    val size: Long,
) {
    fun toJson(): JSONObject =
        JSONObject().put("nonce", nonce).put("size", size)

    companion object {
        fun fromJson(json: JSONObject): EncryptedAttachment =
            EncryptedAttachment(nonce = json.getString("nonce"), size = json.getLong("size"))
    }
}

/**
 * Envelope stored in [BaseNote.payload]. It never contains a plaintext key.
 *
 * [wrappedLocal] is the per-note content key wrapped by the non-exportable Android Keystore key.
 * [wrappedRecovery] is the same content key wrapped by a KEK derived from the user recovery
 * password with PBKDF2-HMAC-SHA256; it is what makes cross-device recovery possible.
 */
data class ProtectedPayload(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val algorithm: String = "AES-256-GCM",
    val keyId: String,
    val wrappedLocal: String,
    val wrappedRecovery: String? = null,
    val recoverySalt: String? = null,
    val recoveryIterations: Int = DEFAULT_RECOVERY_ITERATIONS,
    val bodyNonce: String,
    val bodyCiphertext: String,
    val itemsNonce: String? = null,
    val itemsCiphertext: String? = null,
    val metadataMac: String,
    val attachments: Map<String, EncryptedAttachment> = emptyMap(),
) {
    fun toJson(): String {
        val attachmentJson = JSONObject()
        attachments.forEach { (name, ref) -> attachmentJson.put(name, ref.toJson()) }
        return JSONObject()
            .put("v", formatVersion)
            .put("alg", algorithm)
            .put("keyId", keyId)
            .put("localKey", wrappedLocal)
            .put("recoveryKey", wrappedRecovery ?: JSONObject.NULL)
            .put("recoverySalt", recoverySalt ?: JSONObject.NULL)
            .put("recoveryIterations", recoveryIterations)
            .put("bodyNonce", bodyNonce)
            .put("bodyCt", bodyCiphertext)
            .put("itemsNonce", itemsNonce ?: JSONObject.NULL)
            .put("itemsCt", itemsCiphertext ?: JSONObject.NULL)
            .put("metaMac", metadataMac)
            .put("attachments", attachmentJson)
            .toString()
    }

    /** Fields that are protected against undetected remote tampering. */
    fun canonicalMetadata(note: BaseNote): String =
        listOf(
                "v=$formatVersion",
                "uuid=${note.uuid}",
                "type=${note.type.name}",
                "title=${note.title}",
                "labels=${note.labels.joinToString("\u001f")}",
                "timestamp=${note.timestamp}",
                "modified=${note.modifiedTimestamp}",
                "folder=${note.folderId}",
                "color=${note.color}",
                "pinned=${note.pinned}",
                "version=${note.version}",
                "format=${note.contentFormat.name}",
            )
            .joinToString("\u001e")

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val DEFAULT_RECOVERY_ITERATIONS = 310_000

        fun fromJson(json: String): ProtectedPayload {
            val obj = JSONObject(json)
            val attachments = mutableMapOf<String, EncryptedAttachment>()
            val attachmentJson = obj.optJSONObject("attachments")
            attachmentJson?.keys()?.forEach { name ->
                attachments[name] = EncryptedAttachment.fromJson(attachmentJson.getJSONObject(name))
            }
            return ProtectedPayload(
                formatVersion = obj.getInt("v"),
                algorithm = obj.getString("alg"),
                keyId = obj.getString("keyId"),
                wrappedLocal = obj.getString("localKey"),
                wrappedRecovery = obj.optNullableString("recoveryKey"),
                recoverySalt = obj.optNullableString("recoverySalt"),
                recoveryIterations = obj.optInt("recoveryIterations", DEFAULT_RECOVERY_ITERATIONS),
                bodyNonce = obj.getString("bodyNonce"),
                bodyCiphertext = obj.getString("bodyCt"),
                itemsNonce = obj.optNullableString("itemsNonce"),
                itemsCiphertext = obj.optNullableString("itemsCt"),
                metadataMac = obj.getString("metaMac"),
                attachments = attachments,
            )
        }

        private fun JSONObject.optNullableString(name: String): String? =
            if (isNull(name)) null else optString(name, "").ifEmpty { null }
    }
}

private fun JSONObject.optInt(name: String, fallback: Int): Int =
    if (has(name) && !isNull(name)) getInt(name) else fallback

/** RFC 4122 v5 UUID namespaced by the application id; stable for one device dataset. */
fun stableNoteUuid(namespace: String, id: Long): String {
    val namespaceBytes =
        MessageDigest.getInstance("SHA-1").digest(namespace.toByteArray(Charsets.UTF_8))
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(namespaceBytes.copyOfRange(0, 16))
    digest.update(id.toString().toByteArray(Charsets.UTF_8))
    val bytes = digest.digest().copyOfRange(0, 16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    var msb = 0L
    var lsb = 0L
    for (i in 0..7) msb = (msb shl 8) or (bytes[i].toLong() and 0xff)
    for (i in 8..15) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xff)
    return UUID(msb, lsb).toString()
}

/** Server-independent random UUID for new notes and conflict copies. */
fun newNoteUuid(): String = UUID.randomUUID().toString()
