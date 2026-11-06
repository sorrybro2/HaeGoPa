package com.haegopa.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.haegopa.app.R

/** Pretendard (SIL Open Font License 1.1) — 한글·숫자가 고르게 읽히는 UI 서체 */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = Pretendard,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

/** 차량 디스플레이 가독성을 위해 기본보다 한 단계 큰 글자 */
val HaegopaTypography = Typography(
    displaySmall = style(40, 44, FontWeight.ExtraBold, -0.8f),
    headlineLarge = style(32, 38, FontWeight.ExtraBold, -0.6f),
    headlineMedium = style(28, 34, FontWeight.ExtraBold, -0.5f),
    headlineSmall = style(24, 30, FontWeight.Bold, -0.4f),
    titleLarge = style(22, 28, FontWeight.Bold, -0.3f),
    titleMedium = style(19, 25, FontWeight.Bold, -0.2f),
    titleSmall = style(16, 22, FontWeight.SemiBold, -0.1f),
    bodyLarge = style(18, 26, FontWeight.Normal),
    bodyMedium = style(16, 23, FontWeight.Normal),
    bodySmall = style(14, 20, FontWeight.Normal),
    labelLarge = style(16, 20, FontWeight.SemiBold),
    labelMedium = style(14, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)
