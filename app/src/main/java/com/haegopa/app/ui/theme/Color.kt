package com.haegopa.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.sdk.SdkBackend

/**
 * 낮(라이트) · 밤(다크) 모드에 따라 바뀌는 중립색.
 * Pleos Connect 기본 화면이 낮에는 밝은 테마라서, 해고파도 시스템 주야간 모드를 그대로 따른다.
 */
@Immutable
data class HaegopaPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHigher: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** 밝은 배경 위에서도 읽히는 강조 글자색 */
    val boosterInk: Color,
    val eatInk: Color,
    val playInk: Color,
    val restInk: Color,
    val successInk: Color,
    val dangerInk: Color,
    val map: MapPalette,
)

@Immutable
data class MapPalette(
    val base: Color,
    val block: Color,
    val blockAlt: Color,
    val park: Color,
    val water: Color,
    val roadMinor: Color,
    val roadMajor: Color,
    val roadCasing: Color,
    val highway: Color,
    val highwayCasing: Color,
    val label: Color,
    val chip: Color,
    val chipText: Color,
)

val DarkPalette = HaegopaPalette(
    isDark = true,
    background = Color(0xFF0B0E13),
    surface = Color(0xFF12161D),
    surfaceHigh = Color(0xFF1A1F28),
    surfaceHigher = Color(0xFF232A35),
    outline = Color(0xFF2C3443),
    textPrimary = Color(0xFFF3F5F9),
    textSecondary = Color(0xFFA4ADBB),
    textTertiary = Color(0xFF6C7685),
    boosterInk = Color(0xFFFFD23F),
    eatInk = Color(0xFFFF9A52),
    playInk = Color(0xFFA690FF),
    restInk = Color(0xFF3DD6C2),
    successInk = Color(0xFF45DB8C),
    dangerInk = Color(0xFFFF6B6F),
    map = MapPalette(
        base = Color(0xFF0F141B),
        block = Color(0xFF151B24),
        blockAlt = Color(0xFF19202B),
        park = Color(0xFF12291E),
        water = Color(0xFF0F2538),
        roadMinor = Color(0xFF212A36),
        roadMajor = Color(0xFF2F3A4A),
        roadCasing = Color(0xFF0A0E13),
        highway = Color(0xFF4A4232),
        highwayCasing = Color(0xFF0A0E13),
        label = Color(0xFF6F7B8C),
        chip = Color(0xE6151A22),
        chipText = Color(0xFFF3F5F9),
    ),
)

val LightPalette = HaegopaPalette(
    isDark = false,
    background = Color(0xFFF2F3F5),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF5F6F8),
    surfaceHigher = Color(0xFFEAEDF1),
    outline = Color(0xFFDFE3E9),
    textPrimary = Color(0xFF111418),
    textSecondary = Color(0xFF4E5866),
    textTertiary = Color(0xFF8B95A3),
    boosterInk = Color(0xFFB07400),
    eatInk = Color(0xFFD45A10),
    playInk = Color(0xFF6A4CE0),
    restInk = Color(0xFF0B8C7B),
    successInk = Color(0xFF14994E),
    dangerInk = Color(0xFFD7373D),
    map = MapPalette(
        base = Color(0xFFE8EBEE),
        block = Color(0xFFE0E4E9),
        blockAlt = Color(0xFFDBE0E6),
        park = Color(0xFFCFE8D4),
        water = Color(0xFFBFD8F0),
        roadMinor = Color(0xFFFFFFFF),
        roadMajor = Color(0xFFFFFFFF),
        roadCasing = Color(0xFFC9D0D9),
        highway = Color(0xFFFFE3A3),
        highwayCasing = Color(0xFFE2BF6E),
        label = Color(0xFF8A94A2),
        chip = Color(0xF2FFFFFF),
        chipText = Color(0xFF111418),
    ),
)

val LocalHaegopaPalette = staticCompositionLocalOf { DarkPalette }

/**
 * 해고파 색상 토큰.
 * - 강조색(카테고리·부스터·상태)은 테마와 무관한 고정값이라 Canvas(DrawScope) 안에서도 바로 쓸 수 있다.
 * - 배경·표면·글자색은 주야간 모드에 따라 바뀌므로 @Composable 안에서만 읽는다.
 */
object HaegopaColors {
    val Brand = Color(0xFFFF6B4A)
    val Eat = Color(0xFFFF8A3D)
    val Play = Color(0xFF8B6CFF)
    val Rest = Color(0xFF2EC4B6)
    val Booster = Color(0xFFFFC83D)
    val BoosterDeep = Color(0xFFFF9F2E)
    val Success = Color(0xFF2FBF71)
    val Danger = Color(0xFFF04F54)
    val Info = Color(0xFF3D8BFF)
    val Route = Color(0xFF3D8BFF)
    val Neutral = Color(0xFF8B95A3)

    /** 강조색으로 채운 버튼·탭 위의 글자/아이콘 */
    val OnAccent = Color(0xFF14171C)

    val Background: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.surfaceHigh
    val SurfaceHigher: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.surfaceHigher
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.outline
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.textSecondary
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.textTertiary
    val BoosterInk: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.boosterInk
    val SuccessInk: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.successInk
    val DangerInk: Color @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.dangerInk
    val IsDark: Boolean @Composable @ReadOnlyComposable get() = LocalHaegopaPalette.current.isDark
}

/** 카테고리 고유색 (채우기용, 테마 무관) */
fun SpotCategory.color(): Color = when (this) {
    SpotCategory.EAT -> HaegopaColors.Eat
    SpotCategory.PLAY -> HaegopaColors.Play
    SpotCategory.REST -> HaegopaColors.Rest
}

/** 카테고리 글자색 (밝은 배경에서는 한 톤 진하게) */
@Composable
@ReadOnlyComposable
fun SpotCategory.ink(): Color {
    val palette = LocalHaegopaPalette.current
    return when (this) {
        SpotCategory.EAT -> palette.eatInk
        SpotCategory.PLAY -> palette.playInk
        SpotCategory.REST -> palette.restInk
    }
}

/** SDK 연동 상태 점 색: Pleos 실연동(초록) · Android 대체(파랑) · Mock(회색) · 연결 중(노랑) */
fun SdkBackend.color(): Color = when (this) {
    SdkBackend.PLEOS -> HaegopaColors.Success
    SdkBackend.ANDROID -> HaegopaColors.Info
    SdkBackend.MOCK -> HaegopaColors.Neutral
    SdkBackend.CONNECTING -> HaegopaColors.Booster
}
