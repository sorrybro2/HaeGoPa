package com.haegopa.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.sdk.WaypointKind
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.color
import com.haegopa.app.ui.theme.ink

/**
 * 정차 중 스팟 상세 — 목록 카드나 지도 핀을 누르면 도크의 목록 자리에 오른쪽에서 스윽 밀려 들어온다.
 * 왼쪽 지도는 가리지 않고 그 스팟으로 확대해 보여 준다. [목록] 이나 지도 빈 곳을 누르면 목록으로 돌아간다.
 * 주행이 시작되면 ViewModel 이 선택을 풀어 자동으로 닫힌다.
 *
 * 위: [목록] · 종류 / 가운데(스크롤): 이름 · 거리 · 소개 · 주소 · 태그 · 음성 소개 원고 /
 * 아래(고정): [목적지] · [경유지] (+ [소개 듣기] / [1분 스트레칭])
 */
@Composable
fun SpotDetailPanel(
    spot: Spot,
    role: WaypointKind?,
    onBack: () -> Unit,
    onSetDestination: () -> Unit,
    onAddWaypoint: () -> Unit,
    onPlayDocent: () -> Unit,
    onOpenStretching: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = spot.category.color()
    val ink = spot.category.ink()
    Surface(color = HaegopaColors.Surface, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(50),
                    color = HaegopaColors.SurfaceHigher,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        HIcon(HaegopaIcons.ChevronLeft, tint = HaegopaColors.TextSecondary, size = 24.dp)
                        Spacer(Modifier.width(2.dp))
                        Text("목록", style = MaterialTheme.typography.labelLarge, color = HaegopaColors.TextSecondary)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = listOfNotNull(spot.category.label, spot.kind?.label).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = ink,
                )
            }
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryIconTile(spot.category, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = spot.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = HaegopaColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DistanceText(meters = spot.distanceMeters, ink = ink, numberSize = MaterialTheme.typography.headlineMedium.fontSize)
                    Spacer(Modifier.width(14.dp))
                    HIcon(HaegopaIcons.Car, tint = HaegopaColors.TextSecondary, size = 20.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "약 ${spot.etaMinutes}분" +
                            if (spot.category == SpotCategory.EAT) " · 경로 +${spot.detourMinutes}분" else "",
                        style = MaterialTheme.typography.titleSmall,
                        color = HaegopaColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(spot.summary, style = MaterialTheme.typography.bodyLarge, color = HaegopaColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HIcon(HaegopaIcons.Place, tint = HaegopaColors.TextTertiary, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(spot.address, style = MaterialTheme.typography.bodyMedium, color = HaegopaColors.TextSecondary)
                }
                Spacer(Modifier.height(12.dp))
                TagRow(spot = spot, ink = ink, max = 6)

                spot.docentScript?.let { script ->
                    Spacer(Modifier.height(14.dp))
                    Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.10f)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HIcon(HaegopaIcons.Docent, tint = ink, size = 20.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("음성 소개", style = MaterialTheme.typography.labelLarge, color = ink)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(script, style = MaterialTheme.typography.bodyMedium, color = HaegopaColors.TextSecondary)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 손이 닿는 아래쪽에 고정: 길안내 두 가지 + (있으면) 소개 듣기 / 스트레칭
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                RouteChoiceButton(
                    choice = RouteChoice.DESTINATION,
                    role = role,
                    accent = accent,
                    ink = ink,
                    onClick = onSetDestination,
                    big = true,
                    modifier = Modifier.weight(1f),
                )
                RouteChoiceButton(
                    choice = RouteChoice.WAYPOINT,
                    role = role,
                    accent = accent,
                    ink = ink,
                    onClick = onAddWaypoint,
                    big = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (spot.hasDocent || spot.supportsStretching) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (spot.hasDocent) {
                        BigActionButton(
                            icon = HaegopaIcons.Docent,
                            label = "소개 듣기",
                            container = HaegopaColors.SurfaceHigher,
                            content = HaegopaColors.TextPrimary,
                            onClick = onPlayDocent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (spot.supportsStretching) {
                        BigActionButton(
                            icon = HaegopaIcons.Stretch,
                            label = "1분 스트레칭",
                            container = HaegopaColors.SurfaceHigher,
                            content = HaegopaColors.TextPrimary,
                            onClick = onOpenStretching,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
