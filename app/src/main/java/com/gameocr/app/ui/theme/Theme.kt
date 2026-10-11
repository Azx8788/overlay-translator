package com.gameocr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 极简中性灰 —— 纯黑白灰、无色相。强调色就是黑/白本身，刻意"简陋"：没有品牌色、没有冷暖倾向。
val Ink = Color(0xFF171717)
val Neutral0 = Color(0xFFFFFFFF)
val Neutral50 = Color(0xFFFAFAFA)
val Neutral100 = Color(0xFFF5F5F5)
val Neutral150 = Color(0xFFEEEEEE)
val Neutral200 = Color(0xFFE5E5E5)
val Neutral300 = Color(0xFFCFCFCF)
val Neutral400 = Color(0xFF9E9E9E)
val Neutral500 = Color(0xFF737373)
val Neutral600 = Color(0xFF525252)
val Neutral700 = Color(0xFF3F3F3F)
val Neutral800 = Color(0xFF262626)
val Neutral850 = Color(0xFF1E1E1E)
val Neutral900 = Color(0xFF121212)
val Neutral950 = Color(0xFF0A0A0A)
val Pitch = Color(0xFF000000)

/**
 * 极简浅色：背景纯白，强调色近黑。surfaceTint 透明 —— 抬升不染色，整体扁平无阴影感。
 */
private val MinimalLight = lightColorScheme(
    primary = Ink,
    onPrimary = Neutral0,
    primaryContainer = Neutral200,
    onPrimaryContainer = Ink,

    secondary = Neutral700,
    onSecondary = Neutral0,
    secondaryContainer = Neutral150,
    onSecondaryContainer = Neutral800,

    tertiary = Neutral600,
    onTertiary = Neutral0,
    tertiaryContainer = Neutral100,
    onTertiaryContainer = Neutral800,

    background = Neutral0,
    onBackground = Ink,
    surface = Neutral0,
    onSurface = Ink,
    surfaceVariant = Neutral100,
    onSurfaceVariant = Neutral500,

    surfaceTint = Color.Transparent,
    surfaceBright = Neutral0,
    surfaceDim = Neutral200,
    surfaceContainerLowest = Neutral0,
    surfaceContainerLow = Neutral50,
    surfaceContainer = Neutral100,
    surfaceContainerHigh = Neutral150,
    surfaceContainerHighest = Neutral200,

    inverseSurface = Neutral800,
    inverseOnSurface = Neutral100,
    inversePrimary = Neutral300,

    outline = Neutral300,
    outlineVariant = Neutral200,
    scrim = Color(0x66000000),

    error = Color(0xFFB3261E),
    onError = Neutral0,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * 极简深色：背景纯黑，强调色近白。同样透明 surfaceTint，层次只靠极窄的灰阶拉开。
 */
private val MinimalDark = darkColorScheme(
    primary = Neutral150,
    onPrimary = Neutral900,
    primaryContainer = Neutral800,
    onPrimaryContainer = Neutral100,

    secondary = Neutral300,
    onSecondary = Neutral900,
    secondaryContainer = Neutral850,
    onSecondaryContainer = Neutral200,

    tertiary = Neutral400,
    onTertiary = Neutral900,
    tertiaryContainer = Neutral850,
    onTertiaryContainer = Neutral100,

    background = Pitch,
    onBackground = Neutral150,
    surface = Neutral950,
    onSurface = Neutral150,
    surfaceVariant = Neutral850,
    onSurfaceVariant = Neutral400,

    surfaceTint = Color.Transparent,
    surfaceBright = Neutral800,
    surfaceDim = Pitch,
    surfaceContainerLowest = Pitch,
    surfaceContainerLow = Neutral950,
    surfaceContainer = Neutral900,
    surfaceContainerHigh = Neutral850,
    surfaceContainerHighest = Neutral800,

    inverseSurface = Neutral150,
    inverseOnSurface = Neutral800,
    inversePrimary = Neutral700,

    outline = Neutral700,
    outlineVariant = Neutral850,
    scrim = Color(0x99000000),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

// 简陋 = 小圆角、近乎方正。所有 Material3 组件（卡片 / 按钮 / 对话框 / 底部表单）统一跟随。
private val MinimalShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/** 主题模式：值与 [com.gameocr.app.data.ThemeModePrefs] 中的常量对应。 */
object ThemeMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

@Composable
fun GameOcrTheme(
    themeMode: Int = ThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) MinimalDark else MinimalLight,
        shapes = MinimalShapes,
        content = content
    )
}
