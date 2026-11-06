package com.haegopa.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haegopa.app.domain.model.GearPosition
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.presentation.FeedState
import com.haegopa.app.presentation.MainUiState
import com.haegopa.app.presentation.SdkSummary
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.color
import kotlinx.coroutines.delay

/** 도크 위젯이 위임하는 사용자 액션 묶음 */
data class DockActions(
    val onToggleBooster: (Boolean) -> Unit,
    val onSelectCategory: (SpotCategory) -> Unit,
    val onLoadMore: () -> Unit,
    val onRefresh: () -> Unit,
    val onAddWaypoint: (Spot) -> Unit,
    val onOpenDetail: (Spot) -> Unit,
    val onPlayDocent: (Spot) -> Unit,
    val onOpenStretching: (String?) -> Unit,
    val onRepeatAnnouncement: () -> Unit,
    val onAcceptFatigueNudge: () -> Unit,
    val onDismissFatigueNudge: () -> Unit,
)

/**
 * 와이드 디스플레이 우측 30~35% '해고파' 사이드 도크.
 * 상단: 졸음 방지 모드 토글 / 중단: 먹·놀·쉬 탭 / 하단: 2km 거리순 카드 리스트(정차) 또는 1개 배너(주행)
 */
@Composable
fun HaegopaDockWidget(
    state: MainUiState,
    actions: DockActions,
    modifier: Modifier = Modifier,
) {
    Surface(color = HaegopaColors.Surface, modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            DockHeader(state)
            Spacer(Modifier.height(12.dp))
            BoosterToggle(enabled = state.booster.enabled, onToggle = actions.onToggleBooster)
            Spacer(Modifier.height(12.dp))
            CategoryTabs(selected = state.selectedCategory, onSelect = actions.onSelectCategory)
            AnimatedVisibility(visible = state.fatigueNudgeVisible) {
                FatigueNudge(
                    tripMinutes = state.tripMinutes,
                    onAccept = actions.onAcceptFatigueNudge,
                    onDismiss = actions.onDismissFatigueNudge,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.isDriving) {
                    DrivingModePanel(state = state, actions = actions)
                } else {
                    SpotFeed(state = state, actions = actions)
                }
            }
            SdkStatusFooter(state.sdk)
        }
    }
}

// ── 헤더 ──────────────────────────────────────────────────────────────────────

private val SLOGANS = listOf("배고파? 먹고파!", "심심해? 놀고파!", "지쳤어? 쉬고파!", "뭐라도 하고플 땐? 👉 해고파")

@Composable
private fun DockHeader(state: MainUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("해고파", style = MaterialTheme.typography.headlineMedium, color = HaegopaColors.Brand)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "HAEGOPA",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            SloganTicker(animate = !state.isDriving)
        }
        DriveStatusChip(driving = state.isDriving, speedKmh = state.speedKmh, gear = state.gear)
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "🕒 주행 ${formatTrip(state.tripMinutes)}째 · 📍 ${state.originLabel} · 반경 2km",
        style = MaterialTheme.typography.labelMedium,
        color = HaegopaColors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SloganTicker(animate: Boolean) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(animate) {
        while (animate) {
            delay(2_600)
            index = (index + 1) % SLOGANS.size
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
        },
        label = "slogan",
    ) { i ->
        Text(SLOGANS[i], style = MaterialTheme.typography.titleSmall, color = HaegopaColors.TextPrimary)
    }
}

@Composable
private fun DriveStatusChip(driving: Boolean, speedKmh: Int, gear: GearPosition) {
    val color = if (driving) HaegopaColors.Booster else HaegopaColors.Success
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (driving) "🚗 주행 중" else "🅿️ 정차 중",
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
            Text(
                text = "$speedKmh km/h" + if (gear != GearPosition.UNKNOWN) " · ${gear.label}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = HaegopaColors.TextSecondary,
            )
        }
    }
}

// ── 졸음 방지 모드 토글 ────────────────────────────────────────────────────────

@Composable
private fun BoosterToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val accent = HaegopaColors.Booster
    Surface(
        onClick = { onToggle(!enabled) },
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) accent.copy(alpha = 0.18f) else HaegopaColors.SurfaceHigh,
        border = BorderStroke(if (enabled) 2.dp else 1.dp, if (enabled) accent else HaegopaColors.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚡", fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "졸음 방지 모드 : ${if (enabled) "ON" else "OFF"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) accent else HaegopaColors.TextPrimary,
                )
                Text(
                    text = if (enabled) "노래방 MR · 싱얼롱 가사 · 1분 퀴즈" else "원터치로 깨어나는 부스터",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextSecondary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = HaegopaColors.Background,
                    checkedTrackColor = accent,
                    uncheckedThumbColor = HaegopaColors.TextSecondary,
                    uncheckedTrackColor = HaegopaColors.SurfaceHigher,
                    uncheckedBorderColor = HaegopaColors.Outline,
                ),
            )
        }
    }
}

// ── 먹 · 놀 · 쉬 탭 ────────────────────────────────────────────────────────────

@Composable
private fun CategoryTabs(selected: SpotCategory, onSelect: (SpotCategory) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(HaegopaColors.Background)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SpotCategory.entries.forEach { category ->
            val isSelected = category == selected
            Surface(
                onClick = { onSelect(category) },
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) category.color() else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .height(62.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(category.emoji, fontSize = 20.sp)
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) HaegopaColors.Background else HaegopaColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FatigueNudge(tripMinutes: Int, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = HaegopaColors.Rest.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, HaegopaColors.Rest),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("😴", fontSize = 22.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${formatTrip(tripMinutes)}째 운전 중", style = MaterialTheme.typography.titleSmall, color = HaegopaColors.TextPrimary)
                Text("2시간마다 15분 휴식을 권해요", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
            }
            TextButton(onClick = onDismiss) { Text("나중에", color = HaegopaColors.TextSecondary) }
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = HaegopaColors.Rest, contentColor = HaegopaColors.Background),
            ) { Text("☕ 쉬고파") }
        }
    }
}

// ── 정차: 2km 거리순 무한 스크롤 ─────────────────────────────────────────────

private const val PREFETCH_DISTANCE = 2

@Composable
private fun SpotFeed(state: MainUiState, actions: DockActions) {
    val feed = state.currentFeed
    key(state.selectedCategory) {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") { FeedHeader(state = state, feed = feed) }
            items(feed.items, key = { it.id }) { spot ->
                SpotCardItem(
                    spot = spot,
                    isAdded = state.isAdded(spot),
                    onAddWaypoint = { actions.onAddWaypoint(spot) },
                    onClick = { actions.onOpenDetail(spot) },
                    onPlayDocent = { actions.onPlayDocent(spot) },
                    onOpenStretching = { actions.onOpenStretching(spot.name) },
                )
            }
            item(key = "footer") { FeedFooter(feed = feed, onRefresh = actions.onRefresh, onRetry = actions.onLoadMore) }
        }

        // 리스트 끝에 가까워지면 다음 페이지(가까운 순)를 이어 붙인다 → 반경 2km 가 끝날 때까지 무한 스크롤
        val nearEnd by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1 - PREFETCH_DISTANCE
            }
        }
        LaunchedEffect(nearEnd, feed.items.size, feed.isLoading, feed.endReached) {
            if (nearEnd && !feed.isLoading && !feed.endReached && feed.error == null) actions.onLoadMore()
        }
    }
}

@Composable
private fun FeedHeader(state: MainUiState, feed: FeedState) {
    val category = state.selectedCategory
    Column(Modifier.padding(bottom = 2.dp)) {
        Text(
            text = "${category.emoji} ${category.question} ${category.answer}",
            style = MaterialTheme.typography.titleSmall,
            color = category.color(),
        )
        Text(
            text = "반경 2km · 가까운 순 · ${if (feed.totalInRadius > 0) "총 ${feed.totalInRadius}곳" else "탐색 중"}",
            style = MaterialTheme.typography.labelMedium,
            color = HaegopaColors.TextSecondary,
        )
    }
}

@Composable
private fun FeedFooter(feed: FeedState, onRefresh: () -> Unit, onRetry: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        when {
            feed.isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 3.dp,
                    color = HaegopaColors.Brand,
                )
                Spacer(Modifier.width(10.dp))
                Text("가까운 순으로 더 불러오는 중…", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
            }
            feed.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("불러오지 못했어요: ${feed.error}", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.Danger)
                TextButton(onClick = onRetry) { Text("다시 시도") }
            }
            feed.endReached && feed.items.isNotEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("반경 2km 안의 스팟을 모두 봤어요 👀", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
                TextButton(onClick = onRefresh) { Text("🔄 새로고침", color = HaegopaColors.TextPrimary) }
            }
            else -> Spacer(Modifier.height(1.dp))
        }
    }
}

// ── 주행: 스크롤 잠금 + 최상단 1개 배너 ──────────────────────────────────────

@Composable
private fun DrivingModePanel(state: MainUiState, actions: DockActions) {
    val top = state.currentFeed.items.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = HaegopaColors.Booster, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("주행 중 간편 모드 · 스크롤 잠금", style = MaterialTheme.typography.labelLarge, color = HaegopaColors.Booster)
        }
        Text(
            text = "시선 분산을 막기 위해 가장 가까운 1곳만 보여 드리고 음성으로 안내해요",
            style = MaterialTheme.typography.labelMedium,
            color = HaegopaColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            userScrollEnabled = false, // 스크롤 Lock
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            item {
                if (top != null) {
                    DrivingSpotBanner(
                        spot = top,
                        isAdded = state.isAdded(top),
                        onAddWaypoint = { actions.onAddWaypoint(top) },
                        onRepeat = actions.onRepeatAnnouncement,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = HaegopaColors.Brand, strokeWidth = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("가장 가까운 곳을 찾는 중…", color = HaegopaColors.TextSecondary)
                    }
                }
            }
        }
        Text(
            text = "🅿️ 정차하면 반경 2km 전체 목록이 다시 열려요",
            style = MaterialTheme.typography.labelMedium,
            color = HaegopaColors.TextTertiary,
        )
    }
}

// ── Pleos SDK 진단 배지 ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SdkStatusFooter(sdk: SdkSummary) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        HorizontalDivider(color = HaegopaColors.Outline)
        Spacer(Modifier.height(6.dp))
        Text("Pleos SDK 연동 상태", style = MaterialTheme.typography.labelSmall, color = HaegopaColors.TextTertiary)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusPill("🧭", sdk.navi)
            StatusPill("🚗", sdk.vehicle)
            StatusPill("🔊", sdk.tts)
            StatusPill("🎙", sdk.stt)
        }
    }
}

@Composable
private fun StatusPill(icon: String, status: SdkStatus) {
    val color = status.backend.color()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "$icon ${status.name}",
            style = MaterialTheme.typography.labelSmall,
            color = HaegopaColors.TextSecondary,
            maxLines = 1,
        )
    }
}

fun formatTrip(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
}
