package cool.hin.memox.presentation.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.widget.EditText

/**
 * “一行输入完就预览”的行内 Markdown 预览。
 *
 * 编辑器里始终保存原始 Markdown 文本；本类只在 [EditText] 的 [Editable] 上挂展示用 span：
 * 已经完成的段落按 Markdown 渲染，正在编辑的段落保持源码，点进任意段落后该段重新变回源码，
 * 因此不会出现“预览模式下不能编辑”的问题。所有 span 都实现 [MarkdownLiveSpan]，保存时由
 * `MemoXModel.getFilteredSpans` 过滤，不会写进笔记的富文本格式。
 */
class MarkdownLiveStyler(
    private val editText: EditText,
    private val primaryColor: Int,
    private val onSurfaceVariantColor: Int,
    private val surfaceVariantColor: Int,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val restyleRunnable = Runnable { apply() }
    private var enabled = false

    val isEnabled: Boolean
        get() = enabled

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (enabled) {
            schedule()
        } else {
            handler.removeCallbacks(restyleRunnable)
            clearSpans()
        }
    }

    fun schedule() {
        if (!enabled) return
        handler.removeCallbacks(restyleRunnable)
        handler.postDelayed(restyleRunnable, 32)
    }

    fun clearSpans() {
        val text = editText.text ?: return
        text.getSpans(0, text.length, MarkdownLiveSpan::class.java).forEach(text::removeSpan)
    }

    private fun apply() {
        if (!enabled) return
        val text = editText.text ?: return
        clearSpans()
        if (text.isEmpty()) return

        val selectionStart = editText.selectionStart.coerceAtLeast(0)
        val selectionEnd = editText.selectionEnd.coerceAtLeast(selectionStart)

        var lineStart = 0
        var inFence = false
        var orderedListNumber: Int? = null
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) text.length else newline
            val isCurrentLine = lineStart <= selectionEnd && lineEnd >= selectionStart
            val line = text.subSequence(lineStart, lineEnd).toString()

            val fence = FENCE.matchEntire(line)
            val ordered = if (!inFence) ORDERED.matchEntire(line) else null
            when {
                fence != null -> {
                    orderedListNumber = null
                    if (!isCurrentLine) hide(text, lineStart, lineEnd)
                    inFence = !inFence
                }

                inFence -> {
                    orderedListNumber = null
                    if (!isCurrentLine) {
                        text.setSpan(
                            MdInline(line, mono = true),
                            lineStart,
                            lineEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        text.setSpan(
                            MdCodeBackground(surfaceVariantColor),
                            lineStart,
                            lineEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }

                ordered != null -> {
                    val sourceNumber = ordered.groups[2]!!.value.toIntOrNull() ?: 1
                    val displayNumber = orderedListNumber?.plus(1) ?: sourceNumber
                    orderedListNumber = displayNumber
                    if (!isCurrentLine) {
                        applyOrdered(text, lineStart, ordered, displayNumber)
                    }
                }

                else -> {
                    orderedListNumber = null
                    if (!isCurrentLine) applyBlock(text, lineStart, lineEnd, line)
                }
            }

            if (newline == -1) break
            lineStart = newline + 1
        }
    }

    /**
     * 有序列表在预览中按源码顺序连续编号：源码可以写成 `1.` / `1.` / `1.`，
     * 预览显示为 1. / 2. / 3.，和常见 Markdown 编辑器一致。
     */
    private fun applyOrdered(
        text: Editable,
        start: Int,
        match: MatchResult,
        number: Int,
    ) {
        val contentStart = start + match.groups[4]!!.range.first
        val contentEnd = start + match.groups[4]!!.range.last + 1
        val replacement =
            buildString {
                append(match.groups[1]!!.value)
                append(number)
                append(match.groups[3]!!.value)
            }
        text.setSpan(
            MdReplacement(replacement),
            start,
            contentStart,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        applyInline(text, contentStart, contentEnd)
    }

    private fun applyBlock(text: Editable, start: Int, end: Int, line: String) {
        if (line.isBlank()) return

        HEADING.matchEntire(line)?.let { match ->
            val markerEnd = start + match.groups[1]!!.range.last + 1
            hide(text, start, markerEnd)
            val contentStart = start + match.groups[2]!!.range.first
            val contentEnd = start + match.groups[2]!!.range.last + 1
            val level = match.groups[1]!!.value.trim().length
            style(text, contentStart, contentEnd, MdBold())
            text.setSpan(
                MdLineHeight(
                    when (level) {
                        1 -> 1.6f
                        2 -> 1.45f
                        else -> 1.3f
                    },
                ),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.setSpan(
                MdRelativeSize(
                    when (level) {
                        1 -> 1.45f
                        2 -> 1.3f
                        3 -> 1.18f
                        else -> 1.05f
                    }
                ),
                contentStart,
                contentEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            applyInline(text, contentStart, contentEnd)
            return
        }

        TASK.matchEntire(line)?.let { match ->
            val prefixEnd = start + match.groups[1]!!.range.last + 1
            hide(text, start, prefixEnd)
            val checkStart = start + match.groups[2]!!.range.first
            val checkEnd = start + match.groups[2]!!.range.last + 1
            val checked = match.groups[3]!!.value.equals("x", ignoreCase = true)
            text.setSpan(
                MdReplacement(if (checked) "☑ " else "☐ "),
                checkStart,
                checkEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val contentStart = start + match.groups[4]!!.range.first
            val contentEnd = start + match.groups[4]!!.range.last + 1
            applyInline(text, contentStart, contentEnd)
            return
        }

        BULLET.matchEntire(line)?.let { match ->
            val prefixEnd = start + match.groups[1]!!.range.last + 1
            text.setSpan(
                MdReplacement("• "),
                start,
                prefixEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val contentStart = start + match.groups[2]!!.range.first
            val contentEnd = start + match.groups[2]!!.range.last + 1
            applyInline(text, contentStart, contentEnd)
            return
        }

        QUOTE.matchEntire(line)?.let { match ->
            val prefixEnd = start + match.groups[1]!!.range.last + 1
            hide(text, start, prefixEnd)
            text.setSpan(
                MdQuote(),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val contentStart = start + match.groups[2]!!.range.first
            val contentEnd = start + match.groups[2]!!.range.last + 1
            applyInline(text, contentStart, contentEnd)
            return
        }

        if (HORIZONTAL_RULE.matches(line)) {
            text.setSpan(
                MdReplacement("──────────"),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return
        }

        applyInline(text, start, end)
    }

    private fun applyInline(text: Editable, start: Int, end: Int) {
        if (end <= start) return
        val occupied = BooleanArray(end - start)

        fun mark(
            range: IntRange,
            markerSpan: CharacterStyle,
            contentRange: IntRange? = null,
            contentSpan: CharacterStyle? = null,
        ) {
            if (range.first < start || range.last >= end) return
            val local = range.first - start..range.last - start
            if (local.any { occupied[it] }) return
            local.forEach { occupied[it] = true }
            if (contentRange != null && contentSpan != null) {
                if (contentRange.first > range.first) {
                    text.setSpan(
                        markerSpan,
                        range.first,
                        contentRange.first,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (contentRange.last < range.last) {
                    text.setSpan(
                        markerSpan,
                        contentRange.last + 1,
                        range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                text.setSpan(
                    contentSpan,
                    contentRange.first,
                    contentRange.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                text.setSpan(markerSpan, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        CODE.findAll(text.subSequence(start, end)).forEach { match ->
            val range = (start + match.range.first)..(start + match.range.last)
            val content = (start + match.groups[1]!!.range.first)..(start + match.groups[1]!!.range.last)
            mark(range, MdInline(text.subSequence(content.first, content.last + 1).toString(), mono = true))
        }
        IMAGE.findAll(text.subSequence(start, end)).forEach { match ->
            val range = (start + match.range.first)..(start + match.range.last)
            val alt = match.groups[1]!!.value.ifBlank { "🖼" }
            mark(range, MdReplacement(alt))
        }
        LINK.findAll(text.subSequence(start, end)).forEach { match ->
            val range = (start + match.range.first)..(start + match.range.last)
            val content = (start + match.groups[1]!!.range.first)..(start + match.groups[1]!!.range.last)
            mark(range, MdInline(text.subSequence(content.first, content.last + 1).toString(), color = primaryColor))
        }
        BOLD.findAll(text.subSequence(start, end)).forEach { match ->
            val range = (start + match.range.first)..(start + match.range.last)
            val content = (start + match.groups[1]!!.range.first)..(start + match.groups[1]!!.range.last)
            mark(range, MdInline(text.subSequence(content.first, content.last + 1).toString(), style = Typeface.BOLD))
        }
        ITALIC.findAll(text.subSequence(start, end)).forEach { match ->
            val range = (start + match.range.first)..(start + match.range.last)
            val content = (start + match.groups[1]!!.range.first)..(start + match.groups[1]!!.range.last)
            mark(range, MdInline(text.subSequence(content.first, content.last + 1).toString(), style = Typeface.ITALIC))
        }
        STRIKE.findAll(text.subSequence(start, end)).forEach { match ->
            val range = (start + match.range.first)..(start + match.range.last)
            val content = (start + match.groups[1]!!.range.first)..(start + match.groups[1]!!.range.last)
            mark(range, MdInline(text.subSequence(content.first, content.last + 1).toString(), strike = true))
        }
    }

    private fun hide(text: Editable, start: Int, end: Int) {
        if (end > start) {
            text.setSpan(MdMarker(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun style(text: Editable, start: Int, end: Int, span: CharacterStyle) {
        if (end > start) {
            text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        val FENCE = Regex("^\\s*```.*$")
        val HEADING = Regex("^( {0,3}#{1,6}\\s+)(.*)$")
        val TASK = Regex("^( {0,3}[-*+]\\s+)(\\[([ xX])]\\s+)(.*)$")
        val BULLET = Regex("^( {0,3}[-*+]\\s+)(.*)$")
        val ORDERED = Regex("^( {0,3})(\\d+)([.)]\\s+)(.*)$")
        val QUOTE = Regex("^( {0,3}(?:>\\s?)+)(.*)$")
        val HORIZONTAL_RULE = Regex("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
        val CODE = Regex("`([^`\\n]+)`")
        val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
        val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
        val BOLD = Regex("\\*\\*(?!\\s)(.+?)(?<!\\s)\\*\\*")
        val ITALIC = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
        val STRIKE = Regex("~~(?!\\s)(.+?)(?<!\\s)~~")
    }
}

/** 所有行内预览 span 的标记接口；保存时会被过滤，不进入笔记富文本格式。 */
interface MarkdownLiveSpan

private class MdMarker : ReplacementSpan(), MarkdownLiveSpan {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int = 0

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) = Unit
}

private class MdCodeBackground(color: Int) : ForegroundColorSpan(color), MarkdownLiveSpan

private class MdBold : StyleSpan(Typeface.BOLD), MarkdownLiveSpan

private class MdLineHeight(private val factor: Float) : LineHeightSpan, MarkdownLiveSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        fm.ascent = (fm.ascent * factor).toInt()
        fm.descent = (fm.descent * factor).toInt()
        fm.top = (fm.top * factor).toInt()
        fm.bottom = (fm.bottom * factor).toInt()
    }
}

private class MdRelativeSize(size: Float) : MetricAffectingSpan(), MarkdownLiveSpan {
    private val relativeSize = size

    override fun updateDrawState(tp: TextPaint) {
        tp.textSize = tp.textSize * relativeSize
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.textSize = tp.textSize * relativeSize
    }
}

private class MdInline(
    private val content: String,
    private val style: Int = Typeface.NORMAL,
    private val mono: Boolean = false,
    private val color: Int? = null,
    private val strike: Boolean = false,
) : ReplacementSpan(), MarkdownLiveSpan {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val old = paint.typeface
        paint.typeface = typeface(old)
        val metrics = paint.fontMetricsInt
        fm?.let {
            it.ascent = metrics.ascent
            it.descent = metrics.descent
            it.top = metrics.top
            it.bottom = metrics.bottom
            it.leading = metrics.leading
        }
        val width = paint.measureText(content).toInt()
        paint.typeface = old
        return width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val oldTypeface = paint.typeface
        val oldColor = paint.color
        paint.typeface = typeface(oldTypeface)
        color?.let { paint.color = it }
        canvas.drawText(content, x, y.toFloat(), paint)
        if (strike) {
            val offset = (paint.descent() - paint.ascent()) / 3f
            canvas.drawLine(x, y - offset, x + paint.measureText(content), y - offset, paint)
        }
        paint.typeface = oldTypeface
        paint.color = oldColor
    }

    private fun typeface(old: Typeface?): Typeface =
        if (mono) Typeface.create(Typeface.MONOSPACE, style) else Typeface.create(old, style)
}

private class MdQuote : android.text.style.QuoteSpan(), MarkdownLiveSpan

private class MdReplacement(private val replacement: String) : ReplacementSpan(), MarkdownLiveSpan {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int = paint.measureText(replacement).toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        canvas.drawText(replacement, x, y.toFloat(), paint)
    }
}
