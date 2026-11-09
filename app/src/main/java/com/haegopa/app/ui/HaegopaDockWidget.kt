package com.haegopa.app.ui

import com.haegopa.app.FeatureFlags
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haegopa.app.domain.model.DriveMode
import com.haegopa.app.domain.model.GearPosition
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.presentation.FeedState
import com.haegopa.app.presentation.MainUiState
import com.haegopa.app.presentation.SdkSummary
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.color
import com.haegopa.app.ui.theme.ink
import kotlinx.coroutines.delay

/** 도크 위젯이 위임하는 사용자 액션 묶음 */
data class DockActions(
    val onToggleBooster: (Boolean) -> Unit,
    val onSelectCategory: (SpotCategory) -> Unit,
    val onLoadMore: () -> Unit,
    val onRefresh: () -> Unit,
    val onSetDestination: (Spot) -> Unit,
    val onAddWaypoint: (Spot) -> Unit,
    val onOpenDetail: (Spot) -> Unit,
    val onPlayDocent: (Spot) -> Unit,
    val onOpenStretching: (String?) -> Unit,
    val onRepeatAnnouncement: () -> Unit,
    val onAcceptFatigueNudge: () -> Unit,
    val onDismissFatigueNudge: () -> Unit,
)

/**
 * 와이드 화면 오른쪽 '해고파' 도크.
 * 위에서부터 브랜드·주행 상태 / 졸음 방지 모드(FeatureFlags 로 끌 수 있음) / 먹·놀·쉬 탭 / 스팟 목록(정차: 전체 카드, 주행: 동승자용 큰 글씨 목록) / 연동 상태.
 */
@Composable
fun HaegopaDockWidget(
    state: MainUiState,
    actions: DockActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Surface(color = HaegopaColors.Surface, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                DockHeader(state)
                Spacer(Modifier.height(14.dp))
                if (FeatureFlags.DROWSINESS_BOOSTER) {
                    BoosterCard(enabled = state.booster.enabled, onToggle = actions.onToggleBooster)
                    Spacer(Modifier.height(12.dp))
                }
                CategoryTabs(selected = state.selectedCategory, onSelect = actions.onSelectCategory)
                AnimatedVisibility(visible = state.fatigueNudgeVisible) {
                    FatigueNudge(
                        tripMinutes = state.tripMinutes,
                        onAccept = actions.onAcceptFatigueNudge,
                        onDismiss = actions.onDismissFatigueNudge,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    key(state.selectedCategory, state.isDriving) {
                        if (state.isDriving) {
                            DrivingFeed(state = state, actions = actions)
                        } else {
                            SpotFeed(state = state, actions = actions)
                        }
                    }
                }
                SdkStatusFooter(state.sdk)
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 56.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Color(0xFF1F242C),
                    contentColor = Color.White,
                )
            }
        }
    }
}

// ── 헤더 ──────────────────────────────────────────────────────────────────────

private data class Slogan(val text: String, val category: SpotCategory?)

private val SLOGANS = listOf(
    Slogan("배고파? 먹고파!", SpotCategory.EAT),
    Slogan("심심해? 놀고파!", SpotCategory.PLAY),
    Slogan("지쳤어? 쉬고파!", SpotCategory.REST),
    Slogan("뭐라도 하고플 땐, 해고파", null),
)

@Composable
private fun DockHeader(state: MainUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(HaegopaIcons.Logo),
            contentDescription = null,
            modifier = Modifier.height(42.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("해고파", style = MaterialTheme.typography.headlineSmall, color = HaegopaColors.TextPrimary)
            SloganTicker(animate = !state.isDriving)
        }
        DriveStatePill(driving = state.isDriving, speedKmh = state.speedKmh, gear = state.gear)
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HIcon(HaegopaIcons.Clock, tint = HaegopaColors.TextTertiary, size = 16.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${formatTrip(state.tripMinutes)}째 운전",
            style = MaterialTheme.typography.labelMedium,
            color = HaegopaColors.TextSecondary,
        )
        Spacer(Modifier.width(12.dp))
        HIcon(HaegopaIcons.Place, tint = HaegopaColors.TextTertiary, size = 16.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = state.originLabel,
            style = MaterialTheme.typography.labelMedium,
            color = HaegopaColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SloganTicker(animate: Boolean) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(animate) {
        while (animate) {
            delay(2_800)
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
        val slogan = SLOGANS[i]
        Text(
            text = slogan.text,
            style = MaterialTheme.typography.labelLarge,
            color = slogan.category?.ink() ?: HaegopaColors.Brand,
        )
    }
}

@Composable
private fun DriveStatePill(driving: Boolean, speedKmh: Int, gear: GearPosition) {
    val fill = if (driving) HaegopaColors.Booster else HaegopaColors.Success
    val ink = if (driving) HaegopaColors.BoosterInk else HaegopaColors.SuccessInk
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = fill.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, fill.copy(alpha = 0.45f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        ) {
            HIcon(if (driving) HaegopaIcons.Car else HaegopaIcons.Parking, tint = ink, size = 24.dp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(if (driving) "주행 중" else "정차 중", style = MaterialTheme.typography.labelLarge, color = ink)
                Text(
                    text = "$speedKmh km/h" + if (gear != GearPosition.UNKNOWN) " · ${gear.label}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = HaegopaColors.TextSecondary,
                )
            }
        }
    }
}

// ── 졸음 방지 모드 ────────────────────────────────────────────────────────────

@Composable
private fun BoosterCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val background = if (enabled) {
        Brush.horizontalGradient(listOf(HaegopaColors.Booster, HaegopaColors.BoosterDeep))
    } else {
        Brush.horizontalGradient(listOf(HaegopaColors.SurfaceHigh, HaegopaColors.SurfaceHigh))
    }
    Surface(
        onClick = { onToggle(!enabled) },
        shape = shape,
        color = Color.Transparent,
        border = if (enabled) null else BorderStroke(1.dp, HaegopaColors.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(background, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (enabled) HaegopaColors.OnAccent.copy(alpha = 0.12f) else HaegopaColors.Booster.copy(alpha = 0.18f)),
            ) {
                HIcon(HaegopaIcons.Bolt, tint = if (enabled) HaegopaColors.OnAccent else HaegopaColors.BoosterInk, size = 28.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "졸음 방지 모드 ON" else "졸음 방지 모드",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) HaegopaColors.OnAccent else HaegopaColors.TextPrimary,
                )
                Text(
                    text = if (enabled) "MR 재생 중 · 지도 위 가사를 따라 불러요" else "노래방 MR · 싱얼롱 가사 · 1분 퀴즈",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) HaegopaColors.OnAccent.copy(alpha = 0.75f) else HaegopaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = HaegopaColors.OnAccent.copy(alpha = 0.85f),
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = HaegopaColors.TextTertiary,
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
            .clip(RoundedCornerShape(20.dp))
            .background(if (HaegopaColors.IsDark) HaegopaColors.Background else HaegopaColors.SurfaceHigher)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SpotCategory.entries.forEach { category ->
            val isSelected = category == selected
            Surface(
                onClick = { onSelect(category) },
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) category.color() else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HIcon(category.iconRes(), tint = if (isSelected) HaegopaColors.OnAccent else category.ink(), size = 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) HaegopaColors.OnAccent else HaegopaColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FatigueNudge(tripMinutes: Int, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = HaegopaColors.Rest.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, HaegopaColors.Rest.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryIconTile(SpotCategory.REST, size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${formatTrip(tripMinutes)}째 운전 중", style = MaterialTheme.typography.titleSmall, color = HaegopaColors.TextPrimary)
                Text("2시간마다 15분 휴식을 권해요", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
            }
            TextButton(onClick = onDismiss) { Text("나중에", color = HaegopaColors.TextSecondary) }
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = HaegopaColors.Rest, contentColor = HaegopaColors.OnAccent),
            ) { Text("쉼터 보기") }
        }
    }
}

// ── 정차: 2km 거리순 무한 스크롤 ─────────────────────────────────────────────

private const val PREFETCH_DISTANCE = 2

@Composable
private fun SpotFeed(state: MainUiState, actions: DockActions) {
    val feed = state.currentFeed
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
                role = state.routeRole(spot),
                onSetDestination = { actions.onSetDestination(spot) },
                onAddWaypoint = { actions.onAddWaypoint(spot) },
                onClick = { actions.onOpenDetail(spot) },
                onPlayDocent = { actions.onPlayDocent(spot) },
                onOpenStretching = { actions.onOpenStretching(spot.name) },
            )
        }
        item(key = "footer") {
            FeedFooter(feed = feed, radiusMeters = state.radiusMeters, onRefresh = actions.onRefresh, onRetry = actions.onLoadMore)
        }
    }
    InfiniteLoad(listState = listState, feed = feed, limit = Int.MAX_VALUE, onLoadMore = actions.onLoadMore)
}

/** 리스트 끝에 가까워지면 다음 페이지(가까운 순)를 이어 붙인다 → 반경 2km 가 끝날 때까지(또는 상한까지) */
@Composable
private fun InfiniteLoad(listState: LazyListState, feed: FeedState, limit: Int, onLoadMore: () -> Unit) {
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1 - PREFETCH_DISTANCE
        }
    }
    LaunchedEffect(nearEnd, feed.items.size, feed.isLoading, feed.endReached) {
        if (nearEnd && !feed.isLoading && !feed.endReached && feed.error == null && feed.items.size < limit) onLoadMore()
    }
}

@Composable
private fun FeedHeader(state: MainUiState, feed: FeedState) {
    val category = state.selectedCategory
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)) {
        Text(
            text = "${category.question} ${category.answer}",
            style = MaterialTheme.typography.titleMedium,
            color = category.ink(),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (feed.totalInRadius > 0) "${feed.totalInRadius}곳 · 가까운 순" else "찾는 중",
            style = MaterialTheme.typography.labelMedium,
            color = HaegopaColors.TextTertiary,
        )
    }
}

@Composable
private fun FeedFooter(feed: FeedState, radiusMeters: Int, onRefresh: () -> Unit, onRetry: () -> Unit) {
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
                Text("가까운 순으로 더 불러오는 중", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
            }
            feed.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("불러오지 못했어요: ${feed.error}", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.DangerInk)
                TextButton(onClick = onRetry) { Text("다시 시도") }
            }
            feed.endReached && feed.items.isNotEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "반경 ${GeoUtils.formatRadius(radiusMeters)} 안의 스팟을 모두 봤어요",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextTertiary,
                )
                TextButton(onClick = onRefresh) {
                    HIcon(HaegopaIcons.Refresh, tint = HaegopaColors.TextSecondary, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("새로고침", color = HaegopaColors.TextSecondary)
                }
            }
            else -> Spacer(Modifier.height(1.dp))
        }
    }
}

// ── 주행: 동승자용 큰 글씨 목록 (스크롤 가능 · 개수 상한) ─────────────────────

@Composable
private fun DrivingFeed(state: MainUiState, actions: DockActions) {
    val feed = state.currentFeed
    val spots = feed.items.take(DriveMode.DRIVING_MAX_LIST_ITEMS)
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "driving-header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
                HIcon(HaegopaIcons.Car, tint = HaegopaColors.BoosterInk, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                Text("주행 중 · 큰 글씨 목록", style = MaterialTheme.typography.labelLarge, color = HaegopaColors.BoosterInk)
                Spacer(Modifier.weight(1f))
                Text("동승자가 골라 주세요", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextTertiary)
            }
        }
        val top = spots.firstOrNull()
        if (top == null) {
            item(key = "driving-loading") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = HaegopaColors.Brand, strokeWidth = 3.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("가장 가까운 곳을 찾는 중", color = HaegopaColors.TextSecondary)
                }
            }
        } else {
            item(key = "top-${top.id}") {
                DrivingSpotBanner(
                    spot = top,
                    role = state.routeRole(top),
                    onSetDestination = { actions.onSetDestination(top) },
                    onAddWaypoint = { actions.onAddWaypoint(top) },
                    onRepeat = actions.onRepeatAnnouncement,
                )
            }
            items(spots.drop(1), key = { it.id }) { spot ->
                DrivingSpotRow(
                    spot = spot,
                    role = state.routeRole(spot),
                    onSetDestination = { actions.onSetDestination(spot) },
                    onAddWaypoint = { actions.onAddWaypoint(spot) },
                    // 주행 중에는 상세 화면 대신 음성으로 읽어 준다 (ViewModel.openDetail)
                    onClick = { actions.onOpenDetail(spot) },
                )
            }
        }
        item(key = "driving-footer") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp, top = 2.dp)) {
                HIcon(HaegopaIcons.Parking, tint = HaegopaColors.TextTertiary, size = 18.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "정차하면 상세 정보 · 도슨트 · 스트레칭이 열려요",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextTertiary,
                )
            }
        }
    }
    InfiniteLoad(listState = listState, feed = feed, limit = DriveMode.DRIVING_MAX_LIST_ITEMS, onLoadMore = actions.onLoadMore)
}

// ── Pleos SDK 연동 상태 (한 줄 요약 · 눌러서 펼치기) ─────────────────────────────

@Composable
private fun SdkStatusFooter(sdk: SdkSummary) {
    var expanded by remember { mutableStateOf(false) }
    // 졸음 방지(퀴즈)를 끄면 STT 를 쓰지 않으므로 음성인식 상태는 숨긴다
    val items = listOfNotNull(
        "내비" to sdk.navi,
        "차량" to sdk.vehicle,
        "음성합성" to sdk.tts,
        ("음성인식" to sdk.stt).takeIf { FeatureFlags.DROWSINESS_BOOSTER },
    )
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = HaegopaColors.Outline)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(top = 8.dp, bottom = 2.dp),
        ) {
            Text("Pleos 연동", style = MaterialTheme.typography.labelSmall, color = HaegopaColors.TextTertiary)
            Spacer(Modifier.width(8.dp))
            items.forEach { (label, status) ->
                StatusDot(status)
                Spacer(Modifier.width(3.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = HaegopaColors.TextSecondary, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            HIcon(if (expanded) HaegopaIcons.ExpandLess else HaegopaIcons.ExpandMore, tint = HaegopaColors.TextTertiary, size = 18.dp)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 6.dp)) {
                items.forEach { (label, status) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        StatusDot(status)
                        Spacer(Modifier.width(6.dp))
                        Text("$label · ${status.name}", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
                    }
                }
                Text(
                    text = "차속 신호: ${sdk.speedSource}",
                    style = MaterialTheme.typography.labelSmall,
                    color = HaegopaColors.TextTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: SdkStatus) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(status.backend.color()),
    )
}

fun formatTrip(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
}
