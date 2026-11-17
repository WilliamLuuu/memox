package cool.hin.memox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local deletion record. It survives the physical removal of a note so that a later sync cannot
 * resurrect the note from a remote snapshot, and so that a deletion is propagated exactly once.
 *
 * [pendingRemote] stays true until the active provider confirmed the remote deletion. Protected
 * notes are never purged from remote storage without a local confirmation step (see sync engine).
 */
@Entity
data class NoteTombstone(
    @PrimaryKey val uuid: String,
    val deletedAt: Long,
    /** Version of the note when it was deleted; used for last-writer-wins comparison. */
    val version: Long,
    /** True while the remote side still needs this deletion applied. */
    val pendingRemote: Boolean = true,
    /**
     * True when the deleted note was content-encrypted. Remote removal of protected content
     * requires an authenticated app session; otherwise the tombstone stays pending.
     */
    val protectedContent: Boolean = false,
)
