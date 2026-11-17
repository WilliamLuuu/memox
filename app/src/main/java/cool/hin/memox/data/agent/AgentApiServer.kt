package cool.hin.memox.data.agent

import android.content.ContextWrapper
import android.util.Base64
import android.util.Log
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.ContentFormat
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.NoteProtection
import cool.hin.memox.data.model.ProtectedPayload
import cool.hin.memox.data.model.newNoteUuid
import cool.hin.memox.data.security.NoteCrypto
import cool.hin.memox.data.sync.SyncRouter
import cool.hin.memox.data.sync.SyncState
import cool.hin.memox.data.sync.SyncStatus
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Pre-auth header parsing exceeded the configured bounds; answered with 431. */
private class HeaderLimitException : IOException("request headers too large")

/** A request exceeded its total time budget; answered with 408. */
private class RequestTimeoutException : IOException("request timeout")

/**
 * Versioned HTTP API consumed by the desktop MCP adapter and local scripts.
 *
 * - binds the configured address (loopback by default; `0.0.0.0`/interface IPs are opt-in);
 * - every request needs `Authorization: Bearer <pairing token>`;
 * - runs only while the foreground service is alive and the preference is enabled;
 * - protected notes expose public metadata only; their body requires a valid recovery password via
 *   `X-Memox-Recovery` and is decrypted with that password alone (never the local Keystore);
 * - mutating requests honour `Idempotency-Key` for safe retries.
 */
class AgentApiServer(private val context: ContextWrapper) {

    companion object {
        const val PORT = AgentApiHttp.PORT
        const val DEFAULT_HOST = AgentApiHttp.DEFAULT_HOST
        private const val MAX_BODY_BYTES = 1_000_000
        private const val IDEMPOTENCY_TTL_MS = 10 * 60 * 1000L
        private const val MAX_HEADER_BYTES = AgentApiHttp.MAX_HEADER_BYTES
        private const val MAX_HEADER_COUNT = AgentApiHttp.MAX_HEADER_COUNT
        private const val REQUEST_DEADLINE_MS = AgentApiHttp.REQUEST_DEADLINE_MS
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12

        /** Formats a bind host for use as an HTTP URL authority (IPv6 needs brackets). */
        fun formatEndpointHost(host: String): String = AgentApiHttp.formatEndpointHost(host)

        /** True when [lineLength] fits the pre-auth header/request-line allowance. */
        fun isAllowedHeaderLine(lineLength: Int): Boolean =
            AgentApiHttp.isAllowedHeaderLine(lineLength)

        /** Formats the pairing dialog URL from a bind host. */
        fun formatEndpoint(host: String, port: Int = PORT): String =
            AgentApiHttp.formatEndpoint(host, port)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val idempotency = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    private val preferences = MemoXPreferences.getInstance(context)

    fun start(): Boolean {
        if (serverSocket != null) return true
        val host = preferences.agentApiHost.value.trim().ifEmpty { DEFAULT_HOST }
        var socket: ServerSocket? = null
        try {
            // Assign the instance before bind: if bind() throws, `apply` would otherwise never
            // return and the raw socket would be lost (leaking a file descriptor on every retry).
            val candidate = ServerSocket()
            socket = candidate
            candidate.reuseAddress = true
            candidate.bind(InetSocketAddress(InetAddress.getByName(host), PORT))
        } catch (e: Exception) {
            // bind() failed; close the already-created socket so retries cannot leak descriptors.
            runCatching { socket?.close() }
            Log.e("AgentApiServer", "Cannot bind $host:$PORT", e)
            return false
        }
        val bound = socket ?: return false
        serverSocket = bound
        acceptJob =
            scope.launch {
                while (isActive) {
                    val client =
                        try {
                            bound.accept()
                        } catch (_: Exception) {
                            break
                        }
                    launch(Dispatchers.IO) { handle(client) }
                }
            }
        return true
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    fun ensureToken(): String {
        val existing = preferences.agentApiToken.value
        if (existing.isNotEmpty()) return existing
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP)
        preferences.agentApiToken.save(token)
        return token
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP plumbing
    // ---------------------------------------------------------------------------------------------

    private suspend fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = REQUEST_DEADLINE_MS.toInt()
            val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                // ---- Phase 1: bounded, unauthenticated parse ---------------------------------
                // A peer that has not authenticated yet must not be able to grow our memory
                // without limit, nor keep the connection alive indefinitely with tiny reads.
                val requestLine = readLine(input, client, deadline) ?: return
                checkRemaining(deadline)
                val parts = requestLine.split(' ')
                if (parts.size < 3) return respond(output, 400, error("bad request line"))
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")

                val headers = mutableMapOf<String, String>()
                var headerBytes = 0
                var headerCount = 0
                while (true) {
                    checkRemaining(deadline)
                    val line = readLine(input, client, deadline) ?: break
                    headerBytes += line.length + 2 // CRLF
                    headerCount++
                    if (!isAllowedHeaderLine(line.length) ||
                        headerBytes > MAX_HEADER_BYTES ||
                        headerCount > MAX_HEADER_COUNT
                    ) {
                        throw HeaderLimitException()
                    }
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    return respond(output, 413, error("too large"))
                }

                // ---- Phase 2: authenticate before reading any body ---------------------------
                if (!authorized(headers)) {
                    return respond(output, 401, error("unauthorized"))
                }

                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    constrainRead(client, deadline)
                    val count = input.read(body, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }

                val idempotencyKey = headers["idempotency-key"]
                // Final gate before routing: idempotency replay and route() perform database
                // writes, so a request that overran its deadline must not reach them.
                checkRemaining(deadline)
                if (idempotencyKey != null && method != "GET") {
                    idempotency[idempotencyKey]?.let { (timestamp, cached) ->
                        if (System.currentTimeMillis() - timestamp < IDEMPOTENCY_TTL_MS) {
                            return respond(output, 200, cached)
                        }
                        idempotency.remove(idempotencyKey)
                    }
                }

                val response =
                    route(method, path, Query.parse(query), headers, body.toString(Charsets.UTF_8))
                if (idempotencyKey != null && response.optInt("status", 500) < 500) {
                    idempotency[idempotencyKey] =
                        System.currentTimeMillis() to response.getJSONObject("body")
                }
                respond(
                    output,
                    response.optInt("status", 200),
                    response.optJSONObject("body") ?: JSONObject(),
                )
            } catch (_: HeaderLimitException) {
                runCatching { respond(output, 431, error("headers too large")) }
            } catch (_: RequestTimeoutException) {
                runCatching { respond(output, 408, error("request timeout")) }
            } catch (_: java.net.SocketTimeoutException) {
                runCatching { respond(output, 408, error("request timed out")) }
            } catch (e: Exception) {
                runCatching {
                    respond(output, 500, error(e.message ?: "internal error"))
                }
            }
        }
    }

    private fun checkRemaining(deadline: Long) {
        if (System.currentTimeMillis() > deadline) throw RequestTimeoutException()
    }

    /** Caps the next blocking read at the remaining deadline so a slow peer cannot overrun it. */
    private fun constrainRead(client: Socket, deadline: Long) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) throw RequestTimeoutException()
        client.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val expected = preferences.agentApiToken.value
        if (expected.isEmpty()) return false
        val provided = headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun readLine(input: BufferedInputStream, client: Socket, deadline: Long): String? {
        val buffer = StringBuilder()
        while (true) {
            constrainRead(client, deadline)
            val value = input.read()
            if (value < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (value == '\n'.code) return buffer.toString().trimEnd('\r')
            if (!isAllowedHeaderLine(buffer.length)) throw HeaderLimitException()
            buffer.append(value.toChar())
        }
    }

    private fun respond(output: BufferedOutputStream, status: Int, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val reason =
            when (status) {
                200 -> "OK"
                201 -> "Created"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                408 -> "Request Timeout"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                431 -> "Request Header Fields Too Large"
                else -> "Internal Server Error"
            }
        val headers =
            "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(payload)
        output.flush()
    }

    private fun error(message: String): JSONObject = JSONObject().put("error", message)

    private fun wrapped(status: Int, body: JSONObject): JSONObject =
        JSONObject().put("status", status).put("body", body)

    // ---------------------------------------------------------------------------------------------
    // Routes
    // ---------------------------------------------------------------------------------------------

    private suspend fun route(
        method: String,
        path: String,
        query: Query,
        headers: Map<String, String>,
        body: String,
    ): JSONObject {
        val db = MemoXDatabase.getDatabase(context, observePreferences = false).value
        val dao = db.getBaseNoteDao()
        return when {
            method == "GET" && path == "/v1/status" ->
                wrapped(
                    200,
                    JSONObject()
                        .put("version", 1)
                        .put("syncing", SyncStatus.state.value?.state == SyncState.SYNCING)
                        .put("notes", dao.getAllForSync().size),
                )
            method == "POST" && path == "/v1/sync" -> {
                SyncRouter.syncNow(context)
                wrapped(200, JSONObject().put("started", true))
            }
            method == "GET" && path == "/v1/notes" -> wrapped(200, listNotes(dao, query))
            method == "GET" && path.startsWith("/v1/notes/") -> {
                val uuid = path.removePrefix("/v1/notes/")
                val note = dao.getByUuid(uuid)
                if (note == null) wrapped(404, error("note not found"))
                else
                    wrapped(
                        200,
                        noteToJson(
                            note,
                            includeBody = true,
                            recoveryPassword = headers["x-memox-recovery"],
                        ),
                    )
            }
            method == "POST" && path == "/v1/notes" -> createNote(dao, body)
            method == "PATCH" && path.startsWith("/v1/notes/") -> {
                updateNote(dao, path.removePrefix("/v1/notes/"), body)
            }
            method == "DELETE" && path.startsWith("/v1/notes/") -> {
                trashNote(dao, db, path.removePrefix("/v1/notes/"), query)
            }
            else -> wrapped(404, error("unknown endpoint"))
        }
    }

    private suspend fun listNotes(
        dao: cool.hin.memox.data.dao.BaseNoteDao,
        query: Query,
    ): JSONObject {
        val keyword = query["query"].orEmpty()
        val limit = (query["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (query["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val label = query["label"]
        val from = query["from"]?.toLongOrNull()
        val to = query["to"]?.toLongOrNull()
        val includeTrash = query["trash"] == "true"

        val filtered =
            dao.getAllForSync()
                .asSequence()
                .filter { includeTrash || it.folder != Folder.DELETED }
                .filter { label == null || it.labels.contains(label) }
                .filter { from == null || it.timestamp >= from }
                .filter { to == null || it.timestamp <= to }
                .filter {
                    keyword.isEmpty() ||
                        it.title.contains(keyword, ignoreCase = true) ||
                        (!it.protection.isEncrypted &&
                            it.body.contains(keyword, ignoreCase = true))
                }
                .sortedByDescending { it.modifiedTimestamp }
                .toList()
        val page = filtered.drop(offset).take(limit)
        val array = JSONArray()
        page.forEach { array.put(noteToJson(it, includeBody = false, recoveryPassword = null)) }
        return JSONObject()
            .put("total", filtered.size)
            .put("offset", offset)
            .put("limit", limit)
            .put("notes", array)
    }

    private suspend fun createNote(
        dao: cool.hin.memox.data.dao.BaseNoteDao,
        body: String,
    ): JSONObject {
        val json = runCatching { JSONObject(body) }.getOrElse { return wrapped(400, error("invalid JSON")) }
        val now = System.currentTimeMillis()
        val format =
            when (json.optString("format", "RICH_TEXT")) {
                "MARKDOWN" -> ContentFormat.MARKDOWN
                else -> ContentFormat.RICH_TEXT
            }
        val note =
            BaseNote(
                id = 0,
                type = cool.hin.memox.data.model.Type.NOTE,
                folder = Folder.NOTES,
                color = BaseNote.COLOR_DEFAULT,
                title = json.optString("title", ""),
                pinned = false,
                timestamp = now,
                modifiedTimestamp = now,
                labels = json.optJSONArray("labels")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList(),
                body = json.optString("body", ""),
                spans = emptyList(),
                items = emptyList(),
                images = emptyList(),
                files = emptyList(),
                audios = emptyList(),
                reminders = emptyList(),
                isPinnedToStatus = false,
                uuid = newNoteUuid(),
                version = 1,
                contentFormat = format,
                protection = NoteProtection.NONE,
            )
        val id = dao.insertSafe(context, note)
        val saved = dao.get(id) ?: note
        SyncRouter.syncNow(context)
        return wrapped(201, noteToJson(saved, includeBody = true, recoveryPassword = null))
    }

    private suspend fun updateNote(
        dao: cool.hin.memox.data.dao.BaseNoteDao,
        uuid: String,
        body: String,
    ): JSONObject {
        val existing = dao.getByUuid(uuid) ?: return wrapped(404, error("note not found"))
        if (existing.protection.isEncrypted) {
            return wrapped(409, error("protected note: edit it on the device after authentication"))
        }
        val json = runCatching { JSONObject(body) }.getOrElse { return wrapped(400, error("invalid JSON")) }
        val expectedVersion = json.optLong("expectedVersion", -1)
        if (expectedVersion >= 0 && expectedVersion != existing.version) {
            return wrapped(
                409,
                JSONObject()
                    .put("error", "version conflict")
                    .put("expectedVersion", expectedVersion)
                    .put("currentVersion", existing.version),
            )
        }
        val updated =
            existing.copy(
                title = json.optString("title", existing.title),
                body = json.optString("body", existing.body),
                labels = json.optJSONArray("labels")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: existing.labels,
                modifiedTimestamp = System.currentTimeMillis(),
                version = existing.version + 1,
            )
        dao.insertSafe(context, updated)
        SyncRouter.syncNow(context)
        return wrapped(200, noteToJson(updated, includeBody = true, recoveryPassword = null))
    }

    private suspend fun trashNote(
        dao: cool.hin.memox.data.dao.BaseNoteDao,
        db: MemoXDatabase,
        uuid: String,
        query: Query,
    ): JSONObject {
        val existing = dao.getByUuid(uuid) ?: return wrapped(404, error("note not found"))
        if (query["permanent"] == "true") {
            if (existing.protection.isEncrypted) {
                return wrapped(
                    409,
                    error("permanent deletion of a protected note requires on-device confirmation"),
                )
            }
            db.getTombstoneDao()
                .upsert(
                    cool.hin.memox.data.model.NoteTombstone(
                        uuid = uuid,
                        deletedAt = System.currentTimeMillis(),
                        version = existing.version,
                    )
                )
            dao.deleteByUuid(uuid)
            return wrapped(200, JSONObject().put("deleted", true))
        }
        dao.trashByUuid(uuid, Folder.DELETED, System.currentTimeMillis())
        return wrapped(200, JSONObject().put("trashed", true))
    }

    /**
     * Serializes a note for the API. For encrypted notes the body is only included when a
     * *recovery password* is supplied and successfully verifies the payload. Decryption here is
     * deliberately independent of [NoteCrypto]'s session key cache and Android Keystore wrap:
     * a remote caller with only the pairing token must never be able to read protected content
     * via keys that happen to be unlocked on this device. On any failure we return public
     * metadata only (no body), which is the documented "protected content" rejection.
     */
    private fun noteToJson(
        note: BaseNote,
        includeBody: Boolean,
        recoveryPassword: String?,
    ): JSONObject {
        val json =
            JSONObject()
                .put("id", note.uuid)
                .put("version", note.version)
                .put("title", note.title)
                .put("labels", JSONArray(note.labels))
                .put("createdAt", note.timestamp)
                .put("updatedAt", note.modifiedTimestamp)
                .put("folder", note.folder.name)
                .put("format", note.contentFormat.name)
                .put("locked", note.locked)
                .put("protection", note.protection.name)
                .put("pinned", note.pinned)
        if (!includeBody) return json
        if (!note.protection.isEncrypted) {
            json.put("body", note.body)
            return json
        }
        // Encrypted note: only a verified recovery password may unlock the body. Any failure
        // (missing/incorrect password, no recovery material, corrupt payload) yields metadata only,
        // with an explicit flag so clients never mistake the omission for an empty plaintext body.
        val body = decryptBodyWithRecovery(note, recoveryPassword)
        if (body == null) {
            json.put("bodyLocked", true)
            return json
        }
        json.put("body", body)
        json.put("items", JSONArray())
        return json
    }

    /**
     * Decrypts [note]'s body using only its recovery-password wrap. Returns null on every failure.
     * Mirrors NoteCrypto's primitives so the result is byte-for-byte compatible, but never reads
     * `sessionKeys` or the local Keystore, and never populates either.
     */
    private fun decryptBodyWithRecovery(note: BaseNote, recoveryPassword: String?): String? {
        if (recoveryPassword.isNullOrEmpty()) return null
        val payload = note.payload ?: return null
        // Match NoteCrypto.open: unknown envelope versions must fail closed, not be tried as v1.
        if (payload.formatVersion != cool.hin.memox.data.model.ProtectedPayload.CURRENT_FORMAT_VERSION) return null
        val wrapped = payload.wrappedRecovery ?: return null
        val salt = payload.recoverySalt ?: return null
        return try {
            val kek = pbkdf2(recoveryPassword.toCharArray(), unbase64(salt), payload.recoveryIterations)
            val contentKey = unwrapWithKek(kek, wrapped)
            // Verify the metadata MAC before touching ciphertext: a wrong password or a tampered
            // payload must fail closed, and must not be tolerated because a local key would work.
            val expectedMac = metadataMac(contentKey, note, payload)
            if (!constantTimeEquals(expectedMac, payload.metadataMac)) return null
            val bytes =
                decryptBytes(
                    contentKey,
                    payload.bodyNonce,
                    unbase64(payload.bodyCiphertext),
                    aad(note.uuid, note.version, "body"),
                )
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(String(password).toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
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

    private fun decryptBytes(
        key: SecretKey,
        nonce: String,
        data: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, unbase64(nonce)))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private fun metadataMac(key: SecretKey, note: BaseNote, payload: ProtectedPayload): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        return base64(mac.doFinal(payload.canonicalMetadata(note).toByteArray(StandardCharsets.UTF_8)))
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    private fun aad(uuid: String, version: Long, part: String): ByteArray =
        "$uuid|$version|$part".toByteArray(StandardCharsets.UTF_8)

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unbase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private class Query(private val values: Map<String, String>) {
        operator fun get(key: String): String? = values[key]

        companion object {
            fun parse(raw: String): Query {
                val map = mutableMapOf<String, String>()
                raw.split('&').forEach { pair ->
                    if (pair.isEmpty()) return@forEach
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        map[
                            URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        ] = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    }
                }
                return Query(map)
            }
        }
    }
}
