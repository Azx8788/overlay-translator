package com.gameocr.app.textsource

import android.graphics.Rect
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextRegionGranularity

/**
 * 把无障碍节点条目归一成 [TextBlock]：
 *
 *  1. 丢掉同文本同位置（父子节点重复上报）的条目；
 *  2. 同一行内水平紧邻的碎片合成一段。Compose / Flutter / WebView 常把一句话拆成多个文本
 *     节点，拆开翻译会碎成几个词，这里按"行内按 left 排序后相邻且间隙很小"合并；因为只在
 *     排序后的相邻项之间合并，并排两列中间夹着别的条目时天然不会粘连；
 *  3. 输出顺序沿用节点树遍历顺序，也就是屏幕阅读顺序。
 *
 * 竖排（日文縦書き）不做列内合并：节点树给出的往往是整列文本，逐字符上报的情况在无障碍
 * 里极少见，强行按列合并的风险大于收益。
 */
internal object A11yTextGroupingPolicy {
    private const val MAX_MERGED_TEXT_LENGTH = 240
    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.6f
    private const val MAX_LINE_HEIGHT_RATIO = 1.6f

    fun toTextBlocks(
        entries: List<A11yTextEntry>,
        region: Rect?,
        maxHorizontalGapPx: Int,
    ): List<TextBlock> {
        if (entries.isEmpty()) return emptyList()
        val scoped = if (region == null) entries else entries.filter {
            Rect.intersects(it.bounds, region)
        }
        val unique = dedupe(scoped)
        if (unique.isEmpty()) return emptyList()
        val gapLimit = maxHorizontalGapPx.coerceAtLeast(0)
        return lineBuckets(unique)
            .flatMap { line -> mergeLine(line, gapLimit) }
            .sortedBy { it.order }
            .map { it.toTextBlock() }
    }

    private fun dedupe(entries: List<A11yTextEntry>): List<A11yTextEntry> {
        val seen = HashSet<String>()
        return entries.filter { entry -> seen.add(entry.identityKey()) }
    }

    /**
     * 按行分桶。[bands] 是当前行已收条目的并集，作为"同一行"的判据基准。
     */
    private fun lineBuckets(entries: List<A11yTextEntry>): List<List<A11yTextEntry>> {
        val bands = mutableListOf<Rect>()
        val lines = mutableListOf<MutableList<A11yTextEntry>>()
        entries.forEach { entry ->
            val index = bands.indexOfFirst { band -> sameLine(band, entry.bounds) }
            if (index >= 0) {
                lines[index] += entry
                bands[index].union(entry.bounds)
            } else {
                bands += Rect(entry.bounds)
                lines += mutableListOf(entry)
            }
        }
        return lines
    }

    private fun sameLine(first: Rect, second: Rect): Boolean {
        if (first.width() <= 0 || second.width() <= 0) return false
        val minHeight = minOf(first.height(), second.height())
        val maxHeight = maxOf(first.height(), second.height())
        if (minHeight <= 0) return false
        if (maxHeight > minHeight * MAX_LINE_HEIGHT_RATIO) return false
        val overlap = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        return overlap >= minHeight * MIN_VERTICAL_OVERLAP_RATIO
    }

    private fun mergeLine(line: List<A11yTextEntry>, gapLimit: Int): List<A11yTextEntry> {
        val ordered = line.sortedWith(compareBy({ it.bounds.left }, { it.order }))
        val merged = mutableListOf<A11yTextEntry>()
        var current: A11yTextEntry? = null
        ordered.forEach { entry ->
            val previous = current
            if (previous != null && canMerge(previous, entry, gapLimit)) {
                current = previous.mergeWith(entry)
            } else {
                previous?.let(merged::add)
                current = entry
            }
        }
        current?.let(merged::add)
        return merged
    }

    private fun canMerge(previous: A11yTextEntry, next: A11yTextEntry, gapLimit: Int): Boolean {
        if (previous.windowIndex != next.windowIndex) return false
        if (previous.text.length + next.text.length > MAX_MERGED_TEXT_LENGTH) return false
        val gap = next.bounds.left - previous.bounds.right
        return gap <= gapLimit
    }

    private fun A11yTextEntry.mergeWith(next: A11yTextEntry): A11yTextEntry {
        val union = Rect(bounds).apply { union(next.bounds) }
        return copy(
            text = joinText(text, next.text),
            bounds = union,
            order = minOf(order, next.order),
            mergedEntries = mergedEntries + next.mergedEntries,
        )
    }

    private fun A11yTextEntry.toTextBlock(): TextBlock = TextBlock(
        text = text,
        boundingBox = Rect(bounds),
        confidence = 1f,
        regionGranularity = if (mergedEntries > 1) {
            TextRegionGranularity.PARAGRAPH
        } else {
            TextRegionGranularity.LINE
        },
    )

    /**
     * 相邻节点拼接：两侧都是 CJK（含 CJK 标点）时直接相连，否则补一个空格，
     * 避免拉丁文被拼成 "WHENSHE"。
     */
    private fun joinText(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val head = left.last()
        val tail = right.first()
        if (head.isWhitespace() || tail.isWhitespace()) return left + right
        return if (isCjk(head) || isCjk(tail)) left + right else "$left $right"
    }

    private fun isCjk(char: Char): Boolean = when (Character.UnicodeBlock.of(char)) {
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
        -> true

        else -> false
    }

    private fun A11yTextEntry.identityKey(): String {
        val box = bounds
        return "$text|${box.left},${box.top},${box.right},${box.bottom}"
    }
}
