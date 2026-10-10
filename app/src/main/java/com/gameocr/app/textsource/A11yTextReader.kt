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
 *  2. 递归到叶子：只有子节点都没产出文本时，才取当前节点自己的文本。这样"父节点
 *     contentDescription 汇总子节点文本"不会重复翻译；
 *  3. 跳过密码框、可编辑输入框、不可见 / 零面积节点，以及按钮、复选框这类纯控件类名
 *     （见 [isExcludedA11yClass]），避免把界面按钮文字当成正文送去翻译。
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

        /** @return true 表示该子树已经产出过文本。 */
        private fun visit(
            node: AccessibilityNodeInfo,
            depth: Int,
            windowIndex: Int,
        ): Boolean {
            if (budgetExhausted() || depth > MAX_DEPTH) {
                truncated = true
                return false
            }
            visitedNodes++
            var childYielded = false
            val childCount = node.childCount
            for (index in 0 until childCount) {
                if (budgetExhausted()) {
                    truncated = true
                    break
                }
                val child = node.getChild(index) ?: continue
                try {
                    if (visit(child, depth + 1, windowIndex)) childYielded = true
                } finally {
                    recycleA11yNode(child)
                }
            }
            if (childYielded) return true
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val text = extractText(node, bounds) ?: return false
            entries += A11yTextEntry(
                text = text,
                bounds = bounds,
                order = entries.size,
                windowIndex = windowIndex,
            )
            return true
        }

        private fun extractText(node: AccessibilityNodeInfo, bounds: Rect): String? {
            if (node.isPassword || node.isEditable) return null
            if (!node.isVisibleToUser) return null
            if (node.packageName?.toString() == ownPackage) return null
            if (bounds.width() <= 0 || bounds.height() <= 0) return null
            if (!bounds.intersect(0, 0, screenWidth, screenHeight)) return null
            if (isExcludedA11yClass(node.className?.toString().orEmpty())) return null
            return node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
                ?: node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
                ?: node.hintText?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }

        private fun budgetExhausted(): Boolean =
            visitedNodes >= MAX_NODES || SystemClock.elapsedRealtime() - startedAt > TIME_BUDGET_MS
    }
}

/**
 * 按类名简单名后缀过滤纯控件。它们自己带的文字（"发送"、"确定"）属于界面操作，不是用户想
 * 翻译的正文；真正承载正文的 TextView / WebView / Compose 文本节点不受影响。
 */
private val EXCLUDED_A11Y_CLASS_SUFFIXES = listOf(
    "Button",
    "ImageButton",
    "ImageView",
    "CheckBox",
    "RadioButton",
    "Switch",
    "ToggleButton",
    "SeekBar",
    "ProgressBar",
    "RatingBar",
    "Spinner",
    "Chip",
    "TabLayout",
)

private fun isExcludedA11yClass(className: String): Boolean {
    if (className.isEmpty()) return false
    val simpleName = className.substringAfterLast('.')
    return EXCLUDED_A11Y_CLASS_SUFFIXES.any { suffix -> simpleName.endsWith(suffix) }
}

@Suppress("DEPRECATION")
private fun recycleA11yNode(node: AccessibilityNodeInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
}

@Suppress("DEPRECATION")
private fun recycleA11yWindow(window: AccessibilityWindowInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) window.recycle()
}
