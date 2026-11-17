package cool.hin.memox.presentation.activity.note

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.style.URLSpan
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import cool.hin.memox.R
import cool.hin.memox.data.model.Type
import cool.hin.memox.data.model.ContentFormat
import cool.hin.memox.presentation.markdown.MarkdownLiveStyler
import cool.hin.memox.presentation.markdown.nextMarkdownListPrefix
import cool.hin.memox.presentation.markdown.MarkdownRenderer
import cool.hin.memox.data.model.getNoteIdFromUrl
import cool.hin.memox.data.model.getNoteTypeFromUrl
import cool.hin.memox.data.model.isNoteUrl
import cool.hin.memox.databinding.BottomTextFormattingMenuBinding
import cool.hin.memox.databinding.RecyclerToggleBinding
import cool.hin.memox.presentation.InlineImageSpan
import cool.hin.memox.presentation.addIconButton
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.hideKeyboard
import cool.hin.memox.presentation.setControlsContrastColorForAllViews
import cool.hin.memox.presentation.setOnNextAction
import cool.hin.memox.presentation.showKeyboard
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.note.TextFormattingAdapter
import cool.hin.memox.presentation.view.note.action.AddBottomSheet
import cool.hin.memox.presentation.viewmodel.preference.EditAction
import cool.hin.memox.presentation.view.note.CheckboxSpan
import cool.hin.memox.presentation.view.note.applyLinkCards
import cool.hin.memox.utils.LinkMovementMethod
import cool.hin.memox.utils.copyToClipBoard
import cool.hin.memox.utils.findAllOccurrences
import cool.hin.memox.utils.openNote
import cool.hin.memox.utils.wrapWithChooser

class EditNoteActivity : EditActivity(Type.NOTE) {

    private var isTextFormattingMenuVisible = false

    private var textFormattingAdapter: TextFormattingAdapter? = null

    private var searchResultIndices: List<Pair<Int, Int>>? = null

    override fun configureUI() {
        binding.EnterTitle.setOnNextAction { binding.EnterBody.requestFocus() }

        // NOTE-type notes render images inline in the body, so the top image gallery is hidden.
        binding.ImageLayout.visibility = GONE

        setupMarkdownMode()
        setupMarkdownLiveStyler()

        if (notallyModel.isNewNote) {
            binding.EnterBody.requestFocus()
        }

        // Editor is always editable; initialise formatting/selection callbacks once.
        setupEditor()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.apply {
            putInt(EXTRA_SELECTION_START, binding.EnterBody.selectionStart)
            putInt(EXTRA_SELECTION_END, binding.EnterBody.selectionEnd)
        }
    }

    override fun highlightSearchResults(search: String): Int {
        binding.EnterBody.clearHighlights()
        if (search.isEmpty()) {
            return 0
        }
        searchResultIndices =
            notallyModel.body.toString().findAllOccurrences(search).onEach { (startIdx, endIdx) ->
                binding.EnterBody.highlight(startIdx, endIdx, false)
            }
        return searchResultIndices!!.size
    }

    override fun selectSearchResult(resultPos: Int) {
        if (resultPos < 0) {
            binding.EnterBody.unselectHighlight()
            return
        }
        searchResultIndices?.get(resultPos)?.let { (startIdx, endIdx) ->
            val selectedLineTop = binding.EnterBody.highlight(startIdx, endIdx, true)
            selectedLineTop?.let { binding.ScrollView.scrollTo(0, it) }
        }
    }

    override fun setupListeners() {
        super.setupListeners()
        binding.EnterBody.initHistory(changeHistory) { text ->
            val textChanged = !notallyModel.body.toString().contentEquals(text)
            notallyModel.body = text
            notallyModel.syncImagesFromBody()
            if (textChanged) {
                updateSearchResults(search.query)
                updateJumpButtonsVisibility()
                scheduleMarkdownPreview()
            }
        }
    }

    private data class MdAction(
        val label: String?,
        val iconRes: Int,
        val descriptionRes: Int,
        val action: () -> Unit,
    )

    private var markdownPreviewVisible = false
    private var pendingNewline = false
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null

    private val markdownLiveStyler by lazy {
        MarkdownLiveStyler(
            binding.EnterBody,
            MaterialColors.getColor(binding.EnterBody, MaterialR.attr.colorPrimary),
            MaterialColors.getColor(binding.EnterBody, MaterialR.attr.colorOnSurfaceVariant),
            MaterialColors.getColor(binding.EnterBody, MaterialR.attr.colorSurfaceVariant),
        )
    }

    private fun continueMarkdownList(editable: Editable?) {
        if (editable == null) return
        val cursor = binding.EnterBody.selectionStart
        if (cursor != binding.EnterBody.selectionEnd) return
        if (cursor < 2 || editable[cursor - 1] != '\n') return

        val lineStart = editable.lastIndexOf('\n', cursor - 2) + 1
        val previousLine = editable.subSequence(lineStart, cursor - 1).toString()
        val prefix = nextMarkdownListPrefix(previousLine) ?: return

        editable.insert(cursor, prefix)
        binding.EnterBody.setSelection(cursor + prefix.length)
    }

    private fun setupMarkdownLiveStyler() {
        // 行内预览只改变 Editable 上的 span，不改原始文本；每次输入/移动光标后短暂防抖重排。
        markdownLiveStyler
        binding.EnterBody.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    // 只处理“刚插入一个换行”的情况，避免移动光标/删除时误续写列表。
                    pendingNewline = count == 1 && before == 0 && s?.getOrNull(start) == '\n'
                }

                override fun afterTextChanged(s: android.text.Editable?) {
                    if (pendingNewline) {
                        pendingNewline = false
                        if (markdownLiveStyler.isEnabled) continueMarkdownList(s)
                    }
                    markdownLiveStyler.schedule()
                }
            }
        )
        binding.EnterBody.addOnSelectionChangeListener { _, _ -> markdownLiveStyler.schedule() }
    }

    override fun updateTopActions(topActions: List<EditAction>, changeable: Boolean) {
        super.updateTopActions(topActions, changeable)
        // updateTopActions 会 clear 菜单，必须在它之后补上 Markdown 入口，
        // 否则“预览 / 使用 Markdown”永远不会出现在工具栏里。
        setupMarkdownMode()
    }

    private fun setupMarkdownMode() {
        val isMarkdown = notallyModel.contentFormat == ContentFormat.MARKDOWN
        binding.MarkdownToolbar.removeAllViews()
        binding.MarkdownToolbarScroll.isVisible = isMarkdown
        updateBottomBarSpacing()
        markdownLiveStyler.setEnabled(isMarkdown && !markdownPreviewVisible)
        binding.Toolbar.menu.removeItem(R.id.MenuToggleMarkdownPreview)
        binding.Toolbar.menu.removeItem(R.id.MenuToggleContentFormat)
        binding.Toolbar.inflateMenu(R.menu.menu_markdown)
        val previewItem = binding.Toolbar.menu.findItem(R.id.MenuToggleMarkdownPreview)
        previewItem.isVisible = isMarkdown
        previewItem.setOnMenuItemClickListener {
            toggleMarkdownPreview()
            true
        }
        val formatItem = binding.Toolbar.menu.findItem(R.id.MenuToggleContentFormat)
        // 格式切换对已有笔记同样开放：否则旧笔记只能以 RICH_TEXT 打开，正文里的
        // Markdown 源文（##、** 等）永远不会被渲染，看起来就是“不支持 Markdown”。
        formatItem.isVisible = true
        formatItem.setTitle(if (isMarkdown) R.string.markdown_use_rich else R.string.markdown_use)
        formatItem.setOnMenuItemClickListener {
            notallyModel.contentFormat =
                if (isMarkdown) ContentFormat.RICH_TEXT else ContentFormat.MARKDOWN
            markdownPreviewVisible = false
            binding.MarkdownPreview.isVisible = false
            binding.ScrollView.isVisible = true
            setupMarkdownMode()
            true
        }
        if (!isMarkdown) return
        val actions: List<MdAction> =
            listOf(
                MdAction("H1", 0, R.string.md_h1) { insertAtLineStart("# ") },
                MdAction("H2", 0, R.string.md_h2) { insertAtLineStart("## ") },
                MdAction("H3", 0, R.string.md_h3) { insertAtLineStart("### ") },
                MdAction(null, R.drawable.format_bold, R.string.md_bold) {
                    surround("**", "**", getString(R.string.md_text))
                },
                MdAction(null, R.drawable.format_italic, R.string.md_italic) {
                    surround("*", "*", getString(R.string.md_text))
                },
                MdAction(null, R.drawable.format_strikethrough, R.string.md_strike) {
                    surround("~~", "~~", getString(R.string.md_text))
                },
                MdAction(null, R.drawable.code, R.string.md_inline_code) {
                    surround("`", "`", "code")
                },
                MdAction(null, R.drawable.format_list_bulleted, R.string.md_list) {
                    insertAtLineStart("- ")
                },
                MdAction(null, R.drawable.format_list_numbered, R.string.md_numbered_list) {
                    insertAtLineStart("1. ")
                },
                MdAction(null, R.drawable.checkbox_outline, R.string.md_task) {
                    insertAtLineStart("- [ ] ")
                },
                MdAction(null, R.drawable.format_quote, R.string.md_quote) {
                    insertAtLineStart("> ")
                },
                MdAction(null, R.drawable.code_block, R.string.md_code_block) {
                    surround("```\n", "\n```", "")
                },
                MdAction(null, R.drawable.format_table, R.string.md_table) {
                    surround(
                        "| 列 1 | 列 2 |\n| --- | --- |\n| ",
                        " |  |\n",
                        getString(R.string.md_text),
                    )
                },
                MdAction(null, R.drawable.format_horizontal_rule, R.string.md_rule) {
                    surround("\n---\n", "", "")
                },
                MdAction(null, R.drawable.link, R.string.md_link) {
                    surround("[", "](https://)", getString(R.string.md_link_text))
                },
                MdAction(null, R.drawable.add_images, R.string.md_image) {
                    surround("![", "](image.png)", getString(R.string.md_text))
                },
            )
        val color =
            com.google.android.material.color.MaterialColors.getColor(
                binding.MarkdownToolbar,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        val size = 36.dp
        val selectable = android.util.TypedValue()
        theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            selectable,
            true,
        )
        actions.forEach { action ->
            val view: View =
                if (action.iconRes != 0) {
                    androidx.appcompat.widget.AppCompatImageButton(this).apply {
                        setImageResource(action.iconRes)
                        imageTintList = android.content.res.ColorStateList.valueOf(color)
                        setBackgroundResource(selectable.resourceId)
                        val padding = 6.dp
                        setPadding(padding, padding, padding, padding)
                    }
                } else {
                    TextView(this).apply {
                        text = action.label
                        gravity = android.view.Gravity.CENTER
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(color)
                        setBackgroundResource(selectable.resourceId)
                    }
                }
            view.layoutParams = LinearLayout.LayoutParams(size, size)
            view.contentDescription = getString(action.descriptionRes)
            view.setOnClickListener { action.action() }
            binding.MarkdownToolbar.addView(view)
        }
    }

    private fun toggleMarkdownPreview() {
        markdownPreviewVisible = !markdownPreviewVisible
        val wide = resources.configuration.screenWidthDp >= 600
        binding.MarkdownPreview.isVisible = markdownPreviewVisible
        // Wide windows keep source and preview side by side; phones swap them.
        binding.ScrollView.isVisible = !markdownPreviewVisible || wide
        binding.MarkdownToolbarScroll.isVisible = !markdownPreviewVisible
        updateBottomBarSpacing()
        markdownLiveStyler.setEnabled(
            notallyModel.contentFormat == ContentFormat.MARKDOWN && !markdownPreviewVisible
        )
        binding.Toolbar.menu
            .findItem(R.id.MenuToggleMarkdownPreview)
            .setTitle(
                if (markdownPreviewVisible) R.string.markdown_edit_source
                else R.string.markdown_preview
            )
        if (markdownPreviewVisible) {
            if (!wide) hideKeyboard(binding.EnterBody)
            renderMarkdownPreview()
        }
    }

    private fun scheduleMarkdownPreview() {
        if (!markdownPreviewVisible) return
        if (!notallyModel.preferences.markdownLivePreview.value) return
        previewRunnable?.let { previewHandler.removeCallbacks(it) }
        val runnable =
            Runnable {
                if (markdownPreviewVisible) renderMarkdownPreview()
            }
        previewRunnable = runnable
        previewHandler.postDelayed(runnable, 180)
    }

    private fun renderMarkdownPreview() {
        MarkdownRenderer.renderInto(binding.MarkdownPreview, notallyModel.body.toString())
    }

    private fun surround(before: String, after: String, placeholder: String) {
        val body = binding.EnterBody
        val editable = body.text ?: return
        val start = body.selectionStart.coerceAtLeast(0)
        val end = body.selectionEnd.coerceAtLeast(start)
        val selected = editable.substring(start, end)
        val content = selected.ifEmpty { placeholder }
        editable.replace(start, end, before + content + after)
        body.setSelection(start + before.length + content.length)
        notallyModel.body = editable
    }

    private fun insertAtLineStart(prefix: String) {
        val body = binding.EnterBody
        val editable = body.text ?: return
        val cursor = body.selectionStart.coerceAtLeast(0)
        val lineStart = editable.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let {
            if (it < 0) 0 else it + 1
        }
        editable.insert(lineStart, prefix)
        body.setSelection(cursor + prefix.length)
        notallyModel.body = editable
    }

    override fun onDestroy() {
        previewRunnable?.let { previewHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    override fun setStateFromModel(savedInstanceState: Bundle?) {
        super.setStateFromModel(savedInstanceState)
        updateEditText()
        savedInstanceState?.let {
            val selectionStart = it.getInt(EXTRA_SELECTION_START, -1)
            val selectionEnd = it.getInt(EXTRA_SELECTION_END, -1)
            if (selectionStart > -1) {
                binding.EnterBody.postOnAnimation {
                    binding.EnterBody.focusAndSelect(selectionStart, selectionEnd)
                }
            }
        }
    }

    private fun updateEditText() {
        binding.EnterBody.text = notallyModel.body
        (binding.EnterBody.text as? android.text.Spannable)?.applyLinkCards(binding.EnterBody)
    }

    private fun setupEditor() {
        setupMovementMethod()
        binding.EnterBody.customSelectionActionModeCallback =
            if (canEdit) {
                FormattingActionModeCallback(this, binding.EnterBody)
            } else null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.EnterBody.customInsertionActionModeCallback =
                if (canEdit) {
                    FormattingActionModeCallback(this, binding.EnterBody)
                } else null
        }
        if (canEdit) {
            binding.EnterBody.setOnSelectionChange { selStart, selEnd ->
                if (selEnd - selStart > 0) {
                    if (!isTextFormattingMenuVisible) {
                        initBottomTextFormattingMenu()
                    }
                    textFormattingAdapter?.updateTextFormattingToggles(selStart, selEnd)
                } else {
                    if (isTextFormattingMenuVisible) {
                        initBottomMenu()
                    }
                }
            }
        } else {
            binding.EnterBody.setOnSelectionChange { _, _ -> }
        }
        binding.ContentLayout.setOnClickListener {
            binding.EnterBody.apply {
                requestFocus()
                if (canEdit) {
                    setSelection(length())
                    showKeyboard(this)
                }
            }
        }
    }

    override fun initBottomMenu() {
        isTextFormattingMenuVisible = false
        binding.BottomAppBarCenter.visibility = GONE
        binding.BottomAppBarLeft.apply {
            removeAllViews()
            updateLayoutParams<ConstraintLayout.LayoutParams> { endToStart = -1 }
            // Single "+" entry point (Keep style): images / recording / attachment / checkbox
            addIconButton(R.string.add, R.drawable.add, colorInt, marginStart = 0) {
                AddBottomSheet(
                        actionHandler,
                        colorInt,
                        onInsertCheckbox = { insertCheckboxAtCursor() },
                    )
                    .show(supportFragmentManager, AddBottomSheet.TAG)
            }
        }
        // Right side: 撤销/重做（Keep 风格，无内容时隐藏）、更多（锁定笔记已移入三点菜单）
        binding.BottomAppBarRight.apply {
            removeAllViews()
            // 底部栏只保留 撤销/重做 + 三点菜单；导出/置顶等一律收在三点菜单内，不渲染快捷动作按钮。
            undo =
                addIconButton(
                    R.string.undo, R.drawable.undo, colorInt, marginStart = 2,
                    onLongClick = {
                        try { changeHistory.undoAll() } catch (e: Exception) { android.util.Log.e("EditNoteActivity", "change history action failed", e) }
                        true
                    },
                ) {
                    try { changeHistory.undo() } catch (e: Exception) { android.util.Log.e("EditNoteActivity", "change history action failed", e) }
                }.apply { isVisible = changeHistory.canUndo.value }
            redo =
                addIconButton(
                    R.string.redo, R.drawable.redo, colorInt, marginStart = 2,
                    onLongClick = {
                        try { changeHistory.redoAll() } catch (e: Exception) { android.util.Log.e("EditNoteActivity", "change history action failed", e) }
                        true
                    },
                ) {
                    try { changeHistory.redo() } catch (e: Exception) { android.util.Log.e("EditNoteActivity", "change history action failed", e) }
                }.apply { isVisible = changeHistory.canRedo.value }
            addIconButton(
                R.string.tap_for_more_options,
                R.drawable.more_vert,
                colorInt,
                marginStart = 0,
            ) {
                openMoreOptionsBottomSheet()
            }
        }
        setBottomAppBarColor(colorInt)
    }

    private fun insertCheckboxAtCursor() {
        val body = binding.EnterBody
        val text = body.text ?: return
        val selStart = body.selectionStart.coerceAtLeast(0)
        val lineStart = text.substring(0, selStart).lastIndexOf('\n') + 1
        CheckboxSpan.insertCheckbox(text, lineStart)
        body.setSelection(lineStart + 2)
    }

    private fun initBottomTextFormattingMenu() {
        isTextFormattingMenuVisible = true
        binding.BottomAppBarCenter.visibility = GONE
        val extractColor = colorInt
        binding.BottomAppBarRight.apply {
            removeAllViews()
            addView(
                RecyclerToggleBinding.inflate(layoutInflater, this, false).root.apply {
                    setIconResource(R.drawable.close)
                    contentDescription = context.getString(R.string.cancel)
                    setOnClickListener { initBottomMenu() }

                    updateLayoutParams<LinearLayout.LayoutParams> {
                        marginEnd = 0
                        marginStart = 10.dp
                    }
                    setControlsContrastColorForAllViews(extractColor)
                    setBackgroundColor(0)
                }
            )
            // 锁定笔记已移入三点菜单，此处不再展示锁图标
            addIconButton(
                R.string.tap_for_more_options,
                R.drawable.more_vert,
                colorInt,
                marginStart = 0,
            ) {
                openMoreOptionsBottomSheet()
            }
        }
        binding.BottomAppBarLeft.apply {
            removeAllViews()
            updateLayoutParams<ConstraintLayout.LayoutParams> {
                endToStart = R.id.BottomAppBarRight
            }
            requestLayout()
            val layout = BottomTextFormattingMenuBinding.inflate(layoutInflater, this, false)
            layout.MainListView.apply {
                textFormattingAdapter =
                    TextFormattingAdapter(this@EditNoteActivity, binding.EnterBody, colorInt)
                adapter = textFormattingAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            addView(layout.root)
        }
    }

    private fun setupMovementMethod() {
        val movementMethod =
            LinkMovementMethod(
                onClick = { span ->
                    val items =
                        if (span.url.isNoteUrl()) {
                            if (canEdit) {
                                arrayOf(
                                    getString(R.string.open_note),
                                    getString(R.string.remove_link),
                                    getString(R.string.change_note),
                                    getString(R.string.edit),
                                )
                            } else arrayOf(getString(R.string.open_note))
                        } else {
                            if (canEdit) {
                                arrayOf(
                                    getString(R.string.open_link),
                                    getString(R.string.copy),
                                    getString(R.string.remove_link),
                                    getString(R.string.edit),
                                )
                            } else arrayOf(getString(R.string.open_link), getString(R.string.copy))
                        }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(
                            if (span.url.isNoteUrl())
                                "${getString(R.string.note)}: ${
                            binding.EnterBody.getSpanText(span)
                        }"
                            else span.url
                        )
                        .setItems(items) { _, which ->
                            when (which) {
                                0 -> openLink(span)
                                1 ->
                                    if (span.url.isNoteUrl()) {
                                        removeLink(span)
                                    } else copyLink(span)
                                2 ->
                                    if (span.url.isNoteUrl()) {
                                        actionHandler.updateNoteLink(span)
                                    } else removeLink(span)
                                3 -> editLink(span)
                            }
                        }
                        .show()
                },
                onImageClick = { span -> openInlineImage(span) },
                onCheckboxClick = { span -> span.onClick(binding.EnterBody) },
            )
        binding.EnterBody.movementMethod = movementMethod
    }

    private fun openInlineImage(span: InlineImageSpan) {
        val position =
            notallyModel.images.value
                .indexOfFirst { it.localName == span.attachment.localName }
                .coerceAtLeast(0)
        val intent =
            Intent(this, ViewImageActivity::class.java).apply {
                putExtra(ViewImageActivity.EXTRA_POSITION, position)
                putExtra(EXTRA_SELECTED_BASE_NOTE, notallyModel.id)
            }
        actionHandler.viewImagesActivityResultLauncher.launch(intent)
    }

    private fun openLink(span: URLSpan) {
        span.url?.let {
            if (it.isNoteUrl()) {
                span.navigateToNote()
            } else {
                openLink(span.url)
            }
        }
    }

    private fun editLink(span: URLSpan) {
        binding.EnterBody.showEditDialog(span)
    }

    private fun copyLink(span: URLSpan) {
        copyToClipBoard(span.url)
        showToast(R.string.copied_link)
    }

    private fun removeLink(span: URLSpan) {
        binding.EnterBody.removeSpanWithHistory(
            span,
            span.url.isNoteUrl() || span.url == binding.EnterBody.getSpanText(span),
        )
    }

    private fun openLink(url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).wrapWithChooser(this)
        try {
            startActivity(intent)
        } catch (exception: Exception) {
            showToast(R.string.cant_open_link)
        }
    }

    private fun URLSpan.navigateToNote() {
        openNote(this.url.getNoteIdFromUrl(), this.url.getNoteTypeFromUrl())
    }

    companion object {
        private const val EXTRA_SELECTION_START = "memox.intent.extra.EXTRA_SELECTION_START"
        private const val EXTRA_SELECTION_END = "memox.intent.extra.EXTRA_SELECTION_END"
    }
}
