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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotTag
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.color
import com.haegopa.app.ui.theme.ink

/**
 * 정차 중 2km 피드 카드: 왼쪽 거리 · 가운데 이름/한 줄 소개/태그 · 오른쪽 [경유지] 원터치 버튼.
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
    val ink = spot.category.ink()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = if (HaegopaColors.IsDark) HaegopaColors.SurfaceHigh else HaegopaColors.Surface,
        border = BorderStroke(1.dp, HaegopaColors.Outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DistanceColumn(spot = spot, ink = ink, width = 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
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
                TagRow(spot = spot, ink = ink)
                if (spot.hasDocent || spot.supportsStretching) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (spot.hasDocent) MiniActionChip(HaegopaIcons.Docent, "도슨트 듣기", ink, onPlayDocent)
                        if (spot.supportsStretching) MiniActionChip(HaegopaIcons.Stretch, "1분 스트레칭", ink, onOpenStretching)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            WaypointButton(isAdded = isAdded, accent = spot.category.color(), onClick = onAddWaypoint, tonalInk = ink)
        }
    }
}

/**
 * 주행 중 '가장 가까운 곳' 하이라이트 카드 — 큰 거리 숫자 · 큰 이름 · 큰 버튼 2개.
 */
@Composable
fun DrivingSpotBanner(
    spot: Spot,
    isAdded: Boolean,
    onAddWaypoint: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = spot.category.color()
    val ink = spot.category.ink()
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = if (HaegopaColors.IsDark) HaegopaColors.SurfaceHigh else HaegopaColors.Surface,
        border = BorderStroke(2.dp, accent.copy(alpha = 0.8f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIcon(spot.category.iconRes(), tint = ink, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                Text("가장 가까운 ${spot.category.label}", style = MaterialTheme.typography.labelLarge, color = ink)
                Spacer(Modifier.weight(1f))
                EtaText(spot)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                DistanceText(meters = spot.distanceMeters, ink = ink, numberSize = MaterialTheme.typography.displaySmall.fontSize)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = spot.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = HaegopaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigActionButton(
                    icon = if (isAdded) HaegopaIcons.Added else HaegopaIcons.AddWaypoint,
                    label = if (isAdded) "경로에 추가됨" else "경유지 추가",
                    container = if (isAdded) HaegopaColors.Success.copy(alpha = 0.16f) else accent,
                    content = if (isAdded) HaegopaColors.SuccessInk else HaegopaColors.OnAccent,
                    enabled = !isAdded,
                    onClick = onAddWaypoint,
                    modifier = Modifier.weight(1.35f),
                )
                BigActionButton(
                    icon = HaegopaIcons.Speaker,
                    label = "다시 듣기",
                    container = HaegopaColors.SurfaceHigher,
                    content = HaegopaColors.TextPrimary,
                    onClick = onRepeat,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 주행 중 동승자용 간편 행: 거리 · 이름 · 경유지 버튼만 크게. 누르면 음성으로 읽어 준다.
 */
@Composable
fun DrivingSpotRow(
    spot: Spot,
    isAdded: Boolean,
    onAddWaypoint: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = spot.category.ink()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (HaegopaColors.IsDark) HaegopaColors.SurfaceHigh else HaegopaColors.Surface,
        border = BorderStroke(1.dp, HaegopaColors.Outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DistanceText(
                meters = spot.distanceMeters,
                ink = ink,
                numberSize = MaterialTheme.typography.titleLarge.fontSize,
                modifier = Modifier.width(92.dp),
            )
            Text(
                text = spot.name,
                style = MaterialTheme.typography.titleLarge,
                color = HaegopaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            WaypointButton(isAdded = isAdded, accent = spot.category.color(), onClick = onAddWaypoint, compact = true)
        }
    }
}

@Composable
private fun DistanceColumn(spot: Spot, ink: Color, width: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width)) {
        DistanceText(meters = spot.distanceMeters, ink = ink, numberSize = MaterialTheme.typography.titleLarge.fontSize)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(HaegopaIcons.Car, tint = HaegopaColors.TextTertiary, size = 14.dp)
            Spacer(Modifier.width(3.dp))
            Text("${spot.etaMinutes}분", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
        }
    }
}

/** "230m" → 큰 숫자 + 작은 단위 */
@Composable
fun DistanceText(meters: Int, ink: Color, numberSize: TextUnit, modifier: Modifier = Modifier) {
    val formatted = GeoUtils.formatDistance(meters)
    val number = formatted.takeWhile { it.isDigit() || it == '.' }
    val unit = formatted.drop(number.length)
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontSize = numberSize)) { append(number) }
            withStyle(SpanStyle(fontSize = numberSize * 0.55f)) { append(unit) }
        },
        style = MaterialTheme.typography.titleLarge,
        color = ink,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun EtaText(spot: Spot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HIcon(HaegopaIcons.Car, tint = HaegopaColors.TextSecondary, size = 18.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${spot.etaMinutes}분" + if (spot.category == SpotCategory.EAT) " · 경로 +${spot.detourMinutes}분" else "",
            style = MaterialTheme.typography.titleSmall,
            color = HaegopaColors.TextSecondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(spot: Spot, ink: Color, max: Int = 3) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (spot.category == SpotCategory.EAT) {
            Tag(text = "경로 +${spot.detourMinutes}분", color = HaegopaColors.TextSecondary, background = HaegopaColors.SurfaceHigher)
        }
        spot.tags.take(max).forEach { tag: SpotTag ->
            Tag(text = tag.label, color = ink, background = ink.copy(alpha = 0.10f))
        }
    }
}

@Composable
private fun Tag(text: String, color: Color, background: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 원터치 경유지 버튼. [tonalInk] 를 주면 연한 배경 + 진한 글자(정차 목록용, 카드가 여러 개라 덜 무겁게),
 * 없으면 꽉 찬 강조색(주행 중 큰 버튼용).
 */
@Composable
fun WaypointButton(
    isAdded: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    tonalInk: Color? = null,
) {
    val container = when {
        isAdded -> HaegopaColors.Success.copy(alpha = 0.14f)
        tonalInk != null -> accent.copy(alpha = 0.14f)
        else -> accent
    }
    val content = when {
        isAdded -> HaegopaColors.SuccessInk
        tonalInk != null -> tonalInk
        else -> HaegopaColors.OnAccent
    }
    Surface(
        onClick = onClick,
        enabled = !isAdded,
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = if (tonalInk != null && !isAdded) BorderStroke(1.dp, accent.copy(alpha = 0.45f)) else null,
        modifier = modifier
            .width(if (compact) 76.dp else 80.dp)
            .heightIn(min = if (compact) 64.dp else 80.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            HIcon(if (isAdded) HaegopaIcons.Added else HaegopaIcons.AddWaypoint, tint = content, size = 26.dp)
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (isAdded) "추가됨" else "경유지",
                style = MaterialTheme.typography.labelLarge,
                color = content,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun BigActionButton(
    icon: Int?,
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
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            if (icon != null) {
                HIcon(icon, tint = content, size = 24.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1)
        }
    }
}

@Composable
private fun MiniActionChip(icon: Int, label: String, ink: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = ink.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, ink.copy(alpha = 0.35f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            HIcon(icon, tint = ink, size = 18.dp)
            Spacer(Modifier.width(5.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = ink)
        }
    }
}

@Composable
fun CategoryIconTile(category: SpotCategory, size: Dp = 40.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(category.color().copy(alpha = 0.16f)),
    ) {
        HIcon(category.iconRes(), tint = category.ink(), size = size * 0.55f)
    }
}
