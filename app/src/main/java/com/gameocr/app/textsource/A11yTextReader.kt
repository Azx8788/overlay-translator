package com.gameocr.app.textsource

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** 无障碍节点树里的一条文本。[bounds] 是屏幕坐标系（px）。 */
internal data class A11yTextEntry(
    val text: String,
    val bounds: Rect,
    val order: Int,
    val windowIndex: Int,
    /** 这条文本由多少个节点合并而来；1 表示单个节点。 */
    val mergedEntries: Int = 1,
)

internal data class A11yTextSnapshot(
    val entries: List<A11yTextEntry>,
    val visitedNodes: Int,
    val windowCount: Int,
    val truncated: Boolean,
    val elapsedMs: Long,
) {
    fun toLogString(): String =
        "entries=${entries.size} nodes=$visitedNodes windows=$windowCount " +
            "truncated=$truncated elapsedMs=$elapsedMs"
}

internal sealed interface A11yTextReadResult {
    data class Ready(val snapshot: A11yTextSnapshot) : A11yTextReadResult

    /** 无障碍服务未连接（未授权或被系统回收）。 */
    data object Unavailable : A11yTextReadResult
}

/**
 * 遍历无障碍窗口树抽取屏上可见文本。
 *
 * 与截屏 OCR 相比，这里拿到的是控件真实文本：没有识别误差，也不需要模型、预处理和截屏权限。
 * 代价是只对"真的有文本节点"的界面有效——游戏 / 漫画 / 视觉小说的画布文字在节点树里是空的，
 * 这类界面只能继续走 OCR。
 *
 * 规则（产出顺序即节点树的阅读顺序）：
 *  1. 只接受应用窗口（[AccessibilityWindowInfo.TYPE_APPLICATION]），排除输入法、状态栏、
 *     通知栏、无障碍悬浮层；自家悬浮窗的节点按包名过滤掉；
 *  2. 优先取叶子文本：子节点已经产出文本时，父节点只有在"自己的文本逐一覆盖了全部子片段"时才
 *     接手（例如推文正文里套着 #话题 / 链接等内联 ClickableSpan 虚拟子节点，整段正文才是要翻
 *     的单位）；否则保留子节点条目，避免把父节点的汇总描述重复翻译一遍；
 *  3. 只翻译屏幕上可见的文字（node.text）；不读 contentDescription / hintText 这类无障碍标签，
 *     否则图标按钮、菜单项会凭空叠出大量译文框，在密集列表里把画面糊成一片。密码框、可编辑
 *     输入框、不可见 / 零面积节点与自家悬浮窗一律跳过。
 *
 * 必须在主线程调用；受 [MAX_NODES] / [TIME_BUDGET_MS] 预算保护，超预算即截断返回。
 */
internal object A11yTextReader {
    private const val MAX_NODES = 1500
    private const val MAX_DEPTH = 40
    private const val TIME_BUDGET_MS = 400L

    fun read(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
    ): A11yTextSnapshot {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Accessibility node tree access must run on the main thread"
        }
        val startedAt = SystemClock.elapsedRealtime()
        val traversal = Traversal(
            startedAt = startedAt,
            ownPackage = service.packageName,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        val windows = service.windows.orEmpty()
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        var windowCount = 0
        if (windows.isEmpty()) {
            // 没有可用窗口列表时退回当前活动窗口，至少不会因为缺 flag 而完全读不到字。
            val root = service.rootInActiveWindow
            if (root != null) {
                traversal.visitWindow(root, windowIndex = 0)
                windowCount = 1
                recycleA11yNode(root)
            }
        } else {
            windows.forEachIndexed { index, window ->
                val root = window.root ?: return@forEachIndexed
                windowCount++
                traversal.visitWindow(root, windowIndex = index)
                recycleA11yNode(root)
            }
            windows.forEach(::recycleA11yWindow)
        }
        return traversal.toSnapshot(windowCount = windowCount)
    }

    private class Traversal(
        private val startedAt: Long,
        private val ownPackage: String,
        private val screenWidth: Int,
        private val screenHeight: Int,
    ) {
        private val entries = mutableListOf<A11yTextEntry>()
        private var visitedNodes = 0
        private var truncated = false

        fun visitWindow(root: AccessibilityNodeInfo, windowIndex: Int) {
            visit(root, depth = 0, windowIndex = windowIndex)
        }

        fun toSnapshot(windowCount: Int): A11yTextSnapshot = A11yTextSnapshot(
            entries = entries.toList(),
            visitedNodes = visitedNodes,
            windowCount = windowCount,
            truncated = truncated,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        )

        /** @return 该子树产出的全部文本（去掉空白，用于父节点判断自己是否覆盖了子文本）；无文本返回 null。 */
        private fun visit(
            node: AccessibilityNodeInfo,
            depth: Int,
            windowIndex: Int,
        ): String? {
            if (budgetExhausted() || depth > MAX_DEPTH) {
                truncated = true
                return null
            }
            visitedNodes++
            val firstChildEntryIndex = entries.size
            val childFragments = ArrayList<String>()
            val childCount = node.childCount
            for (index in 0 until childCount) {
                if (budgetExhausted()) {
                    truncated = true
                    break
                }
                val child = node.getChild(index) ?: continue
                try {
                    visit(child, depth + 1, windowIndex)?.let(childFragments::add)
                } finally {
                    recycleA11yNode(child)
                }
            }
            val ownText = readOwnText(node)
            if (childFragments.isEmpty()) {
                val bounds = Rect()
                if (ownText == null || !acceptBounds(node, bounds)) return null
                entries += A11yTextEntry(
                    text = ownText,
                    bounds = bounds,
                    order = entries.size,
                    windowIndex = windowIndex,
                )
                return ownText.withoutWhitespace()
            }
            // 子树已产出文本。父节点若"自己的文本覆盖了全部子文本"，就接手整段、丢掉更碎的子条目。
            // 覆盖判定按"每个子片段分别被包含"，而不是"拼接后整体包含"：推文 / 聊天 / 新闻正文常把
            // 超链接、@提及、#话题渲染成内联 ClickableSpan，这些 span 会变成分散的虚拟子节点；它们
            // 拼接起来并不是正文的连续子串，用整体 contains 判定会漏掉整段正文、只剩零散的链接词。
            // 长度闸门（父文本 ≥ 子片段总长）确保父文本确实是更完整的那份，避免误吞并列的两段文字。
            if (ownText != null) {
                val normalizedOwn = ownText.withoutWhitespace()
                val combinedChildLength = childFragments.sumOf { it.length }
                val ownCoversEveryFragment = childFragments.all { normalizedOwn.contains(it) }
                if (normalizedOwn.length >= combinedChildLength && ownCoversEveryFragment) {
                    val bounds = Rect()
                    if (acceptBounds(node, bounds)) {
                        while (entries.size > firstChildEntryIndex) {
                            entries.removeAt(entries.lastIndex)
                        }
                        entries += A11yTextEntry(
                            text = ownText,
                            bounds = bounds,
                            order = entries.size,
                            windowIndex = windowIndex,
                        )
                        return normalizedOwn
                    }
                }
            }
            return childFragments.joinToString(separator = "")
        }

        /**
         * 只取节点在屏幕上真实可见的文字（[AccessibilityNodeInfo.getText]）。
         *
         * 刻意不读 contentDescription / hintText：它们绝大多数是图标按钮、菜单项、输入框占位符的
         * 无障碍标签（如"回复""转帖""喜欢""显示翻译设置""让 Grok 解释此帖子""发帖选项"），屏幕上
         * 并没有对应的可见文字。一旦连它们也翻译，就会凭空叠出大量译文框——在推文 / 聊天这类密集
         * 列表里尤其会糊成一片，把原文和译文叠得看不清。屏幕翻译只翻"看得见的字"才干净。
         *
         * 密码框、可编辑输入框、不可见节点、自家悬浮窗节点一律跳过。
         */
        private fun readOwnText(node: AccessibilityNodeInfo): String? {
            if (node.isPassword || node.isEditable) return null
            if (!node.isVisibleToUser) return null
            if (node.packageName?.toString() == ownPackage) return null
            return node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }

        /** 控件必须在屏上有正面积；命中屏幕外的部分裁掉，保证 overlay 坐标可用。 */
        private fun acceptBounds(node: AccessibilityNodeInfo, out: Rect): Boolean {
            node.getBoundsInScreen(out)
            if (out.width() <= 0 || out.height() <= 0) return false
            return out.intersect(0, 0, screenWidth, screenHeight)
        }

        private fun budgetExhausted(): Boolean =
            visitedNodes >= MAX_NODES || SystemClock.elapsedRealtime() - startedAt > TIME_BUDGET_MS
    }
}

private fun String.withoutWhitespace(): String = filterNot(Char::isWhitespace)

@Suppress("DEPRECATION")
private fun recycleA11yNode(node: AccessibilityNodeInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
}

@Suppress("DEPRECATION")
private fun recycleA11yWindow(window: AccessibilityWindowInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) window.recycle()
}
