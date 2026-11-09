package com.haegopa.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private fun schemeOf(p: HaegopaPalette) = if (p.isDark) {
    darkColorScheme(
        primary = HaegopaColors.Brand,
        onPrimary = HaegopaColors.OnAccent,
        secondary = HaegopaColors.Amber,
        onSecondary = HaegopaColors.OnAccent,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.textSecondary,
        outline = p.outline,
        error = HaegopaColors.Danger,
    )
} else {
    lightColorScheme(
        primary = HaegopaColors.Brand,
        onPrimary = HaegopaColors.OnAccent,
        secondary = HaegopaColors.Amber,
        onSecondary = HaegopaColors.OnAccent,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.textSecondary,
        outline = p.outline,
        error = HaegopaColors.Danger,
    )
}

/**
 * 화면 크기에 맞춘 UI 배율.
 * 기준 디자인은 1180×800dp. Pleos Connect 에뮬레이터는 160dpi 에 앱 창이 1682×1210dp 라서
 * 그대로 그리면 글자가 절반 크기로 보인다 → 창 크기에 비례해 전체 UI 를 키운다 (작은 화면은 1배 유지).
 */
val LocalUiScale = staticCompositionLocalOf { 1f }

private const val DESIGN_WIDTH_DP = 1180f
private const val DESIGN_HEIGHT_DP = 800f

@Composable
fun HaegopaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val configuration = LocalConfiguration.current
    val scale = maxOf(
        1f,
        minOf(configuration.screenWidthDp / DESIGN_WIDTH_DP, configuration.screenHeightDp / DESIGN_HEIGHT_DP),
    )
    CompositionLocalProvider(
        LocalHaegopaPalette provides palette,
        LocalUiScale provides scale,
    ) {
        MaterialTheme(colorScheme = schemeOf(palette), typography = HaegopaTypography) {
            ProvideUiScale(content)
        }
    }
}

/**
 * [LocalUiScale] 만큼 dp·sp 를 키운다.
 * Dialog 는 새 창이라 플랫폼이 LocalDensity 를 다시 넣으므로, Dialog 내용에서도 한 번 더 감싸 준다.
 */
@Composable
fun ProvideUiScale(content: @Composable () -> Unit) {
    val scale = LocalUiScale.current
    if (scale == 1f) {
        content()
        return
    }
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = base.density * scale, fontScale = base.fontScale),
        content = content,
    )
}
