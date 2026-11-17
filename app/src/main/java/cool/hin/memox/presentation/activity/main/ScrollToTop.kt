package cool.hin.memox.presentation.activity.main

/** Implemented by list screens that can jump back to the top on a toolbar double-tap. */
interface ScrollToTop {
    /** Returns true when a list was present and scrolled. */
    fun scrollToTop(): Boolean
}
