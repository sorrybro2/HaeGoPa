package com.haegopa.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotTag
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.color

/**
 * 2km 피드의 장소 카드: 거리 · 스팟 이름 · 소요 시간 · 특징 태그 + 우측 [경유지 추가] 원터치 버튼.
 */
@Composable
fun SpotCardItem(
    spot: Spot,
    isAdded: Boolean,
    onAddWaypoint: () -> Unit,
    onClick: () -> Unit,
    onPlayDocent: () -> Unit,
    onOpenStretching: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = spot.category.color()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = HaegopaColors.SurfaceHigh,
        border = BorderStroke(1.dp, HaegopaColors.Outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SpotMetaRow(spot = spot, accent = accent)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = spot.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = HaegopaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = spot.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = HaegopaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                TagRow(tags = spot.tags, accent = accent)
                if (spot.hasDocent || spot.supportsStretching) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (spot.hasDocent) MiniActionChip("🔊 도슨트 듣기", accent, onPlayDocent)
                        if (spot.supportsStretching) MiniActionChip("🧘 1분 스트레칭", accent, onOpenStretching)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            WaypointButton(isAdded = isAdded, accent = accent, onClick = onAddWaypoint)
        }
    }
}

/** 주행 중 간편 모드: 최상단 1개 추천 카드를 크게 보여 주는 심플 배너 */
@Composable
fun DrivingSpotBanner(
    spot: Spot,
    isAdded: Boolean,
    onAddWaypoint: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = spot.category.color()
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = HaegopaColors.SurfaceHigh,
        border = BorderStroke(2.dp, accent.copy(alpha = 0.75f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = "가장 가까운 ${spot.category.emoji} ${spot.category.label}",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DistanceBadge(meters = spot.distanceMeters, accent = accent, large = true)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "🚗 ${spot.etaMinutes}분" + if (spot.category == SpotCategory.EAT) " · 경로 +${spot.detourMinutes}분" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = HaegopaColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = spot.name,
                style = MaterialTheme.typography.headlineSmall,
                color = HaegopaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            TagRow(tags = spot.tags, accent = accent, max = 2)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigActionButton(
                    label = if (isAdded) "✓ 경로에 추가됨" else "🧭 경유지 추가",
                    container = if (isAdded) HaegopaColors.Success.copy(alpha = 0.2f) else accent,
                    content = if (isAdded) HaegopaColors.Success else HaegopaColors.Background,
                    enabled = !isAdded,
                    onClick = onAddWaypoint,
                    modifier = Modifier.weight(1.4f),
                )
                BigActionButton(
                    label = "🔊 다시 듣기",
                    container = HaegopaColors.SurfaceHigher,
                    content = HaegopaColors.TextPrimary,
                    onClick = onRepeat,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SpotMetaRow(spot: Spot, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DistanceBadge(meters = spot.distanceMeters, accent = accent)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "🚗 ${spot.etaMinutes}분",
            style = MaterialTheme.typography.labelMedium,
            color = HaegopaColors.TextSecondary,
        )
        if (spot.category == SpotCategory.EAT) {
            Text(
                text = " · 경로 +${spot.detourMinutes}분",
                style = MaterialTheme.typography.labelMedium,
                color = HaegopaColors.TextSecondary,
            )
        }
    }
}

@Composable
fun DistanceBadge(meters: Int, accent: Color, large: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = GeoUtils.formatDistance(meters),
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = accent,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(tags: List<SpotTag>, accent: Color, max: Int = 4) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.take(max).forEach { tag ->
            Text(
                text = tag.label,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
fun WaypointButton(
    isAdded: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (isAdded) HaegopaColors.Success.copy(alpha = 0.18f) else accent
    val content = if (isAdded) HaegopaColors.Success else HaegopaColors.Background
    Surface(
        onClick = onClick,
        enabled = !isAdded,
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = modifier
            .width(92.dp)
            .heightIn(min = 88.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
        ) {
            Icon(
                imageVector = if (isAdded) Icons.Filled.Check else Icons.Filled.Place,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isAdded) "추가됨" else "경유지\n추가",
                style = MaterialTheme.typography.labelLarge,
                color = content,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun BigActionButton(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = container,
        modifier = modifier.heightIn(min = 68.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = content,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MiniActionChip(label: String, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
