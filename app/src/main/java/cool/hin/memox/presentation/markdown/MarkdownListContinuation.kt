package cool.hin.memox.presentation.markdown

private val LIST_ITEM = Regex("""^(\s*)(\d+[.)]|[-*+])\s+(\[[ xX]]\s+)?(.*)$""")

/**
 * 返回在 Markdown 列表项后按回车时应插入的前缀。
 *
 * 例如 `1. item` -> `2. `，`- item` -> `- `，`- [ ] task` -> `- [ ] `。
 * 空列表项返回 null，让用户直接退出列表。
 */
internal fun nextMarkdownListPrefix(line: String): String? {
    val match = LIST_ITEM.matchEntire(line) ?: return null
    if (match.groupValues[4].isBlank()) return null

    val indent = match.groupValues[1]
    val marker = match.groupValues[2]
    val task = match.groupValues[3]
    return when {
        marker.first().isDigit() -> {
            val number = marker.dropLast(1).toIntOrNull() ?: return null
            "$indent${number + 1}${marker.last()} "
        }

        task.isNotEmpty() -> "$indent${marker.first()} $task"
        else -> "$indent$marker "
    }
}
