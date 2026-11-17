package cool.hin.memox.presentation.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import androidx.core.text.getSpans
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.dao.BaseNoteDao
import cool.hin.memox.data.imports.txt.extractListItems
import cool.hin.memox.data.imports.txt.findListSyntaxRegex
import cool.hin.memox.data.model.Audio
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.ListItem
import cool.hin.memox.data.model.Reminder
import cool.hin.memox.data.model.SpanRepresentation
import cool.hin.memox.data.model.Type
import cool.hin.memox.data.model.ContentFormat
import cool.hin.memox.data.model.NoteProtection
import cool.hin.memox.data.model.ProtectedPayload
import cool.hin.memox.data.model.attachmentsDifferFrom
import cool.hin.memox.data.model.copy
import cool.hin.memox.data.model.deepCopy
import cool.hin.memox.data.sync.SyncRouter
import cool.hin.memox.data.security.NoteCrypto
import cool.hin.memox.presentation.IMAGE_PLACEHOLDER
import cool.hin.memox.presentation.InlineImageSpan
import cool.hin.memox.presentation.activity.note.reminders.ReminderReceiver
import cool.hin.memox.presentation.activity.note.reminders.RemindersActivity.Companion.NEW_REMINDER_ID
import cool.hin.memox.presentation.applySpans
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.misc.NotNullLiveData
import cool.hin.memox.presentation.view.misc.Progress
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.viewmodel.preference.TextSizeSp
import cool.hin.memox.presentation.viewmodel.progress.AddFilesProgress
import cool.hin.memox.presentation.widget.WidgetProvider
import cool.hin.memox.presentation.withoutImagePlaceholders
import cool.hin.memox.utils.Cache
import cool.hin.memox.utils.Event
import cool.hin.memox.utils.FileError
import cool.hin.memox.utils.backup.importAudio
import cool.hin.memox.utils.backup.importFile
import cool.hin.memox.utils.cancelPinAndReminders
import cool.hin.memox.utils.cancelReminder
import cool.hin.memox.utils.deleteAttachments
import cool.hin.memox.utils.getCurrentAudioDirectory
import cool.hin.memox.utils.getCurrentFilesDirectory
import cool.hin.memox.utils.getCurrentImagesDirectory
import cool.hin.memox.utils.getTempAudioFile
import cool.hin.memox.utils.log
import cool.hin.memox.utils.scheduleReminder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias BackupFile = Pair<String?, File>

/**
 * Business-state equality used when deciding whether a save changed the note. It keeps the old
 * "no substantive change" behaviour (body, title, type, spans and items) and extends it to every
 * other user-visible field. Identity, version, modified time and the encryption envelope are
 * deliberately excluded: they change on each commit or are decided by the commit itself.
 */
fun BaseNote.hasSameBusinessState(other: BaseNote?): Boolean =
    other != null &&
        equalContents(other) &&
        folder == other.folder &&
        color == other.color &&
        pinned == other.pinned &&
        timestamp == other.timestamp &&
        labels == other.labels &&
        images == other.images &&
        files == other.files &&
        audios == other.audios &&
        reminders == other.reminders &&
        isPinnedToStatus == other.isPinnedToStatus &&
        locked == other.locked &&
        folderId == other.folderId &&
        contentFormat == other.contentFormat &&
        protection == other.protection

/** Modified time to commit: a no-op save keeps the stored time, a real edit takes the new one. */
fun commitTimestamp(current: BaseNote, original: BaseNote?): Long =
    if (original != null && current.hasSameBusinessState(original)) {
        original.modifiedTimestamp
    } else {
        current.modifiedTimestamp
    }

class MemoXModel(private val app: Application) : AndroidViewModel(app) {

    private val database = MemoXDatabase.getDatabase(app)
    private lateinit var baseNoteDao: BaseNoteDao

    val preferences = MemoXPreferences.getInstance(app)
    val textSize: TextSizeSp = preferences.textSizeNoteEditor.value

    var isNewNote = true

    var type = Type.NOTE

    var id = 0L
    var folder = Folder.NOTES
    var color = preferences.defaultNoteColor.value

    var title = String()
    var pinned = false
    var isPinnedToStatus = false
    var locked = false
    var protection = NoteProtection.NONE
    var contentFormat =
        if (preferences.markdownByDefault.value) ContentFormat.MARKDOWN else ContentFormat.RICH_TEXT
    var payload: ProtectedPayload? = null
    /** Password used to wrap the content key for other devices; never persisted. */
    var recoveryPassword: CharArray? = null
    /** True when the stored note is encrypted but could not be opened on this device yet. */
    var contentUnavailable = false
    var timestamp = System.currentTimeMillis()
    var modifiedTimestamp = System.currentTimeMillis()

    val labels = ArrayList<String>()

    var folderId = ""

    var body: Editable = SpannableStringBuilder()

    val items = ArrayList<ListItem>()

    val images = NotNullLiveData<List<FileAttachment>>(emptyList())
    val files = NotNullLiveData<List<FileAttachment>>(emptyList())
    val audios = NotNullLiveData<List<Audio>>(emptyList())

    val reminders = NotNullLiveData<List<Reminder>>(emptyList())

    val addingFiles = MutableLiveData<Progress>()
    val eventBus = MutableLiveData<Event<List<FileError>>>()

    var imageRoot = app.getCurrentImagesDirectory()
    var audioRoot = app.getCurrentAudioDirectory()
    var filesRoot = app.getCurrentFilesDirectory()

    var originalNote: BaseNote? = null

    init {
        database.observeForever { baseNoteDao = it.getBaseNoteDao() }
    }

    fun addAudio() {
        viewModelScope.launch {
            val audio = app.importAudio(app.getTempAudioFile(), true)
            val copy = ArrayList(audios.value)
            copy.add(audio)
            audios.value = copy
            updateAudios()
        }
    }

    fun deleteAudio(audio: Audio) {
        viewModelScope.launch {
            val copy = ArrayList(audios.value)
            copy.remove(audio)
            audios.value = copy
            updateAudios()
            withContext(Dispatchers.IO) { app.deleteAttachments(arrayListOf(audio)) }
        }
    }

    fun addImages(uris: Array<Uri>) {
        /*
        Regenerate because the directory may have been deleted between the time of activity creation
        and image addition
         */
        imageRoot = app.getCurrentImagesDirectory()
        requireNotNull(imageRoot) { "imageRoot is null" }
        addFiles(uris, imageRoot!!, FileType.IMAGE)
    }

    fun addFiles(uris: Array<Uri>) {
        /*
        Regenerate because the directory may have been deleted between the time of activity creation
        and image addition
         */
        filesRoot = app.getCurrentFilesDirectory()
        requireNotNull(filesRoot) { "filesRoot is null" }
        addFiles(uris, filesRoot!!, FileType.ANY)
    }

    private fun addFiles(uris: Array<Uri>, directory: File, fileType: FileType) {
        val errorWhileRenaming =
            if (fileType == FileType.IMAGE) {
                R.string.error_while_renaming_image
            } else {
                R.string.error_while_renaming_file
            }
        viewModelScope.launch {
            addingFiles.postValue(AddFilesProgress(0, uris.size))

            val successes = ArrayList<FileAttachment>()
            val errors = ArrayList<FileError>()

            uris.forEachIndexed { index, uri ->
                val (fileAttachment, error) =
                    app.importFile(
                        uri,
                        directory,
                        fileType,
                        errorWhileRenaming,
                        allowCompression = fileType == FileType.IMAGE,
                    )
                fileAttachment?.let { successes.add(it) }
                error?.let { errors.add(it) }
                addingFiles.postValue(AddFilesProgress(index + 1, uris.size))
            }

            addingFiles.postValue(AddFilesProgress(inProgress = false))

            if (successes.isNotEmpty()) {
                val copy =
                    when (fileType) {
                        FileType.IMAGE -> ArrayList(images.value)
                        FileType.ANY -> ArrayList(files.value)
                    }
                copy.addAll(successes)
                when (fileType) {
                    FileType.IMAGE -> {
                        images.value = copy
                        updateImages()
                        SyncRouter.syncNow(app)
                    }

                    FileType.ANY -> {
                        files.value = copy
                        updateFiles()
                        SyncRouter.syncNow(app)
                    }
                }
            }

            if (errors.isNotEmpty()) {
                eventBus.value = Event(errors)
            }
        }
    }

    fun deleteImages(list: ArrayList<FileAttachment>) {
        viewModelScope.launch {
            val copy = ArrayList(images.value)
            copy.removeAll(list)
            images.value = copy
            updateImages()
            withContext(Dispatchers.IO) { app.deleteAttachments(list) }
            SyncRouter.syncNow(app)
        }
    }

    fun deleteFiles(list: ArrayList<FileAttachment>) {
        viewModelScope.launch {
            val copy = ArrayList(files.value)
            copy.removeAll(list)
            files.value = copy
            updateFiles()
            withContext(Dispatchers.IO) { app.deleteAttachments(list) }
            SyncRouter.syncNow(app)
        }
    }

    /**
     * Imports the given image [uris] without touching the `images` list. The imported
     * [FileAttachment]s are handed to [onInserted] (on the main thread) so the caller can insert
     * them inline into the note body. Used for NOTE-type notes where images live in the body.
     */
    fun importImages(uris: Array<Uri>, onInserted: (List<FileAttachment>) -> Unit) {
        imageRoot = app.getCurrentImagesDirectory()
        requireNotNull(imageRoot) { "imageRoot is null" }
        val directory = imageRoot!!
        viewModelScope.launch {
            addingFiles.postValue(AddFilesProgress(0, uris.size))
            val successes = ArrayList<FileAttachment>()
            val errors = ArrayList<FileError>()
            uris.forEachIndexed { index, uri ->
                val (fileAttachment, error) =
                    app.importFile(
                        uri,
                        directory,
                        FileType.IMAGE,
                        R.string.error_while_renaming_image,
                        allowCompression = true,
                    )
                fileAttachment?.let { successes.add(it) }
                error?.let { errors.add(it) }
                addingFiles.postValue(AddFilesProgress(index + 1, uris.size))
            }
            addingFiles.postValue(AddFilesProgress(inProgress = false))
            if (successes.isNotEmpty()) {
                onInserted(successes)
            }
            if (errors.isNotEmpty()) {
                eventBus.value = Event(errors)
            }
        }
    }

    /**
     * Re-derives the `images` list from the [body]'s [InlineImageSpan]s (in text order). The N-th
     * [IMAGE_PLACEHOLDER] maps to the N-th image: if a span is present its [FileAttachment] is
     * used, otherwise the existing list entry at that index is reused as a fallback (this keeps
     * images intact even when spans are temporarily absent, e.g. after undo/redo on very large
     * notes whose state was compressed). No image files are deleted here ? orphaned files are
     * cleaned up later by [cool.hin.memox.utils.CleanupMissingAttachmentsWorker].
     */
    fun syncImagesFromBody() {
        if (type != Type.NOTE) {
            return
        }
        val current = images.value
        val text = body
        val result = ArrayList<FileAttachment>()
        var placeholderIdx = 0
        for (i in 0 until text.length) {
            if (text[i] == IMAGE_PLACEHOLDER) {
                val span = text.getSpans(i, i + 1, InlineImageSpan::class.java).firstOrNull()
                val attachment = span?.attachment ?: current.getOrNull(placeholderIdx)
                if (attachment != null) {
                    result.add(attachment)
                }
                placeholderIdx++
            }
        }
        if (result != current) {
            images.value = result
            viewModelScope.launch { updateImages() }
            SyncRouter.syncNow(app)
        }
    }

    /**
     * Ensures the body contains one [IMAGE_PLACEHOLDER] per image (appending placeholders at the
     * end for legacy notes whose images used to live in the top gallery) and attaches an
     * [InlineImageSpan] to each placeholder. Must run after the body and `images` have been loaded
     * in [setState] and before the body is bound to the editor.
     */
    private suspend fun reconcileAndAttachInlineImages() {
        if (type != Type.NOTE) {
            return
        }
        val imgs = images.value
        if (imgs.isEmpty()) {
            return
        }
        val textStr = body.toString()
        val placeholderCount = textStr.count { it == IMAGE_PLACEHOLDER }
        if (placeholderCount < imgs.size) {
            // Legacy migration: images existed only in the gallery list, not inline in the body.
            // Append a placeholder for each missing image so it renders inline at the end.
            val toAppend = imgs.size - placeholderCount
            val builder = SpannableStringBuilder(body)
            repeat(toAppend) { builder.append(IMAGE_PLACEHOLDER) }
            body = builder
        }
        val finalText = body.toString()
        val positions = ArrayList<Int>()
        for (i in finalText.indices) {
            if (finalText[i] == IMAGE_PLACEHOLDER) {
                positions.add(i)
            }
        }
        val spansToAttach =
            withContext(Dispatchers.IO) {
                positions.mapIndexedNotNull { index, pos ->
                    if (index >= imgs.size) {
                        null
                    } else {
                        loadInlineImageSpan(imgs[index])?.let { pos to it }
                    }
                }
            }
        for ((pos, span) in spansToAttach) {
            try {
                body.setSpan(span, pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (_: Exception) {
                // Ignore spans that fail to attach (e.g. index out of bounds after edits).
            }
        }
    }

    /**
     * Loads the bitmap for [attachment] (scaled to fit the editor width) and wraps it in an
     * [InlineImageSpan]. Must be called off the main thread.
     */
    fun loadInlineImageSpan(attachment: FileAttachment): InlineImageSpan? {
        val root = imageRoot ?: return null
        val file = File(root, attachment.localName)
        if (!file.exists()) {
            return null
        }
        val targetWidthPx = maxInlineImageWidthPx()
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val originalWidth = bounds.outWidth.coerceAtLeast(1)
            val originalHeight = bounds.outHeight.coerceAtLeast(1)
            val targetHeightPx =
                (targetWidthPx.toLong() * originalHeight / originalWidth).toInt().coerceAtLeast(1)
            val bitmap =
                Glide.with(app).asBitmap().load(file).submit(targetWidthPx, targetHeightPx).get()
            val drawable = BitmapDrawable(app.resources, bitmap)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            InlineImageSpan(drawable, attachment)
        } catch (_: Exception) {
            null
        }
    }

    private fun maxInlineImageWidthPx(): Int {
        val displayWidth = app.resources.displayMetrics.widthPixels
        val density = app.resources.displayMetrics.density
        return (displayWidth - 32 * density).toInt().coerceIn(200, displayWidth)
    }

    fun setLabels(list: List<String>) {
        labels.clear()
        labels.addAll(list)
    }

    suspend fun setState(id: Long, createInDb: Boolean = true) {
        if (id != 0L) {
            isNewNote = false

            val cachedNote = Cache.list.find { baseNote -> baseNote.id == id }
            val storedNote = cachedNote ?: withContext(Dispatchers.IO) { baseNoteDao.get(id) }
            var baseNote = storedNote
            contentUnavailable = false
            if (storedNote != null && storedNote.protection == NoteProtection.ENCRYPTED) {
                baseNote =
                    try {
                        val opened = withContext(Dispatchers.IO) { NoteCrypto.open(storedNote) }
                        withContext(Dispatchers.IO) {
                            NoteCrypto.materializeAttachments(app, opened.note)
                        }
                        opened.note
                    } catch (e: Exception) {
                        contentUnavailable = true
                        app.log("MemoXModel", msg = "Encrypted note could not be opened", throwable = e)
                        storedNote
                    }
            }

            if (baseNote != null) {
                originalNote = baseNote.deepCopy()

                this.id = id
                folder = baseNote.folder
                color = baseNote.color

                title = baseNote.title
                pinned = baseNote.pinned
                timestamp = baseNote.timestamp
                modifiedTimestamp = baseNote.modifiedTimestamp

                setLabels(baseNote.labels)
                folderId = baseNote.folderId
                protection = baseNote.protection
                contentFormat = baseNote.contentFormat
                payload = baseNote.payload

                // Markdown 笔记的正文是源码，旧版本预览产生的富文本 span 不能带入编辑器，
                // 否则当前行会残留粗体/字号等样式，看起来像源码没有完整恢复。
                body =
                    baseNote.body.applySpans(
                        if (baseNote.contentFormat == ContentFormat.MARKDOWN) {
                            emptyList()
                        } else {
                            baseNote.spans
                        },
                    )

                items.clear()
                items.addAll(baseNote.items)

                images.value = baseNote.images
                files.value = baseNote.files
                audios.value = baseNote.audios
                reminders.value = baseNote.reminders
                isPinnedToStatus = baseNote.isPinnedToStatus
                locked = baseNote.locked
                try {
                    reconcileAndAttachInlineImages()
                } catch (e: Exception) {
                    // Image attachment must never prevent the note from loading.
                    app.log("MemoXModel", msg = "Failed to attach inline images", throwable = e)
                }
            } else {
                originalNote = createBaseNote(createInDb)
                app.showToast(R.string.cant_find_note)
            }

        } else originalNote = createBaseNote(createInDb)
    }

    /** Adopt an encrypted note from another device using its recovery password. */
    suspend fun unlockWithRecovery(password: CharArray): Boolean {
        val stored = originalNote ?: return false
        if (stored.uuid.isBlank()) return false
        return try {
            val adopted = withContext(Dispatchers.IO) { NoteCrypto.adoptRecoveredNote(stored, password) }
            withContext(Dispatchers.IO) { baseNoteDao.insertSafe(app, adopted) }
            setState(id)
            !contentUnavailable
        } catch (e: Exception) {
            app.log("MemoXModel", msg = "Recovery password rejected", throwable = e)
            false
        }
    }

    private suspend fun createBaseNote(createInDb: Boolean = true): BaseNote {
        val baseNote = getBaseNote()
        if (!createInDb) return baseNote
        val insertedNote =
            withContext(Dispatchers.IO) {
                id = baseNoteDao.insertSafe(app, baseNote)
                // The UUID is assigned inside insert; reload so the editor seals against the
                // persisted identity instead of an empty one.
                baseNoteDao.get(id) ?: baseNote.copy(id = id)
            }
        return insertedNote
    }

    suspend fun deleteBaseNote() {
        app.cancelPinAndReminders(id, reminders.value)
        val note = getBaseNote()
        if (note.protection == NoteProtection.ENCRYPTED) {
            // Protected content is never physically removed from remote storage without a local,
            // authenticated confirmation; record the intent and let the sync engine propagate it.
            withContext(Dispatchers.IO) {
                MemoXDatabase.getDatabase(app)
                    .value
                    .getTombstoneDao()
                    .upsert(
                        cool.hin.memox.data.model.NoteTombstone(
                            uuid = note.uuid,
                            deletedAt = System.currentTimeMillis(),
                            version = note.version,
                            protectedContent = true,
                        )
                    )
            }
        } else {
            try {
                SyncRouter.deleteRemoteNote(app, note)
            } catch (_: Exception) {}
        }
        withContext(Dispatchers.IO) { baseNoteDao.delete(id) }
        WidgetProvider.sendBroadcast(app, longArrayOf(id))
        val attachments = ArrayList(images.value + files.value + audios.value)
        if (attachments.isNotEmpty()) {
            withContext(Dispatchers.IO) { app.deleteAttachments(attachments) }
        }
    }

    fun setItems(items: List<ListItem>) {
        this.items.clear()
        this.items.addAll(items)
    }

    suspend fun saveNote(): Long {
        // Never overwrite a sealed payload we could not open: that would destroy the content.
        if (contentUnavailable) return id
        return withContext(Dispatchers.IO) {
            val plain = getBaseNote()
            val original = originalNote
            // Decide the modification time from plaintext: sealing destroys the body/items and
            // binds modifiedTimestamp into metadataMac, so it must not happen before this step.
            val committedTimestamp = commitTimestamp(plain, original)
            // Advance the version exactly once, in the layer that produces the persisted note:
            // seal() bumps it internally; the plaintext paths bump it below. Keeping the pre-seal
            // version equal to the stored one also keeps NoteCrypto.open's MAC/AAD check valid.
            val baseVersion = original?.version ?: 0
            val plaintext = plain.copy(modifiedTimestamp = committedTimestamp, version = baseVersion)
            val changed = !plaintext.hasSameBusinessState(original)

            val note =
                when {
                    protection == NoteProtection.ENCRYPTED ->
                        NoteCrypto.seal(app, plaintext, recoveryPassword)
                    plain.payload != null -> {
                        val opened = NoteCrypto.open(plaintext)
                        // makePlain restores the stored (decrypted) body; the editor's current
                        // plaintext is authoritative, so keep it instead of the old content.
                        NoteCrypto.makePlain(app, opened.note)
                            .copy(
                                version = baseVersion + 1,
                                body = plain.body,
                                spans = plain.spans,
                                items = plain.items,
                            )
                    }
                    else -> plaintext.copy(version = baseVersion + 1)
                }
            val id = baseNoteDao.insertSafe(app, note)
            if (changed) {
                app.sendBroadcast(
                    Intent(app, ReminderReceiver::class.java).apply {
                        action = ReminderReceiver.ACTION_UPDATE_NOTIFICATIONS
                        putExtra(ReminderReceiver.EXTRA_NOTE_ID, id)
                    }
                )
            }
            payload = note.payload
            this@MemoXModel.id = if (this@MemoXModel.id == 0L) id else this@MemoXModel.id
            // Keep a plaintext mirror for change detection while the editor is open. It must
            // carry the committed version, timestamp and envelope — only body/spans/items are
            // restored — so an export taken before any async refresh cannot mix a stale version
            // with the new payload's MAC.
            originalNote =
                note.deepCopy().copy(body = plain.body, spans = plain.spans, items = plain.items)
            return@withContext id
        }
    }

    fun isEmpty(): Boolean {
        return title.isEmpty() &&
            body.isEmpty() &&
            items.none { item -> item.body.isNotEmpty() } &&
            files.value.isEmpty() &&
            images.value.isEmpty() &&
            audios.value.isEmpty()
    }

    fun isModified(): Boolean {
        return getBaseNote() != originalNote
    }

    private suspend fun updateImages() {
        withContext(Dispatchers.IO) { baseNoteDao.updateImages(id, images.value) }
    }

    private suspend fun updateFiles() {
        withContext(Dispatchers.IO) { baseNoteDao.updateFiles(id, files.value) }
    }

    private suspend fun updateAudios() {
        withContext(Dispatchers.IO) { baseNoteDao.updateAudios(id, audios.value) }
    }

    fun getBaseNote(): BaseNote {
        val spans = getFilteredSpans(body)
        val body = this.body.toString()
        val nonEmptyItems = this.items.filter { item -> item.body.isNotEmpty() }
        return BaseNote(
            id,
            type,
            folder,
            color,
            title,
            pinned,
            timestamp,
            modifiedTimestamp,
            labels,
            body,
            spans,
            nonEmptyItems,
            images.value,
            files.value,
            audios.value,
            reminders.value,
            isPinnedToStatus,
            locked,
            folderId,
            uuid = originalNote?.uuid.orEmpty(),
            version = originalNote?.version ?: 0,
            contentFormat = contentFormat,
            protection = protection,
            payload = payload,
        )
    }

    private fun getFilteredSpans(spanned: Spanned): ArrayList<SpanRepresentation> {
        val representations = LinkedHashSet<SpanRepresentation>()
        spanned.getSpans<CharacterStyle>().forEach { span ->
            // 行内 Markdown 预览 span 只用于编辑器显示，不能写进笔记的富文本格式。
            if (span is cool.hin.memox.presentation.markdown.MarkdownLiveSpan) return@forEach
            val end = spanned.getSpanEnd(span)
            val start = spanned.getSpanStart(span)
            val representation =
                SpanRepresentation(start, end, false, false, null, false, false, false, false, false)

            when (span) {
                is StyleSpan -> {
                    representation.bold = span.style == Typeface.BOLD
                    representation.italic = span.style == Typeface.ITALIC
                }

                is URLSpan -> {
                    representation.link = true
                    representation.linkData = span.url
                }
                is TypefaceSpan -> representation.monospace = span.family == "monospace"
                is StrikethroughSpan -> representation.strikethrough = true
                is cool.hin.memox.presentation.view.note.CheckboxSpan -> {
                    representation.checkbox = true
                    representation.checkboxChecked = span.isChecked
                }
            }

            if (representation.isNotUseless()) {
                representations.add(representation)
            }
        }
        return getFilteredRepresentations(ArrayList(representations))
    }

    private fun getFilteredRepresentations(
        representations: ArrayList<SpanRepresentation>
    ): ArrayList<SpanRepresentation> {
        representations.forEachIndexed { index, representation ->
            val match =
                representations.find { spanRepresentation ->
                    spanRepresentation.isEqualInSize(representation)
                }
            if (match != null && representations.indexOf(match) != index) {
                if (match.bold) {
                    representation.bold = true
                }
                if (match.link) {
                    representation.link = true
                    representation.linkData = match.linkData
                }
                if (match.italic) {
                    representation.italic = true
                }
                if (match.monospace) {
                    representation.monospace = true
                }
                if (match.strikethrough) {
                    representation.strikethrough = true
                }
                val copy = ArrayList(representations)
                copy[index] = representation
                copy.remove(match)
                return getFilteredRepresentations(copy)
            }
        }
        return representations
    }

    suspend fun removeReminder(reminder: Reminder) {
        app.cancelReminder(this.id, reminder.id)
        val updatedReminders = reminders.value.filter { it.id != reminder.id }
        updateReminders(updatedReminders)
    }

    suspend fun addReminder(reminder: Reminder) {
        val updatedReminders = ArrayList(reminders.value)
        val newReminder = reminder.copy(id = (updatedReminders.maxOfOrNull { it.id } ?: -1) + 1)
        updatedReminders.add(newReminder)
        updateReminders(updatedReminders)
        app.scheduleReminder(id, newReminder)
    }

    suspend fun updateReminder(updatedReminder: Reminder) {
        if (updatedReminder.id != NEW_REMINDER_ID) {
            app.cancelReminder(id, updatedReminder.id)
        }
        val updatedReminders = reminders.value.copy().toMutableList()
        val idx = updatedReminders.indexOfFirst { it.id == updatedReminder.id }
        if (idx != -1) {
            updatedReminders[idx] = updatedReminder
            updateReminders(updatedReminders)
            app.scheduleReminder(id, updatedReminder)
        }
    }

    private suspend fun updateReminders(updatedReminders: List<Reminder>) {
        reminders.value = updatedReminders
        withContext(Dispatchers.IO) { baseNoteDao.updateReminders(id, updatedReminders) }
    }

    suspend fun refreshOriginalNote() {
        if (id == 0L) return
        val baseNote = withContext(Dispatchers.IO) { baseNoteDao.get(id) }
        if (baseNote == null) return
        originalNote = baseNote.deepCopy()
        reminders.value = baseNote.reminders
    }

    enum class FileType {
        IMAGE,
        ANY,
    }
}
