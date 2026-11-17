package cool.hin.memox.data.sync.onedrive

import android.content.ContextWrapper
import android.util.Log
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.ContentFormat
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Label
import cool.hin.memox.data.model.NoteProtection
import cool.hin.memox.data.model.NoteTombstone
import cool.hin.memox.data.model.ProtectedPayload
import cool.hin.memox.data.model.newNoteUuid
import cool.hin.memox.data.security.NoteCrypto
import cool.hin.memox.data.security.SecuritySession
import cool.hin.memox.data.sync.SyncLog
import cool.hin.memox.data.sync.SyncResult
import cool.hin.memox.data.sync.SyncOrdering
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.utils.SUBFOLDER_AUDIOS
import cool.hin.memox.utils.SUBFOLDER_FILES
import cool.hin.memox.utils.SUBFOLDER_IMAGES
import cool.hin.memox.utils.deleteAttachments
import cool.hin.memox.utils.getCurrentAudioDirectory
import cool.hin.memox.utils.getCurrentFilesDirectory
import cool.hin.memox.utils.getCurrentImagesDirectory
import cool.hin.memox.utils.resolveAttachmentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles synchronization with OneDrive via the Microsoft Graph API.
 *
 * Protocol v2 (mirrors [cool.hin.memox.data.sync.webdav.WebDavSyncService]):
 * - each note is one JSON file under `memoX/v2/notes/{uuid}.json`, identified by a stable UUID;
 *   the local auto-increment id never leaves the device;
 * - every record carries a logical version and a modified timestamp; writes are conditional on
 *   the eTag read during listing, so a concurrent writer is detected instead of overwritten;
 * - deletions travel as UUID tombstones in `memoX/v2/sync_meta.json`; a missing remote file is
 *   never interpreted as "delete locally";
 * - attachments live under `memoX/v2/attachments/`; encrypted attachments sync as `<name>.enc`.
 *
 * The legacy v1 tree (`memoX/notes`, keyed by a device-local id) is left untouched for rollback.
 */
class OneDriveSyncService(private val context: ContextWrapper) {

    companion object {
        private const val TAG = "OneDriveSync"
        const val REMOTE_DIR = "memoX/v2"
        const val REMOTE_NOTES_DIR = "memoX/v2/notes"
        const val REMOTE_IMAGES_DIR = "memoX/v2/attachments/images"
        const val REMOTE_AUDIOS_DIR = "memoX/v2/attachments/audios"
        const val REMOTE_FILES_DIR = "memoX/v2/attachments/files"
        const val REMOTE_SYNC_META = "memoX/v2/sync_meta.json"
        const val REMOTE_LABELS_FILE = "memoX/v2/labels.json"
        const val LEGACY_NOTES_DIR = "memoX/notes"

        /** 附件目录，用于一次性建立远端附件索引。 */
        private val ATTACHMENT_DIRS = listOf(REMOTE_IMAGES_DIR, REMOTE_FILES_DIR, REMOTE_AUDIOS_DIR)

        /** One file per note, named by stable UUID. */
        fun noteFileName(note: BaseNote): String {
            val name = note.uuid.ifBlank { note.id.toString() }
            return "$name.json"
        }

        /** Extract the note UUID from a v2 filename. Legacy numeric names return null. */
        fun extractNoteUuid(fileName: String): String? {
            if (!fileName.endsWith(".json")) return null
            val name = fileName.removeSuffix(".json")
            return name.takeIf { it.length >= 16 && it.contains('-') }
        }
    }

    private val preferences: MemoXPreferences by lazy { MemoXPreferences.getInstance(context) }

    /**
     * 本次操作期间的远端附件索引：远端目录 -> (文件名 -> 字节数)。
     * 供上传（同名同大小跳过）、下载（本地已完整则跳过）与孤儿清理共用。
     * 为 null 表示索引不可用，此时退化为"总是传输"的行为，正确性不受影响。
     */
    private var remoteAttachmentIndex: Map<String, ConcurrentHashMap<String, Long>>? = null

    /** 远端目录是否已确认存在，避免每次同步都无条件发 5 个建目录请求。 */
    private var remoteDirsEnsured = false

    private fun createClient(): OneDriveClient? {
        if (!OneDriveAuthHelper.isLoggedIn(context)) return null
        return OneDriveClient(context)
    }

    /** Test the OneDrive connection by ensuring the user is signed in. */
    suspend fun testConnection(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("OneDrive not signed in")

        client.testConnection().fold(
            onSuccess = {
                ensureRemoteDirs(client)
                SyncResult.Success("Connection successful")
            },
            onFailure = { SyncResult.Error(it.message ?: "Connection failed") }
        )
    }

    /** Upload current data to OneDrive. */
    suspend fun upload(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("OneDrive not signed in")

        try {
            SyncLog.log("Starting OneDrive upload...")
            ensureRemoteDirs(client)
            loadRemoteAttachmentIndex(client)

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val allNotes = dao.getAllNotesIncludingDeleted()

            SyncLog.log("Found ${allNotes.size} notes to upload")

            // 安全守卫：本地为空时，拒绝"用空库覆盖服务器"。
            if (allNotes.isEmpty()) {
                SyncLog.log("upload: 本地笔记为空，跳过上传以免清空服务器")
                return@withContext SyncResult.Success("本地无数据，已跳过上传（未改动服务器）")
            }

            // Manual "upload" is a merge: it never deletes remote notes just because they are
            // absent locally.
            var uploaded = 0
            var failed = 0

            for (note in allNotes) {
                val outcome =
                    putNote(client = client, note = note, etag = null, createOnly = false)
                when (outcome) {
                    ConditionalResult.SUCCESS -> uploaded++
                    ConditionalResult.PRECONDITION_FAILED -> {
                        failed++
                        SyncLog.log("Conditional write rejected for ${note.uuid}")
                    }
                    ConditionalResult.ERROR -> {
                        failed++
                        SyncLog.log("Failed to upload note ${note.uuid}")
                    }
                }
            }

            cleanupOrphanedAttachments(client, allNotes)
            uploadLabels(client)
            uploadSyncMeta(
                client,
                MemoXDatabase.getDatabase(context, observePreferences = false)
                    .value
                    .getTombstoneDao()
                    .getAll(),
            )

            preferences.onedriveLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Upload complete: $uploaded uploaded, $failed failed")
            SyncResult.Success("Upload complete: $uploaded notes uploaded, $failed failed")
        } catch (e: Exception) {
            SyncLog.log("Upload error: ${e.message}")
            SyncResult.Error(e.message ?: "Upload failed")
        }
    }

    /** Download all remote notes. Never deletes a local note that is merely absent remotely. */
    suspend fun download(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("OneDrive not signed in")

        try {
            SyncLog.log("Starting OneDrive download...")
            ensureRemoteDirs(client)
            loadRemoteAttachmentIndex(client)

            val remoteFiles =
                client.listFiles(REMOTE_NOTES_DIR).getOrElse {
                    return@withContext SyncResult.Error("Cannot list remote notes: ${it.message}")
                }
            val noteFiles =
                remoteFiles
                    .filter { !it.isDirectory }
                    .mapNotNull { file -> extractNoteUuid(file.name)?.let { it to file } }

            if (noteFiles.isEmpty()) {
                SyncLog.log("No remote notes found")
                return@withContext SyncResult.Error("No notes found on OneDrive")
            }

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            var downloaded = 0
            var failed = 0

            for ((uuid, file) in noteFiles) {
                val bytes = client.download("$REMOTE_NOTES_DIR/${file.name}").getOrNull()
                if (bytes == null) {
                    failed++
                    continue
                }
                try {
                    val note = jsonToNote(JSONObject(String(bytes, Charsets.UTF_8)))
                    val existing = dao.getByUuid(uuid)
                    downloadAttachments(client, note)
                    dao.insertSafe(context, note.copy(id = existing?.id ?: 0, uuid = uuid))
                    database.getFolderDao().ensureFolder(note.folderId)
                    downloaded++
                } catch (e: Exception) {
                    failed++
                    SyncLog.log("Failed to parse note $uuid: ${e.message}")
                }
            }

            downloadLabels(client)

            preferences.onedriveLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Download complete: $downloaded downloaded, $failed failed")
            SyncResult.Success("Download complete: $downloaded notes downloaded, $failed failed")
        } catch (e: Exception) {
            SyncLog.log("Download error: ${e.message}")
            SyncResult.Error(e.message ?: "Download failed")
        }
    }

    /** Two-way sync on stable UUIDs with conditional writes and UUID tombstones. */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("OneDrive not signed in")

        try {
            SyncLog.log("Starting OneDrive two-way sync (v2)...")
            ensureRemoteDirs(client)
            loadRemoteAttachmentIndex(client)

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val tombstoneDao = database.getTombstoneDao()

            // Defensive: a row created before the identity migration gets its UUID persisted now.
            for (note in dao.getAllForSync().filter { it.uuid.isBlank() }) {
                dao.insertSafe(context, note)
            }
            var localNotes = dao.getAllForSync().associateBy { it.uuid }

            // A failed listing means "unknown", never "empty": do not delete or download.
            val remoteFiles =
                client.listFiles(REMOTE_NOTES_DIR).getOrElse {
                    return@withContext SyncResult.Error("Cannot list remote notes: ${it.message}")
                }
            val remoteNotes =
                remoteFiles.filter { !it.isDirectory }.mapNotNull { file ->
                    extractNoteUuid(file.name)?.let { it to file }
                }
            val remoteEtags = remoteNotes.associate { (uuid, file) -> uuid to file.eTag }
            val remoteJson = mutableMapOf<String, JSONObject>()
            var fetchFailures = 0
            for ((uuid, file) in remoteNotes) {
                val bytes = client.download("$REMOTE_NOTES_DIR/${file.name}").getOrNull()
                if (bytes == null) {
                    fetchFailures++
                    continue
                }
                runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                    .onSuccess { remoteJson[uuid] = it }
                    .onFailure { fetchFailures++ }
            }
            val snapshotComplete = fetchFailures == 0
            if (!snapshotComplete) {
                SyncLog.log(
                    "Remote snapshot incomplete ($fetchFailures failures); skipping destructive steps"
                )
            }

            val remoteMeta = downloadSyncMeta(client)
            val remoteTombstones = remoteMeta?.tombstones?.associateBy { it.uuid } ?: emptyMap()
            val localTombstones = tombstoneDao.getAll().associateBy { it.uuid }
            val mergedTombstones =
                (remoteTombstones.keys + localTombstones.keys).associateWith { uuid ->
                    maxOf(
                        remoteTombstones[uuid]?.deletedAt ?: 0L,
                        localTombstones[uuid]?.deletedAt ?: 0L,
                    )
                }

            // Apply deletions. A local edit newer than the tombstone wins and is re-published.
            var deletedLocal = 0
            for ((uuid, deletedAt) in mergedTombstones) {
                val local = localNotes[uuid]
                if (local != null && local.modifiedTimestamp > deletedAt) {
                    tombstoneDao.removeApplied(uuid)
                    continue
                }
                if (local != null) {
                    context.deleteAttachments(local.images + local.files + local.audios)
                    NoteCrypto.clearSession(context, uuid)
                    dao.deleteByUuid(uuid)
                    localNotes = localNotes - uuid
                    deletedLocal++
                }
                tombstoneDao.upsert(NoteTombstone(uuid, deletedAt, local?.version ?: 0))
            }

            // Download notes that are new or newer remotely. Inserts keep the local row id.
            var downloaded = 0
            for ((uuid, json) in remoteJson) {
                if (uuid in mergedTombstones) continue
                val local = dao.getByUuid(uuid)
                val incoming = runCatching { jsonToNote(json) }.getOrNull() ?: continue
                if (local != null && !isRemoteNewer(json, local)) continue

                downloadAttachments(client, incoming)
                dao.insertSafe(context, incoming.copy(id = local?.id ?: 0, uuid = uuid))
                database.getFolderDao().ensureFolder(incoming.folderId)
                downloaded++
            }

            // Upload local changes with a precondition, so concurrent writers are detected.
            var uploaded = 0
            var conflicts = 0
            for (local in dao.getAllForSync()) {
                if (local.uuid in mergedTombstones) continue
                val remote = remoteJson[local.uuid]
                if (remote != null && !isLocalNewer(local, remote)) continue

                val outcome =
                    putNote(
                        client = client,
                        note = local,
                        etag = remoteEtags[local.uuid],
                        createOnly = remote == null,
                    )
                when (outcome) {
                    ConditionalResult.SUCCESS -> uploaded++
                    ConditionalResult.PRECONDITION_FAILED -> {
                        // Lost a race: preserve the other writer's content as a conflict copy.
                        val fresh =
                            client
                                .download("$REMOTE_NOTES_DIR/${noteFileName(local)}")
                                .getOrNull()
                                ?.let {
                                    runCatching { JSONObject(String(it, Charsets.UTF_8)) }
                                        .getOrNull()
                                }
                        if (fresh != null) {
                            val remoteNote = jsonToNote(fresh)
                            downloadAttachments(client, remoteNote)
                            dao.insertSafe(
                                context,
                                remoteNote.copy(
                                    id = 0,
                                    uuid = newNoteUuid(),
                                    title =
                                        if (remoteNote.title.isBlank()) "Conflict"
                                        else "${remoteNote.title} (conflict)",
                                    modifiedTimestamp = System.currentTimeMillis(),
                                ),
                            )
                            conflicts++
                        }
                    }
                    ConditionalResult.ERROR -> {
                        SyncLog.log("Upload failed for ${local.uuid}")
                    }
                }
            }

            // Orphan cleanup is destructive; only run on a complete remote snapshot.
            if (snapshotComplete) {
                cleanupOrphanedAttachments(client, dao.getAllNotesIncludingDeleted())
            }
            syncLabels(client)

            // Push pending deletions, then publish the tombstone set.
            for (tombstone in tombstoneDao.getPending()) {
                if (tombstone.protectedContent && !SecuritySession.appUnlocked) {
                    SyncLog.log("Deferring protected remote deletion for ${tombstone.uuid}")
                    continue
                }
                client.delete("$REMOTE_NOTES_DIR/${tombstone.uuid}.json")
                tombstoneDao.markRemoteApplied(tombstone.uuid)
            }
            uploadSyncMeta(client, tombstoneDao.getAll())

            preferences.onedriveLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log(
                "Sync complete: $uploaded uploaded, $downloaded downloaded, " +
                    "$deletedLocal deleted locally, $conflicts conflicts"
            )
            SyncResult.Success(
                "Sync complete: $uploaded up, $downloaded down, $deletedLocal local del, $conflicts conflicts"
            )
        } catch (e: Exception) {
            SyncLog.log("Sync error: ${e.message}")
            SyncResult.Error(e.message ?: "Sync failed")
        }
    }

    private fun isLocalNewer(local: BaseNote, remote: JSONObject): Boolean =
        SyncOrdering.isLocalNewer(
            localVersion = local.version,
            localModified = local.modifiedTimestamp,
            remoteVersion = remote.optLong("version", -1),
            remoteModified = remote.optLong("modifiedTimestamp", 0),
        )

    private fun isRemoteNewer(remote: JSONObject, local: BaseNote): Boolean =
        SyncOrdering.isRemoteNewer(
            localVersion = local.version,
            localModified = local.modifiedTimestamp,
            remoteVersion = remote.optLong("version", -1),
            remoteModified = remote.optLong("modifiedTimestamp", 0),
        )

    private suspend fun putNote(
        client: OneDriveClient,
        note: BaseNote,
        etag: String?,
        createOnly: Boolean,
    ): ConditionalResult {
        val result =
            client.uploadConditional(
                path = "$REMOTE_NOTES_DIR/${noteFileName(note)}",
                data = noteToJson(note).toByteArray(Charsets.UTF_8),
                ifMatch = etag,
                createOnly = createOnly,
            )
        if (result == ConditionalResult.SUCCESS) {
            uploadAttachments(client, note)
        }
        return result
    }

    /** Create the v2 folder tree. Graph creates missing parents automatically. */
    private suspend fun ensureRemoteDirs(client: OneDriveClient) {
        val dirs =
            listOf(
                REMOTE_DIR,
                REMOTE_NOTES_DIR,
                REMOTE_IMAGES_DIR,
                REMOTE_AUDIOS_DIR,
                REMOTE_FILES_DIR,
            )
        for (dir in dirs) {
            val result = client.createDirectory(dir)
            if (result.isFailure) {
                SyncLog.log("Failed to create remote dir '$dir': ${result.exceptionOrNull()?.message}")
            }
        }
        remoteDirsEnsured = true
    }

    private suspend fun ensureRemoteDirsOnce(client: OneDriveClient) {
        if (remoteDirsEnsured) return
        ensureRemoteDirs(client)
    }

    /** 一次性拉取三个附件目录的清单，建立索引。 */
    private suspend fun loadRemoteAttachmentIndex(client: OneDriveClient) {
        val index = mutableMapOf<String, ConcurrentHashMap<String, Long>>()
        var anyDirMissing = false
        for (dir in ATTACHMENT_DIRS) {
            val map = ConcurrentHashMap<String, Long>()
            val files = client.listFiles(dir).getOrNull()
            if (files == null) {
                anyDirMissing = true
            } else {
                for (file in files) if (!file.isDirectory) map[file.name] = file.size
            }
            index[dir] = map
        }
        // 列不出来通常意味着目录还没建过，补一次；空索引正好等价于"远端什么都没有"
        if (anyDirMissing) ensureRemoteDirsOnce(client)
        remoteAttachmentIndex = index
    }

    private fun noteToJson(note: BaseNote): String {
        val json = JSONObject()
        json.put("schema", 2)
        json.put("uuid", note.uuid)
        json.put("version", note.version)
        json.put("contentFormat", note.contentFormat.name)
        json.put("protection", note.protection.name)
        note.payload?.let { json.put("payload", JSONObject(it.toJson())) }
        json.put("type", note.type.name)
        json.put("folder", note.folder.name)
        json.put("color", note.color)
        json.put("title", note.title)
        json.put("pinned", note.pinned)
        json.put("timestamp", note.timestamp)
        json.put("modifiedTimestamp", note.modifiedTimestamp)
        json.put("labels", Converters.labelsToJson(note.labels))
        json.put("folderId", note.folderId)
        json.put("body", note.body)
        json.put("spans", Converters.spansToJson(note.spans))
        json.put("items", Converters.itemsToJson(note.items))
        json.put("images", Converters.filesToJson(note.images))
        json.put("files", Converters.filesToJson(note.files))
        json.put("audios", Converters.audiosToJson(note.audios))
        json.put("reminders", Converters.remindersToJson(note.reminders))
        json.put("isPinnedToStatus", note.isPinnedToStatus)
        json.put("locked", note.locked)
        return json.toString(2)
    }

    private fun jsonToNote(json: JSONObject): BaseNote {
        val locked = json.optBoolean("locked", false)
        val protection =
            json.optString("protection", "")
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { NoteProtection.valueOf(it) }.getOrNull() }
                ?: if (locked) NoteProtection.LEGACY_LOCK else NoteProtection.NONE
        val payload =
            json.optJSONObject("payload")?.let {
                runCatching { ProtectedPayload.fromJson(it.toString()) }.getOrNull()
            }
        return BaseNote(
            id = 0,
            type = cool.hin.memox.data.model.Type.valueOfOrDefault(json.getString("type")),
            folder = Folder.valueOf(json.getString("folder")),
            color = json.optString("color", BaseNote.COLOR_DEFAULT),
            title = json.optString("title", ""),
            pinned = json.optBoolean("pinned", false),
            timestamp = json.getLong("timestamp"),
            modifiedTimestamp = json.optLong("modifiedTimestamp", json.getLong("timestamp")),
            labels = Converters.jsonToLabels(JSONArray(json.optString("labels", "[]"))),
            body = json.optString("body", ""),
            spans = Converters.jsonToSpans(json.optString("spans", "[]")),
            items = Converters.jsonToItems(JSONArray(json.optString("items", "[]"))),
            images = Converters.jsonToFiles(JSONArray(json.optString("images", "[]"))),
            files = Converters.jsonToFiles(JSONArray(json.optString("files", "[]"))),
            audios = Converters.jsonToAudios(JSONArray(json.optString("audios", "[]"))),
            reminders = Converters.jsonToReminders(JSONArray(json.optString("reminders", "[]"))),
            isPinnedToStatus = json.optBoolean("isPinnedToStatus", false),
            locked = locked,
            folderId = json.optString("folderId", ""),
            uuid = json.optString("uuid", ""),
            version = json.optLong("version", 0),
            contentFormat =
                runCatching { ContentFormat.valueOf(json.optString("contentFormat", "RICH_TEXT")) }
                    .getOrDefault(ContentFormat.RICH_TEXT),
            protection = protection,
            payload = payload,
        )
    }

    /** Upload all attachments for a note (skips what OneDrive already has). */
    private suspend fun uploadAttachments(client: OneDriveClient, note: BaseNote) {
        for (img in note.images) {
            uploadAttachment(client, REMOTE_IMAGES_DIR, SUBFOLDER_IMAGES, img.localName)
        }
        for (f in note.files) {
            uploadAttachment(client, REMOTE_FILES_DIR, SUBFOLDER_FILES, f.localName)
        }
        for (audio in note.audios) {
            uploadAttachment(client, REMOTE_AUDIOS_DIR, SUBFOLDER_AUDIOS, audio.name)
        }
    }

    /**
     * 上传单个附件。加密笔记的附件以 `<name>.enc` 上传；远端已有同名且字节数一致时直接跳过。
     */
    private suspend fun uploadAttachment(
        client: OneDriveClient,
        remoteDir: String,
        subfolder: String,
        name: String,
    ) {
        val encrypted = NoteCrypto.encryptedAttachmentFile(context, subfolder, name)
        val remoteName = if (encrypted.exists()) "$name.enc" else name
        val file = if (encrypted.exists()) encrypted else getLocalAttachmentFile(subfolder, name)
        if (file == null || !file.exists()) {
            Log.d(TAG, "uploadAttachment: local file missing, skip $subfolder/$name")
            return
        }
        val localSize = file.length()
        val known = remoteAttachmentIndex?.get(remoteDir)
        if (known != null && known[remoteName] == localSize) return

        val result = client.upload("$remoteDir/$remoteName", file.readBytes())
        if (result.isSuccess) {
            known?.put(remoteName, localSize)
        } else {
            Log.w(TAG, "uploadAttachment: failed $remoteDir/$remoteName: ${result.exceptionOrNull()?.message}")
        }
    }

    /** Download all attachments for a note (skips what is already on disk). */
    private suspend fun downloadAttachments(client: OneDriveClient, note: BaseNote) {
        for (img in note.images) {
            downloadAttachment(client, REMOTE_IMAGES_DIR, SUBFOLDER_IMAGES, img.localName)
        }
        for (f in note.files) {
            downloadAttachment(client, REMOTE_FILES_DIR, SUBFOLDER_FILES, f.localName)
        }
        for (audio in note.audios) {
            downloadAttachment(client, REMOTE_AUDIOS_DIR, SUBFOLDER_AUDIOS, audio.name)
        }
    }

    /** 下载单个附件。远端是 `.enc` 时写入本地加密文件；本地已完整则跳过。 */
    private suspend fun downloadAttachment(
        client: OneDriveClient,
        remoteDir: String,
        subfolder: String,
        name: String,
    ) {
        val remoteName =
            if (remoteAttachmentIndex?.get(remoteDir)?.containsKey("$name.enc") == true) {
                "$name.enc"
            } else {
                name
            }
        val localFile =
            if (remoteName.endsWith(".enc")) {
                NoteCrypto.encryptedAttachmentFile(context, subfolder, name)
            } else {
                ensureLocalAttachmentFile(subfolder, name) ?: return
            }
        val remoteSize = remoteAttachmentIndex?.get(remoteDir)?.get(remoteName)
        if (
            localFile.exists() &&
                localFile.length() > 0 &&
                (remoteSize == null || remoteSize == localFile.length())
        ) {
            return
        }
        client.download("$remoteDir/$remoteName").getOrNull()?.let { bytes ->
            localFile.parentFile?.mkdirs()
            localFile.writeBytes(bytes)
        }
    }

    private fun getLocalAttachmentFile(subfolder: String, localName: String): File? {
        return context.resolveAttachmentFile(subfolder, localName)
    }

    private fun ensureLocalAttachmentFile(subfolder: String, localName: String): File? {
        val dir = when (subfolder) {
            SUBFOLDER_IMAGES -> context.getCurrentImagesDirectory()
            SUBFOLDER_FILES -> context.getCurrentFilesDirectory()
            SUBFOLDER_AUDIOS -> context.getCurrentAudioDirectory()
            else -> return null
        }
        return File(dir, localName)
    }

    /** A referenced attachment may exist remotely as plaintext or as an encrypted sibling. */
    private fun attachmentNames(names: List<String>): Set<String> =
        names.flatMap { listOf(it, "$it.enc") }.toSet()

    /**
     * Remove remote attachments that are no longer referenced by any note.
     *
     * 安全守卫：本地笔记为空时【绝不】删除服务器附件。
     */
    private suspend fun cleanupOrphanedAttachments(client: OneDriveClient, allNotes: List<BaseNote>) {
        if (allNotes.isEmpty()) {
            SyncLog.log("cleanupOrphanedAttachments: 本地笔记为空，跳过以免误删服务器附件")
            return
        }
        try {
            val referencedImages = attachmentNames(allNotes.flatMap { it.images.map { img -> img.localName } })
            val referencedFiles = attachmentNames(allNotes.flatMap { it.files.map { f -> f.localName } })
            val referencedAudios = attachmentNames(allNotes.flatMap { it.audios.map { a -> a.name } })

            cleanupOrphanedDir(client, REMOTE_IMAGES_DIR, referencedImages)
            cleanupOrphanedDir(client, REMOTE_FILES_DIR, referencedFiles)
            cleanupOrphanedDir(client, REMOTE_AUDIOS_DIR, referencedAudios)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup orphaned attachments", e)
        }
    }

    private suspend fun cleanupOrphanedDir(
        client: OneDriveClient,
        dir: String,
        referencedNames: Set<String>,
    ) {
        val known = remoteAttachmentIndex?.get(dir)
        val names =
            known?.keys?.toList()
                ?: client.listFiles(dir).getOrNull()?.filterNot { it.isDirectory }?.map { it.name }
                ?: return
        for (name in names) {
            if (name !in referencedNames) {
                client.delete("$dir/$name")
                known?.remove(name)
                SyncLog.log("Deleted orphaned attachment: $name")
            }
        }
    }

    /** Delete a single note and its attachments from OneDrive, and record a UUID tombstone. */
    suspend fun deleteRemoteNote(note: BaseNote) = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext
        try {
            client.delete("$REMOTE_NOTES_DIR/${noteFileName(note)}")
            deleteRemoteAttachments(client, note)
            addTombstone(note)
            SyncLog.log("Deleted remote note: ${note.uuid}")
        } catch (e: Exception) {
            Log.w(TAG, "deleteRemoteNote failed: ${e.message}")
        }
    }

    /** Delete multiple notes and their attachments from OneDrive, and record tombstones. */
    suspend fun deleteRemoteNotes(notes: Collection<BaseNote>) = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext
        try {
            for (note in notes) {
                client.delete("$REMOTE_NOTES_DIR/${noteFileName(note)}")
                deleteRemoteAttachments(client, note)
                addTombstone(note)
            }
            SyncLog.log("Deleted ${notes.size} remote notes")
        } catch (e: Exception) {
            Log.w(TAG, "deleteRemoteNotes failed: ${e.message}")
        }
    }

    private suspend fun deleteRemoteAttachments(client: OneDriveClient, note: BaseNote) {
        for (img in note.images) {
            client.delete("$REMOTE_IMAGES_DIR/${img.localName}")
            client.delete("$REMOTE_IMAGES_DIR/${img.localName}.enc")
        }
        for (f in note.files) {
            client.delete("$REMOTE_FILES_DIR/${f.localName}")
            client.delete("$REMOTE_FILES_DIR/${f.localName}.enc")
        }
        for (audio in note.audios) {
            client.delete("$REMOTE_AUDIOS_DIR/${audio.name}")
            client.delete("$REMOTE_AUDIOS_DIR/${audio.name}.enc")
        }
    }

    /** Record a tombstone locally. The sync loop pushes it with a conditional delete. */
    private suspend fun addTombstone(note: BaseNote) {
        if (note.uuid.isBlank()) return
        val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
        database
            .getTombstoneDao()
            .upsert(
                NoteTombstone(
                    uuid = note.uuid,
                    deletedAt = System.currentTimeMillis(),
                    version = note.version,
                    protectedContent = note.protection.isEncrypted,
                )
            )
    }

    /** Upload sync metadata. Deletions are UUIDs; local row ids never leave this device. */
    private suspend fun uploadSyncMeta(client: OneDriveClient, tombstones: List<NoteTombstone>) {
        try {
            val json =
                JSONObject().apply {
                    put("schema", 2)
                    put("lastSyncTime", System.currentTimeMillis())
                    val array = JSONArray()
                    for (tombstone in tombstones) {
                        array.put(
                            JSONObject()
                                .put("uuid", tombstone.uuid)
                                .put("deletedAt", tombstone.deletedAt)
                                .put("version", tombstone.version)
                                .put("protectedContent", tombstone.protectedContent)
                        )
                    }
                    put("tombstones", array)
                }
            client.upload(REMOTE_SYNC_META, json.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "uploadSyncMeta failed: ${e.message}")
        }
    }

    private data class SyncTombstone(val uuid: String, val deletedAt: Long)

    private data class SyncMeta(val tombstones: List<SyncTombstone>)

    /** Download sync metadata */
    private suspend fun downloadSyncMeta(client: OneDriveClient): SyncMeta? {
        return try {
            val result = client.download(REMOTE_SYNC_META)
            val bytes = result.getOrNull() ?: return null
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.optInt("schema", 0) < 2) return SyncMeta(emptyList())

            val tombstones = mutableListOf<SyncTombstone>()
            val array = json.optJSONArray("tombstones")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val uuid = obj.optString("uuid", "")
                    if (uuid.isNotEmpty()) {
                        tombstones.add(SyncTombstone(uuid, obj.optLong("deletedAt", 0)))
                    }
                }
            }
            SyncMeta(tombstones)
        } catch (_: Exception) {
            null
        }
    }

    /** Upload labels and hidden labels config to OneDrive. */
    private suspend fun uploadLabels(client: OneDriveClient) {
        try {
            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val labelDao = database.getLabelDao()
            val labels = labelDao.getArrayOfAll()
            val hiddenLabels = preferences.labelsHidden.value

            val json = JSONObject().apply {
                val labelsArray = JSONArray()
                for (label in labels) {
                    labelsArray.put(label)
                }
                put("labels", labelsArray)
                val hiddenArray = JSONArray()
                for (hidden in hiddenLabels) {
                    hiddenArray.put(hidden)
                }
                put("hiddenLabels", hiddenArray)
            }
            client.upload(REMOTE_LABELS_FILE, json.toString().toByteArray(Charsets.UTF_8))
            preferences.labelsHiddenLastSynced.save(hiddenLabels)
        } catch (e: Exception) {
            Log.w(TAG, "uploadLabels: failed: ${e.message}")
        }
    }

    private suspend fun downloadLabels(client: OneDriveClient) {
        try {
            val result = client.download(REMOTE_LABELS_FILE)
            val bytes = result.getOrNull() ?: return
            val json = JSONObject(String(bytes, Charsets.UTF_8))

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val labelDao = database.getLabelDao()

            val labelsArray = json.optJSONArray("labels") ?: return
            val labels = mutableListOf<Label>()
            for (i in 0 until labelsArray.length()) {
                val value = labelsArray.getString(i)
                labels.add(Label(value, i))
            }

            labelDao.deleteAll()
            labelDao.insert(labels)

            val hiddenArray = json.optJSONArray("hiddenLabels")
            if (hiddenArray != null) {
                val hiddenSet = mutableSetOf<String>()
                for (i in 0 until hiddenArray.length()) {
                    hiddenSet.add(hiddenArray.getString(i))
                }
                preferences.labelsHidden.save(hiddenSet)
                preferences.labelsHiddenLastSynced.save(hiddenSet)
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadLabels: failed: ${e.message}")
        }
    }

    private suspend fun syncLabels(client: OneDriveClient) {
        try {
            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val labelDao = database.getLabelDao()
            val localLabels = labelDao.getArrayOfAll().toSet()
            val localHidden = preferences.labelsHidden.value

            val result = client.download(REMOTE_LABELS_FILE)
            if (result.isFailure) {
                // Download failed (network/auth/transient). Do NOT overwrite the remote labels
                // file with local state here, otherwise a transient failure on this device would
                // clobber another device's (e.g. hidden-label) changes that are already on the server.
                Log.w(TAG, "syncLabels: download failed, skipping: ${result.exceptionOrNull()?.message}")
                return
            }
            val bytes = result.getOrNull()
            if (bytes == null) {
                uploadLabels(client)
                return
            }

            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val remoteArray = json.optJSONArray("labels") ?: return
            val remoteLabels = mutableSetOf<String>()
            for (i in 0 until remoteArray.length()) {
                remoteLabels.add(remoteArray.getString(i))
            }

            val remoteHiddenArray = json.optJSONArray("hiddenLabels")
            val remoteHidden = mutableSetOf<String>()
            if (remoteHiddenArray != null) {
                for (i in 0 until remoteHiddenArray.length()) {
                    remoteHidden.add(remoteHiddenArray.getString(i))
                }
            }

            val merged = (localLabels + remoteLabels).toMutableSet()
            if (merged != localLabels) {
                labelDao.deleteAll()
                labelDao.insert(merged.mapIndexed { index, name -> Label(name, index) })
            }

            val lastSynced = preferences.labelsHiddenLastSynced.value
            val mergedHidden = (localHidden + remoteHidden).toMutableSet()
            (lastSynced - remoteHidden).forEach { mergedHidden.remove(it) }
            (localHidden - lastSynced).forEach { mergedHidden.add(it) }
            (lastSynced - localHidden).forEach { mergedHidden.remove(it) }
            preferences.labelsHidden.save(mergedHidden)

            uploadLabels(client)
        } catch (e: Exception) {
            Log.w(TAG, "syncLabels: failed: ${e.message}")
        }
    }
}
