package com.haegopa.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.haegopa.app.domain.model.SearchRadius
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.ProvideUiScale
import com.haegopa.app.ui.theme.color
import com.haegopa.app.ui.theme.ink
import kotlin.math.roundToInt

/**
 * 차량 기준 검색 반경 선택: 2km · 5km · 10km · 직접 설정.
 * 선택값은 지도 원 · 목록 · 음성 안내에 바로 반영된다.
 */
@Composable
fun RadiusSelector(
    radiusMeters: Int,
    customRadiusMeters: Int?,
    category: SpotCategory,
    onSelect: (Int) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = category.color()
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = HaegopaColors.Surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, HaegopaColors.Outline.copy(alpha = if (HaegopaColors.IsDark) 1f else 0.6f)),
        shadowElevation = if (HaegopaColors.IsDark) 0.dp else 6.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(5.dp),
        ) {
            Text(
                text = "반경",
                style = MaterialTheme.typography.labelMedium,
                color = HaegopaColors.TextSecondary,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            )
            SearchRadius.PRESETS.forEach { meters ->
                RadiusSegment(
                    label = GeoUtils.formatRadius(meters),
                    selected = radiusMeters == meters,
                    accent = accent,
                    onClick = { onSelect(meters) },
                )
            }
            val customSelected = !SearchRadius.isPreset(radiusMeters)
            RadiusSegment(
                label = if (customSelected) GeoUtils.formatRadius(radiusMeters) else customRadiusMeters?.let { GeoUtils.formatRadius(it) } ?: "직접",
                selected = customSelected,
                accent = accent,
                icon = HaegopaIcons.Tune,
                onClick = onCustom,
            )
        }
    }
}

@Composable
private fun RadiusSegment(label: String, selected: Boolean, accent: Color, onClick: () -> Unit, icon: Int? = null) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent else Color.Transparent,
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (icon != null) {
                HIcon(icon, tint = if (selected) HaegopaColors.OnAccent else HaegopaColors.TextSecondary, size = 16.dp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) HaegopaColors.OnAccent else HaegopaColors.TextPrimary,
            )
        }
    }
}

/** [직접 설정] 슬라이더 (1~20km, 0.5km 단위) — 정차 중에만 열린다 */
@Composable
fun CustomRadiusDialog(
    initialMeters: Int,
    category: SpotCategory,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ProvideUiScale {
            val accent = category.color()
            val ink = category.ink()
            var km by remember { mutableFloatStateOf(initialMeters / 1000f) }
            val meters = SearchRadius.normalizeCustom((km * 1000).roundToInt())
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = HaegopaColors.Surface,
                border = BorderStroke(2.dp, accent.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .widthIn(min = 420.dp, max = 640.dp),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HIcon(HaegopaIcons.Tune, tint = ink, size = 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("검색 반경 직접 설정", style = MaterialTheme.typography.titleLarge, color = HaegopaColors.TextPrimary)
                    }
                    Text(
                        text = "차량 현재 위치에서 이 거리 안의 ${category.label} 스팟을 가까운 순으로 보여 드려요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HaegopaColors.TextSecondary,
                    )
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(GeoUtils.formatRadius(meters), style = MaterialTheme.typography.displaySmall, color = ink)
                    }
                    Slider(
                        value = km,
                        onValueChange = { km = it },
                        valueRange = SearchRadius.CUSTOM_MIN_METERS / 1000f..SearchRadius.CUSTOM_MAX_METERS / 1000f,
                        steps = (SearchRadius.CUSTOM_MAX_METERS - SearchRadius.CUSTOM_MIN_METERS) / SearchRadius.CUSTOM_STEP_METERS - 1,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = HaegopaColors.SurfaceHigher,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                    )
                    Row {
                        Text(GeoUtils.formatRadius(SearchRadius.CUSTOM_MIN_METERS), style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextTertiary)
                        Spacer(Modifier.weight(1f))
                        Text(GeoUtils.formatRadius(SearchRadius.CUSTOM_MAX_METERS), style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextTertiary)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        BigActionButton(
                            icon = null,
                            label = "취소",
                            container = HaegopaColors.SurfaceHigh,
                            content = HaegopaColors.TextSecondary,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        BigActionButton(
                            icon = null,
                            label = "${GeoUtils.formatRadius(meters)}로 검색",
                            container = accent,
                            content = HaegopaColors.OnAccent,
                            onClick = { onApply(meters) },
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
            }
        }
    }
}
