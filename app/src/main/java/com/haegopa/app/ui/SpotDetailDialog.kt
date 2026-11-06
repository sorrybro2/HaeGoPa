package com.haegopa.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.color

/** 정차 중 카드 터치 시 열리는 상세 패널 (주행 시작 시 ViewModel 이 자동으로 닫는다) */
@Composable
fun SpotDetailDialog(
    spot: Spot,
    isAdded: Boolean,
    onAddWaypoint: () -> Unit,
    onPlayDocent: () -> Unit,
    onOpenStretching: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = spot.category.color()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = HaegopaColors.Surface,
            border = BorderStroke(2.dp, accent.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .widthIn(max = 820.dp),
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "${spot.category.emoji} ${spot.category.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
                Spacer(Modifier.height(4.dp))
                Text(spot.name, style = MaterialTheme.typography.headlineMedium, color = HaegopaColors.TextPrimary)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DistanceBadge(meters = spot.distanceMeters, accent = accent, large = true)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "🚗 약 ${spot.etaMinutes}분" +
                            if (spot.category == SpotCategory.EAT) " · 현재 경로에서 +${spot.detourMinutes}분 우회" else "",
                        style = MaterialTheme.typography.titleSmall,
                        color = HaegopaColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(spot.summary, style = MaterialTheme.typography.bodyLarge, color = HaegopaColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text("📍 ${spot.address}", style = MaterialTheme.typography.bodyMedium, color = HaegopaColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                TagRow(tags = spot.tags, accent = accent, max = 6)

                spot.docentScript?.let { script ->
                    Spacer(Modifier.height(14.dp))
                    Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.1f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("🔊 TTS 도슨트", style = MaterialTheme.typography.labelLarge, color = accent)
                            Spacer(Modifier.height(4.dp))
                            Text(script, style = MaterialTheme.typography.bodyMedium, color = HaegopaColors.TextSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    BigActionButton(
                        label = if (isAdded) "✓ 경로에 추가됨" else "🧭 경유지 추가",
                        container = if (isAdded) HaegopaColors.Success.copy(alpha = 0.2f) else accent,
                        content = if (isAdded) HaegopaColors.Success else HaegopaColors.Background,
                        enabled = !isAdded,
                        onClick = onAddWaypoint,
                        modifier = Modifier.weight(1f),
                    )
                    if (spot.hasDocent) {
                        BigActionButton(
                            label = "🔊 도슨트 듣기",
                            container = HaegopaColors.SurfaceHigher,
                            content = HaegopaColors.TextPrimary,
                            onClick = onPlayDocent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (spot.supportsStretching) {
                        BigActionButton(
                            label = "🧘 1분 스트레칭",
                            container = HaegopaColors.SurfaceHigher,
                            content = HaegopaColors.TextPrimary,
                            onClick = onOpenStretching,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BigActionButton(
                        label = "닫기",
                        container = HaegopaColors.SurfaceHigh,
                        content = HaegopaColors.TextSecondary,
                        onClick = onDismiss,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
        }
    }
}
