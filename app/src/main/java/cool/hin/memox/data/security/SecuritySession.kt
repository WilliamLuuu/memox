package cool.hin.memox.data.security

/**
 * Process-wide authentication state. Set only by a successful biometric / device-credential
 * prompt (or at startup when the app lock is disabled). Destructive operations on protected
 * content must consult this flag; it is never persisted.
 */
object SecuritySession {
    @Volatile var appUnlocked: Boolean = false
        private set

    fun onAuthenticated() {
        appUnlocked = true
    }

    fun lock() {
        appUnlocked = false
        PendingContextHolder.context?.let { NoteCrypto.clearAllSessions(it) }
    }

    /** Set at startup when no app lock is configured. */
    fun markNoLockConfigured() {
        appUnlocked = true
    }
}

/**
 * Minimal bridge so [SecuritySession.lock] can clear decrypted attachment caches without every
 * caller holding an application context.
 */
object PendingContextHolder {
    @Volatile var context: android.content.ContextWrapper? = null
}
