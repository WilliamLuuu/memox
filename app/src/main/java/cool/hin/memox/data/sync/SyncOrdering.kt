package cool.hin.memox.data.sync

/**
 * Deterministic conflict ordering used by every provider.
 *
 * Logical version is the primary clock; the wall-clock timestamp only breaks ties between two
 * devices that somehow produced the same version. Clock skew can therefore never decide which
 * side wins when versions differ.
 */
object SyncOrdering {

    fun isLocalNewer(
        localVersion: Long,
        localModified: Long,
        remoteVersion: Long,
        remoteModified: Long,
    ): Boolean {
        if (localVersion != remoteVersion) return localVersion > remoteVersion
        return localModified > remoteModified
    }

    fun isRemoteNewer(
        localVersion: Long,
        localModified: Long,
        remoteVersion: Long,
        remoteModified: Long,
    ): Boolean {
        if (remoteVersion != localVersion) return remoteVersion > localVersion
        return remoteModified > localModified
    }
}
