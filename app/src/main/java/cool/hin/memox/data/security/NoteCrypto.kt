package cool.hin.memox.data.security

import android.content.ContextWrapper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cool.hin.memox.data.model.Audio
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.model.EncryptedAttachment
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.ListItem
import cool.hin.memox.data.model.NoteProtection
import cool.hin.memox.data.model.ProtectedPayload
import cool.hin.memox.utils.SUBFOLDER_AUDIOS
import cool.hin.memox.utils.SUBFOLDER_FILES
import cool.hin.memox.utils.SUBFOLDER_IMAGES
import cool.hin.memox.utils.getCurrentAudioDirectory
import cool.hin.memox.utils.getCurrentFilesDirectory
import cool.hin.memox.utils.getCurrentImagesDirectory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray

/**
 * Note-level encryption.
 *
 * Threat model: protects note bodies, checklist items and attachment bytes at rest (database
 * columns, attachment directory, cloud sync objects and backup archives). Public metadata
 * (title, labels, creation time, folder, colour) stays readable by design; it is authenticated
 * with an HMAC so remote tampering is detected.
 *
 * Keys:
 * - one random AES-256 content key per note;
 * - wrapped locally by a non-exportable Android Keystore key (API 23+);
 * - optionally wrapped by a recovery-password KEK (PBKDF2-HMAC-SHA256) for other devices.
 *
 * Plaintext only exists in memory and in a session cache directory used by the UI to render
 * attachments; [clearSession] removes it.
 */
object NoteCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "memox_note_content_key_v1"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val PBKDF2_ITERATIONS = ProtectedPayload.DEFAULT_RECOVERY_ITERATIONS
    private const val SALT_BYTES = 16
    private const val CACHE_DIR = "note-protected-cache"

    private val random = SecureRandom()

    private val sessionKeys = mutableMapOf<String, SecretKey>()

    open class CryptoException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    class WrongPasswordException : CryptoException("Recovery password does not match")
    class MissingRecoveryException :
        CryptoException("This note has no recovery material for another device")

    class CorruptedPayloadException(cause: Throwable? = null) :
        CryptoException("Encrypted note payload is corrupted or has an unknown version", cause)

    data class OpenedNote(val note: BaseNote, val contentKey: SecretKey)

    /** True when a protected note can currently be opened on this device. */
    fun canOpenLocally(note: BaseNote): Boolean {
        val payload = note.payload ?: return false
        return runCatching { unwrapLocal(note.uuid, payload.wrappedLocal) }.isSuccess
    }

    fun hasRecoveryMaterial(note: BaseNote): Boolean =
        note.payload?.wrappedRecovery != null && note.payload.recoverySalt != null

    /**
     * Encrypts [plainNote] (body, items, attachments). The returned note carries the envelope and
     * empty plaintext columns so nothing sensitive reaches Room or an exporter.
     */
    fun seal(context: ContextWrapper, plainNote: BaseNote, recoveryPassword: CharArray?): BaseNote {
        require(plainNote.protection == NoteProtection.ENCRYPTED) {
            "Only ENCRYPTED notes can be sealed"
        }
        val contentKey = sessionKeys[plainNote.uuid] ?: newContentKey()
        val version = plainNote.version + 1
        val versioned = plainNote.copy(version = version, locked = true)

        val bodyNonceBytes = randomNonce()
        val bodyCt =
            encryptBytes(
                contentKey,
                bodyNonceBytes,
                plainNote.body.toByteArray(Charsets.UTF_8),
                aad(plainNote.uuid, version, "body"),
            )
        val itemsJson = Converters.itemsToJson(plainNote.items).toByteArray(Charsets.UTF_8)
        val itemsNonceBytes = if (plainNote.items.isEmpty()) null else randomNonce()
        val itemsCt =
            itemsNonceBytes?.let {
                encryptBytes(contentKey, it, itemsJson, aad(plainNote.uuid, version, "items"))
            }

        val attachmentRefs = mutableMapOf<String, EncryptedAttachment>()
        encryptAttachments(context, plainNote, contentKey, attachmentRefs)

        val wrappedLocal = wrapLocal(contentKey)
        val recovery =
            if (recoveryPassword != null && recoveryPassword.isNotEmpty()) {
                val salt = randomBytes(SALT_BYTES)
                val kek = pbkdf2(recoveryPassword, salt, PBKDF2_ITERATIONS)
                val wrapped = wrapWithKek(kek, contentKey)
                Triple(wrapped, base64(salt), PBKDF2_ITERATIONS)
            } else {
                plainNote.payload?.let {
                    if (it.wrappedRecovery != null && it.recoverySalt != null)
                        Triple(it.wrappedRecovery, it.recoverySalt, it.recoveryIterations)
                    else null
                } ?: Triple(null, null, PBKDF2_ITERATIONS)
            }

        val payload =
            ProtectedPayload(
                keyId = plainNote.uuid,
                wrappedLocal = wrappedLocal,
                wrappedRecovery = recovery.first,
                recoverySalt = recovery.second,
                recoveryIterations = recovery.third,
                bodyNonce = base64(bodyNonceBytes),
                bodyCiphertext = base64(bodyCt),
                itemsNonce = itemsNonceBytes?.let { base64(it) },
                itemsCiphertext = itemsCt?.let { base64(it) },
                metadataMac = "",
                attachments = attachmentRefs,
            )
        val withMac = payload.copy(metadataMac = metadataMac(contentKey, versioned, payload))
        sessionKeys[plainNote.uuid] = contentKey
        return versioned.copy(
            body = "",
            spans = emptyList(),
            items = emptyList(),
            payload = withMac,
        )
    }

    /**
     * Decrypts a sealed note. [recoveryPassword] is only consulted when the local Keystore wrap is
     * unavailable (e.g. restored on another device).
     */
    fun open(note: BaseNote, recoveryPassword: CharArray? = null): OpenedNote {
        val payload = note.payload ?: throw CorruptedPayloadException()
        if (payload.formatVersion != ProtectedPayload.CURRENT_FORMAT_VERSION) {
            throw CorruptedPayloadException()
        }
        val contentKey =
            sessionKeys[note.uuid]
                ?: unwrapLocal(note.uuid, payload.wrappedLocal)
                ?: unwrapRecovery(payload, recoveryPassword)
                ?: throw WrongPasswordException()

        verifyMetadataOrThrow(contentKey, note, payload)

        val body =
            try {
                String(
                    decryptBytes(
                        contentKey,
                        payload.bodyNonce,
                        unbase64(payload.bodyCiphertext),
                        aad(note.uuid, note.version, "body"),
                    ),
                    Charsets.UTF_8,
                )
            } catch (e: Exception) {
                throw CorruptedPayloadException(e)
            }
        val items: List<ListItem> =
            payload.itemsNonce?.let { nonce ->
                val bytes =
                    decryptBytes(
                        contentKey,
                        nonce,
                        unbase64(payload.itemsCiphertext ?: ""),
                        aad(note.uuid, note.version, "items"),
                    )
                Converters.jsonToItems(JSONArray(String(bytes, Charsets.UTF_8)))
            } ?: emptyList()

        sessionKeys[note.uuid] = contentKey
        return OpenedNote(
            note =
                note.copy(
                    body = body,
                    items = items,
                    protection = NoteProtection.ENCRYPTED,
                    locked = note.locked,
                ),
            contentKey = contentKey,
        )
    }

    /**
     * Opens a note with its recovery password and re-wraps the content key with this device's
     * Keystore key, so the note can be opened locally from now on.
     */
    fun adoptRecoveredNote(note: BaseNote, password: CharArray): BaseNote {
        val opened = open(note, password)
        val payload = note.payload ?: throw CorruptedPayloadException()
        val rewrapped = payload.copy(wrappedLocal = wrapLocal(opened.contentKey))
        return note.copy(
            payload =
                rewrapped.copy(metadataMac = metadataMac(opened.contentKey, note, rewrapped))
        )
    }

    /** Rewraps the content key with a new recovery password (or adds one). */
    fun changeRecoveryPassword(note: BaseNote, password: CharArray): BaseNote {
        val payload = note.payload ?: throw CorruptedPayloadException()
        val contentKey =
            sessionKeys[note.uuid] ?: unwrapLocal(note.uuid, payload.wrappedLocal)
                ?: throw CorruptedPayloadException()
        val salt = randomBytes(SALT_BYTES)
        val kek = pbkdf2(password, salt, PBKDF2_ITERATIONS)
        val updated =
            payload.copy(
                wrappedRecovery = wrapWithKek(kek, contentKey),
                recoverySalt = base64(salt),
                recoveryIterations = PBKDF2_ITERATIONS,
            )
        return note.copy(payload = updated.copy(metadataMac = metadataMac(contentKey, note, updated)))
    }

    /** Drops in-memory keys and decrypted attachment copies for one note. */
    fun clearSession(context: ContextWrapper, uuid: String) {
        sessionKeys.remove(uuid)
        cacheDirectory(context, uuid).deleteRecursively()
    }

    fun clearAllSessions(context: ContextWrapper) {
        sessionKeys.clear()
        File(context.cacheDir, CACHE_DIR).deleteRecursively()
    }

    /** Decrypted attachment path used by the UI while a protected note is open. */
    fun cachedAttachment(
        context: ContextWrapper,
        uuid: String,
        subfolder: String,
        localName: String,
    ): File = File(File(cacheDirectory(context, uuid), subfolder), localName)

    /** Encrypted sibling written next to the plaintext attachment. */
    fun encryptedAttachmentFile(
        context: ContextWrapper,
        subfolder: String,
        localName: String,
    ): File = File(directoryFor(context, subfolder), "$localName.enc")

    fun isAttachmentEncrypted(context: ContextWrapper, subfolder: String, localName: String): Boolean =
        encryptedAttachmentFile(context, subfolder, localName).exists()

    /**
     * Decrypts all attachments of [note] into the session cache so existing UI paths keep working.
     * Requires [open] to have succeeded for the same note.
     */
    fun materializeAttachments(context: ContextWrapper, note: BaseNote) {
        val payload = note.payload ?: return
        val contentKey = sessionKeys[note.uuid] ?: return
        payload.attachments.forEach { (localName, ref) ->
            val subfolder =
                when {
                    note.images.any { it.localName == localName } -> SUBFOLDER_IMAGES
                    note.files.any { it.localName == localName } -> SUBFOLDER_FILES
                    note.audios.any { it.name == localName } -> SUBFOLDER_AUDIOS
                    else -> return@forEach
                }
            val encrypted = encryptedAttachmentFile(context, subfolder, localName)
            if (!encrypted.exists()) return@forEach
            val target = cachedAttachment(context, note.uuid, subfolder, localName)
            if (target.exists()) return@forEach
            target.parentFile?.mkdirs()
            try {
                decryptFileTo(contentKey, encrypted, target, unbase64(ref.nonce))
            } catch (e: Exception) {
                target.delete()
            }
        }
    }

    fun verifyMetadata(note: BaseNote): Boolean {
        val payload = note.payload ?: return true
        val key = sessionKeys[note.uuid] ?: return true
        return runCatching { verifyMetadataOrThrow(key, note, payload) }.isSuccess
    }
    /**
     * Converts an opened encrypted note back to an ordinary plaintext note. Requires the user to
     * have authenticated first; the caller is responsible for that check.
     */
    fun makePlain(context: ContextWrapper, openedNote: BaseNote): BaseNote {
        val payload = openedNote.payload ?: return openedNote
        val contentKey = sessionKeys[openedNote.uuid] ?: throw CorruptedPayloadException()
        payload.attachments.forEach { (localName, ref) ->
            val subfolder =
                when {
                    openedNote.images.any { it.localName == localName } -> SUBFOLDER_IMAGES
                    openedNote.files.any { it.localName == localName } -> SUBFOLDER_FILES
                    openedNote.audios.any { it.name == localName } -> SUBFOLDER_AUDIOS
                    else -> return@forEach
                }
            val encrypted = encryptedAttachmentFile(context, subfolder, localName)
            if (!encrypted.exists()) return@forEach
            val plaintext = File(directoryFor(context, subfolder), localName)
            try {
                decryptFileTo(contentKey, encrypted, plaintext, unbase64(ref.nonce))
                encrypted.delete()
            } catch (e: Exception) {
                plaintext.delete()
                throw CorruptedPayloadException(e)
            }
        }
        cacheDirectory(context, openedNote.uuid).deleteRecursively()
        sessionKeys.remove(openedNote.uuid)
        return openedNote.copy(
            protection = NoteProtection.NONE,
            locked = false,
            payload = null,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Attachment encryption
    // ---------------------------------------------------------------------------------------------

    private fun encryptAttachments(
        context: ContextWrapper,
        note: BaseNote,
        contentKey: SecretKey,
        out: MutableMap<String, EncryptedAttachment>,
    ) {
        note.payload?.attachments?.let { out.putAll(it) }

        fun handle(subfolder: String, file: File) {
            if (!file.isFile) return
            val encrypted = encryptedAttachmentFile(context, subfolder, file.name)
            val nonce = randomNonce()
            try {
                encryptFileTo(contentKey, file, encrypted, nonce)
                file.delete()
            } catch (e: Exception) {
                encrypted.delete()
                throw e
            }
            out[file.name] = EncryptedAttachment(base64(nonce), encrypted.length())
        }
        note.images.forEach { image ->
            resolve(context, SUBFOLDER_IMAGES, image.localName)?.let {
                handle(SUBFOLDER_IMAGES, it)
            }
        }
        note.files.forEach { file ->
            resolve(context, SUBFOLDER_FILES, file.localName)?.let { handle(SUBFOLDER_FILES, it) }
        }
        note.audios.forEach { audio ->
            resolve(context, SUBFOLDER_AUDIOS, audio.name)?.let { handle(SUBFOLDER_AUDIOS, it) }
        }
    }

    private fun directoryFor(context: ContextWrapper, subfolder: String): File =
        when (subfolder) {
            SUBFOLDER_IMAGES -> context.getCurrentImagesDirectory()
            SUBFOLDER_FILES -> context.getCurrentFilesDirectory()
            else -> context.getCurrentAudioDirectory()
        }

    private fun resolve(context: ContextWrapper, subfolder: String, name: String): File? {
        val plaintext = File(directoryFor(context, subfolder), name)
        if (plaintext.isFile) return plaintext
        // A previously decrypted copy is the only plaintext available for an already sealed note.
        return sessionKeys.keys
            .map { uuid -> cachedAttachment(context, uuid, subfolder, name) }
            .firstOrNull { it.isFile }
    }

    private fun cacheDirectory(context: ContextWrapper, uuid: String): File =
        File(File(context.cacheDir, CACHE_DIR), uuid)

    // ---------------------------------------------------------------------------------------------
    // Local wrapping (Android Keystore)
    // ---------------------------------------------------------------------------------------------

    private fun wrapLocal(contentKey: SecretKey): String {
        val wrapper = localWrapperKey() ?: throw CryptoException("Content wrapper key unavailable")
        val nonce = randomNonce()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapper, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(contentKey.encoded)
        return base64(nonce + ct)
    }

    private fun unwrapLocal(noteUuid: String?, wrapped: String): SecretKey? {
        val wrapper = localWrapperKey() ?: return null
        val bytes = unbase64(wrapped)
        if (bytes.size <= NONCE_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrapper,
            GCMParameterSpec(GCM_TAG_BITS, bytes.copyOfRange(0, NONCE_BYTES)),
        )
        val raw = cipher.doFinal(bytes.copyOfRange(NONCE_BYTES, bytes.size))
        return SecretKeySpec(raw, "AES")
    }

    private fun localWrapperKey(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            return it
        }
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
        return generator.generateKey()
    }

    // ---------------------------------------------------------------------------------------------
    // Recovery wrapping (portable)
    // ---------------------------------------------------------------------------------------------

    private fun unwrapRecovery(payload: ProtectedPayload, password: CharArray?): SecretKey? {
        val wrapped = payload.wrappedRecovery ?: return null
        val salt = payload.recoverySalt ?: return null
        if (password == null || password.isEmpty()) return null
        val kek = pbkdf2(password, unbase64(salt), payload.recoveryIterations)
        return try {
            unwrapWithKek(kek, wrapped)
        } catch (e: Exception) {
            throw WrongPasswordException()
        }
    }

    private fun wrapWithKek(kek: SecretKey, contentKey: SecretKey): String {
        val nonce = randomNonce()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return base64(nonce + cipher.doFinal(contentKey.encoded))
    }

    private fun unwrapWithKek(kek: SecretKey, wrapped: String): SecretKey {
        val bytes = unbase64(wrapped)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            kek,
            GCMParameterSpec(GCM_TAG_BITS, bytes.copyOfRange(0, NONCE_BYTES)),
        )
        return SecretKeySpec(cipher.doFinal(bytes.copyOfRange(NONCE_BYTES, bytes.size)), "AES")
    }

    // ---------------------------------------------------------------------------------------------
    // Primitives
    // ---------------------------------------------------------------------------------------------

    private fun aad(uuid: String, version: Long, part: String): ByteArray =
        "$uuid|$version|$part".toByteArray(Charsets.UTF_8)

    private fun metadataMac(key: SecretKey, note: BaseNote, payload: ProtectedPayload): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        return base64(mac.doFinal(payload.canonicalMetadata(note).toByteArray(Charsets.UTF_8)))
    }

    private fun verifyMetadataOrThrow(key: SecretKey, note: BaseNote, payload: ProtectedPayload) {
        val expected = metadataMac(key, note, payload)
        if (!constantTimeEquals(expected, payload.metadataMac)) {
            throw CorruptedPayloadException()
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    private fun encryptBytes(key: SecretKey, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private fun decryptBytes(key: SecretKey, nonce: String, data: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, unbase64(nonce)),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private fun encryptFileTo(key: SecretKey, source: File, target: File, nonce: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                output.write(nonce)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(cipher.update(buffer, 0, read))
                }
                output.write(cipher.doFinal())
            }
        }
    }

    private fun decryptFileTo(key: SecretKey, source: File, target: File, nonce: ByteArray) {
        FileInputStream(source).use { input ->
            input.read(ByteArray(NONCE_BYTES))
            FileOutputStream(target).use { output ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(cipher.update(buffer, 0, read))
                }
                output.write(cipher.doFinal())
            }
        }
    }

    private fun newContentKey(): SecretKey {
        val bytes = randomBytes(KEY_BYTES)
        return SecretKeySpec(bytes, "AES")
    }

    private fun randomNonce(): ByteArray = randomBytes(NONCE_BYTES)

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unbase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    /**
     * PBKDF2-HMAC-SHA256 implemented on top of [Mac]: JCA only ships this PRF from API 26, while
     * the app supports API 21.
     */
    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(String(password).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var block = mac.doFinal()
        val result = block.copyOf()
        repeat(iterations - 1) {
            mac.update(block)
            block = mac.doFinal()
            for (i in result.indices) result[i] = (result[i].toInt() xor block[i].toInt()).toByte()
        }
        return SecretKeySpec(result, "AES")
    }

    @Suppress("unused")
    private fun fileLength(file: File): Long = file.length()
}
