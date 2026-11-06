package com.haegopa.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.sdk.SdkBackend

/** 야간 주행에도 눈부시지 않은 차량용 다크 팔레트 */
object HaegopaColors {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF141B26)
    val SurfaceHigh = Color(0xFF1C2533)
    val SurfaceHigher = Color(0xFF243044)
    val Outline = Color(0xFF2E3A4E)

    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFFA3AFC0)
    val TextTertiary = Color(0xFF6E7B8E)

    val Brand = Color(0xFFFF6B4A)
    val Eat = Color(0xFFFF8A3D)
    val Play = Color(0xFF8B6CFF)
    val Rest = Color(0xFF2EC4B6)
    val Booster = Color(0xFFFFD23F)
    val Success = Color(0xFF3DDC84)
    val Danger = Color(0xFFFF5A5F)
    val Info = Color(0xFF3DA5FF)

    val MapBase = Color(0xFF0E1624)
    val MapBlock = Color(0xFF121D2E)
    val MapRoad = Color(0xFF1E2A3C)
    val MapRoadMajor = Color(0xFF2B3B54)
    val MapPark = Color(0xFF13301F)
    val MapWater = Color(0xFF0F2C47)
    val Route = Color(0xFF3DA5FF)
}

fun SpotCategory.color(): Color = when (this) {
    SpotCategory.EAT -> HaegopaColors.Eat
    SpotCategory.PLAY -> HaegopaColors.Play
    SpotCategory.REST -> HaegopaColors.Rest
}

fun SdkBackend.color(): Color = when (this) {
    SdkBackend.PLEOS -> HaegopaColors.Success
    SdkBackend.ANDROID -> HaegopaColors.Info
    SdkBackend.MOCK -> HaegopaColors.TextTertiary
    SdkBackend.CONNECTING -> HaegopaColors.Booster
}
