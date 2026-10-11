package com.gameocr.app.textsource

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「精准取字（无障碍）」这条取字通路的接线约定：
 * 设置项要能被持久化与分类；取字要走无障碍服务、能在读不到字时回退到 OCR；循环翻译不短路。
 * 这些约定靠源码标记守住，改动通路时如果漏掉其中一环，测试会立刻失败。
 */
class A11yTextSourceWiringTest {

    @Test
    fun setting_isPersistedClassifiedAndSearchable() {
        val settings = source("src/main/java/com/gameocr/app/data/Settings.kt")
        val policy = source("src/main/java/com/gameocr/app/data/SettingsFieldPolicy.kt")
        val repository = source("src/main/java/com/gameocr/app/data/SettingsRepository.kt")
        val screen = source("src/main/java/com/gameocr/app/ui/SettingsScreen.kt")

        assertTrue(
            "settings field with a safe default",
            settings.contains("val a11yTextSourceEnabled: Boolean = false,"),
        )
        assertTrue(
            "settings field must stay classified by the policy table",
            policy.contains("portable(\"a11yTextSourceEnabled\", R.string.settings_search_item_a11y_text_source)"),
        )
        assertTrue("preference key", repository.contains("booleanPreferencesKey(\"a11y_text_source_enabled\")"))
        assertTrue("save path", repository.contains("prefs[Keys.A11yTextSource] = next.a11yTextSourceEnabled"))
        assertTrue(
            "load path",
            repository.contains("a11yTextSourceEnabled = this[Keys.A11yTextSource] ?: default.a11yTextSourceEnabled,"),
        )
        assertTrue(
            "switch renders inside the OCR section",
            screen.contains("R.string.settings_a11y_text_source_label"),
        )
        assertTrue(
            "search entry routes to the OCR section",
            screen.contains("R.string.settings_search_item_a11y_text_source"),
        )
        assertTrue(
            "search child target stays reachable",
            screen.substringAfter("private val SEARCH_TARGET_OCR_ENGINE")
                .substringBefore("private val SEARCH_TARGET_ORIENTATION_DETECTION")
                .contains("R.string.settings_search_item_a11y_text_source"),
        )
    }

    @Test
    fun reader_readsApplicationWindowTextOnlyAndSkipsNonContentNodes() {
        val reader = source("src/main/java/com/gameocr/app/textsource/A11yTextReader.kt")

        listOf(
            "AccessibilityWindowInfo.TYPE_APPLICATION",
            "service.rootInActiveWindow",
            "node.isPassword",
            "node.packageName?.toString() == ownPackage",
            "node.isVisibleToUser",
            "must run on the main thread",
        ).forEach { marker ->
            assertTrue("reader must keep: $marker", reader.contains(marker))
        }
        assertTrue(
            "parent takes over only when its own text covers every child fragment",
            reader.contains("childFragments.all { normalizedOwn.contains(it) }"),
        )
        assertFalse(
            "screen translation reads only visible text, never icon / menu a11y labels",
            reader.contains("node.contentDescription") || reader.contains("node.hintText"),
        )
    }

    @Test
    fun accessibilityService_exposesScreenTextReadOnTheMainThread() {
        val service = source("src/main/java/com/gameocr/app/trigger/GameOcrAccessibilityService.kt")
        val config = source("src/main/res/xml/a11y_config.xml")

        assertTrue(
            "static entry with an unavailable fallback",
            service.contains("fun readScreenText(screenWidth: Int, screenHeight: Int): A11yTextReadResult ="),
        )
        assertTrue(
            "reader call",
            service.contains("A11yTextReader.read(this, screenWidth, screenHeight)"),
        )
        assertTrue(
            "multiple windows must be retrievable",
            config.contains("flagRetrieveInteractiveWindows"),
        )
    }

    @Test
    fun capturePipeline_fullScreenForcesOcrAndWordSelectReadsA11yFirst() {
        val service = source("src/main/java/com/gameocr/app/service/CaptureService.kt")

        // 全屏 / 区域翻译一律走截图 OCR：captureOnce 不再有无障碍短路分支（X 等 App 的正文常在
        // contentDescription 里，全屏节点取字读不到，故强制 OCR）。
        assertFalse(
            "full-screen capture must not short-circuit to accessibility text",
            service.contains("if (!loopMode && settings.a11yTextSourceEnabled) {"),
        )
        assertTrue(
            "full-screen forces screenshot OCR",
            service.contains("全屏 / 区域翻译一律走截图 OCR"),
        )

        // 划词仍然先读无障碍文本、读不到再截屏 OCR（无障碍保留在划词 / 输入框场景）。
        val reader = service.indexOf("var a11yBlocks = readA11yTextBlocks(")
        val wordSelectScreenshot = service.indexOf("captureScreenshotWithTiming(shotter, diagId)", reader)
        assertTrue("word select reads accessibility text first", reader >= 0)
        assertTrue("word select screenshot stays behind the reader", wordSelectScreenshot > reader)
        assertTrue(
            "word select skips the image-backed visual context when there is no screenshot",
            service.contains("if (extractTextOnly || croppedBitmap == null) settings else prepareVisualTranslationSettings("),
        )
        assertTrue(
            "word select still falls back to OCR when accessibility text is empty",
            service.contains("logVerticalDiag(diagId, \"a11y text source empty; fallback to screenshot OCR\")"),
        )
    }

    @Test
    fun accessibilityTextNeverReplacesTheOcrEngineList() {
        val catalog = source("src/main/java/com/gameocr/app/data/OcrEngineCatalog.kt")
        assertFalse(
            "the accessibility reader is a text source, not an OCR engine",
            catalog.contains("A11Y_TEXT"),
        )
    }

    private fun source(path: String): String = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?.readText()
        ?: error("Source not found: $path")
}
