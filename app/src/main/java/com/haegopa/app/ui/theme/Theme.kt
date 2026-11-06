package com.haegopa.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val HaegopaColorScheme = darkColorScheme(
    primary = HaegopaColors.Brand,
    onPrimary = Color.White,
    secondary = HaegopaColors.Booster,
    onSecondary = Color.Black,
    background = HaegopaColors.Background,
    onBackground = HaegopaColors.TextPrimary,
    surface = HaegopaColors.Surface,
    onSurface = HaegopaColors.TextPrimary,
    surfaceVariant = HaegopaColors.SurfaceHigh,
    onSurfaceVariant = HaegopaColors.TextSecondary,
    outline = HaegopaColors.Outline,
    error = HaegopaColors.Danger,
)

/** 차량 디스플레이 가독성을 위해 기본보다 큰 글자 */
private val HaegopaTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HaegopaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HaegopaColorScheme,
        typography = HaegopaTypography,
        content = content,
    )
}
