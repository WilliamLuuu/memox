package cool.hin.memox.presentation.markdown

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.core.text.HtmlCompat
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * CommonMark + GFM renderer shared by the editor preview and the note list.
 *
 * It renders to [Spanned] in one pass, never executes raw HTML and never fetches a remote image:
 * image syntax is shown as its alt text. Rendering is bounded by the caller (see
 * [MarkdownRenderCache]) so a long note never parses on the main thread per keystroke.
 */
object MarkdownRenderer {

    private const val CODE_BACKGROUND = 0x1F888888

    private val extensions =
        listOf(TablesExtension.create(), StrikethroughExtension.create())

    private val parser: Parser =
        Parser.builder().extensions(extensions).build()

    fun render(source: String): CharSequence {
        return try {
            val document = parser.parse(source)
            val builder = SpannableStringBuilder()
            renderChildren(document, builder)
            builder.trimEnd()
        } catch (e: Exception) {
            android.util.Log.w("MarkdownRenderer", "Failed to render markdown", e)
            source
        }
    }

    fun renderInto(textView: TextView, source: String) {
        textView.text = render(source)
    }

    private fun renderChildren(parent: Node, out: SpannableStringBuilder) {
        var child = parent.firstChild
        while (child != null) {
            renderBlock(child, out)
            child = child.next
        }
    }

    private fun renderBlock(node: Node, out: SpannableStringBuilder) {
        when (node) {
            is Document -> renderChildren(node, out)
            is Heading -> {
                val start = out.length
                renderInlineChildren(node, out)
                out.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                val size =
                    when (node.level) {
                        1 -> 1.6f
                        2 -> 1.4f
                        3 -> 1.2f
                        else -> 1.1f
                    }
                out.setSpan(
                    RelativeSizeSpan(size),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                out.append("\n\n")
            }
            is Paragraph -> {
                renderInlineChildren(node, out)
                out.append("\n\n")
            }
            is BulletList -> renderList(node, out, ordered = false)
            is OrderedList -> renderList(node, out, ordered = true)
            is BlockQuote -> {
                val start = out.length
                renderChildren(node, out)
                val quote = out.substring(start, out.length)
                out.replace(start, out.length, quote.trimEnd().prependIndent("│ "))
                out.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                out.append("\n")
            }
            is FencedCodeBlock -> {
                val code = node.literal ?: ""
                appendCode(out, code.trimEnd())
                out.append("\n\n")
            }
            is IndentedCodeBlock -> {
                appendCode(out, (node.literal ?: "").trimEnd())
                out.append("\n\n")
            }
            is ThematicBreak -> {
                out.append("────────────\n\n")
            }
            is TableBlock -> {
                renderTable(node, out)
                out.append("\n")
            }
            is HtmlBlock -> {
                // Raw HTML is displayed as inert text; scripts are never interpreted.
                out.append(
                    HtmlCompat.fromHtml(node.literal ?: "", HtmlCompat.FROM_HTML_MODE_LEGACY)
                )
                out.append("\n\n")
            }
            else -> renderChildren(node, out)
        }
    }

    private fun renderInlineChildren(parent: Node, out: SpannableStringBuilder) {
        var child = parent.firstChild
        while (child != null) {
            renderInline(child, out)
            child = child.next
        }
    }

    private fun renderInline(node: Node, out: SpannableStringBuilder) {
        when (node) {
            is Text -> out.append(node.literal)
            is SoftLineBreak -> out.append(' ')
            is HardLineBreak -> out.append('\n')
            is Emphasis -> {
                val start = out.length
                renderInlineChildren(node, out)
                out.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            is StrongEmphasis -> {
                val start = out.length
                renderInlineChildren(node, out)
                out.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            is Strikethrough -> {
                val start = out.length
                renderInlineChildren(node, out)
                out.setSpan(
                    StrikethroughSpan(),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            is Code -> {
                val start = out.length
                out.append(node.literal ?: "")
                out.setSpan(
                    TypefaceSpan("monospace"),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            is Link -> {
                val start = out.length
                renderInlineChildren(node, out)
                out.setSpan(
                    URLSpan(node.destination),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            is Image -> {
                val alt = plainText(node).ifBlank { node.destination }
                out.append("[图片: $alt]")
            }
            is HtmlInline -> out.append(plainText(node))
            else -> renderInlineChildren(node, out)
        }
    }

    private fun renderList(node: Node, out: SpannableStringBuilder, ordered: Boolean) {
        var index = 1
        var item = node.firstChild
        while (item != null) {
            if (item is ListItem) {
                val start = out.length
                out.append(if (ordered) "$index. " else "• ")
                val markerEnd = out.length
                out.setSpan(
                    BulletSpan(16),
                    start,
                    markerEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                val contentStart = out.length
                var child = item.firstChild
                while (child != null) {
                    when (child) {
                        is Paragraph -> renderInlineChildren(child, out)
                        else -> renderBlock(child, out)
                    }
                    child = child.next
                }
                if (out.isEmpty() || out.last() != '\n') out.append('\n')
                // Indent continuation lines of the item.
                val end = out.length
                for (i in contentStart until end) {
                    if (out[i] == '\n' && i + 1 < end && out[i + 1] != '\n') {
                        out.insert(i + 1, "    ")
                    }
                }
                index++
            }
            item = item.next
        }
        out.append('\n')
    }

    private fun renderTable(table: Node, out: SpannableStringBuilder) {
        val rows = mutableListOf<List<String>>()
        var row = table.firstChild
        while (row != null) {
            if (row is TableRow) {
                val cells = mutableListOf<String>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) {
                        cells.add(plainText(cell).trim())
                    }
                    cell = cell.next
                }
                rows.add(cells)
            }
            row = row.next
        }
        if (rows.isEmpty()) return
        val columns = rows.maxOf { it.size }
        for ((rowIndex, cells) in rows.withIndex()) {
            val padded = cells + List(columns - cells.size) { "" }
            out.append(padded.joinToString(" │ "))
            out.append('\n')
            if (rowIndex == 0) {
                out.append(List(columns) { "───" }.joinToString("─┼─"))
                out.append('\n')
            }
        }
    }

    private fun appendCode(out: SpannableStringBuilder, code: String) {
        val start = out.length
        out.append(code)
        out.setSpan(
            TypefaceSpan("monospace"),
            start,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        out.setSpan(
            BackgroundColorSpan(CODE_BACKGROUND),
            start,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun plainText(node: Node): String {
        val builder = StringBuilder()
        fun walk(current: Node?) {
            var child = current
            while (child != null) {
                if (child is Text) builder.append(child.literal)
                if (child is Code) builder.append(child.literal)
                walk(child.firstChild)
                child = child.next
            }
        }
        walk(node.firstChild)
        return builder.toString()
    }
}
