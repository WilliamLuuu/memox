package cool.hin.memox.presentation.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownListContinuationTest {
    @Test
    fun orderedListContinuesNumber() {
        assertEquals("2. ", nextMarkdownListPrefix("1. first"))
        assertEquals("12. ", nextMarkdownListPrefix("11. eleventh"))
        assertEquals("3) ", nextMarkdownListPrefix("2) second"))
    }

    @Test
    fun unorderedListContinuesMarker() {
        assertEquals("- ", nextMarkdownListPrefix("- item"))
        assertEquals("* ", nextMarkdownListPrefix("* item"))
        assertEquals("+ ", nextMarkdownListPrefix("+ item"))
    }

    @Test
    fun taskListKeepsCheckbox() {
        assertEquals("- [ ] ", nextMarkdownListPrefix("- [ ] todo"))
        assertEquals("- [x] ", nextMarkdownListPrefix("- [x] done"))
    }

    @Test
    fun indentedListKeepsIndent() {
        assertEquals("  2. ", nextMarkdownListPrefix("  1. nested"))
    }

    @Test
    fun emptyItemEndsList() {
        assertNull(nextMarkdownListPrefix("- "))
        assertNull(nextMarkdownListPrefix("1. "))
        assertNull(nextMarkdownListPrefix("not a list"))
    }
}
