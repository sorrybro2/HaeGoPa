package com.haegopa.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haegopa.app.domain.model.GearPosition
import com.haegopa.app.presentation.MainUiState
import com.haegopa.app.presentation.MainViewModel
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.SpeechCaption
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.color
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.min

/**
 * 16:9 와이드 디스플레이 분할 구조 (Split-Screen)
 * ┌──────────────────────────────── 67% ───────────────────────────────┬──── 33% ────┐
 * │ 현대 순정 내비게이션 지도 영역 (Mock 캔버스)                            │ 해고파 도크  │
 * │  └ 졸음 방지 모드 ON 이면 상단에 싱얼롱 가사 패널                       │             │
 * └─────────────────────────────────────────────────────────────────────┴─────────────┘
 * 세로 화면(일반 폰 AVD)에서는 위(지도) / 아래(도크)로 쌓는다.
 */
@Composable
fun MainCarScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val caption by viewModel.caption.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val dockActions = remember(viewModel) {
        DockActions(
            onToggleBooster = viewModel::setBoosterEnabled,
            onSelectCategory = viewModel::selectCategory,
            onLoadMore = { viewModel.loadNextPage() },
            onRefresh = viewModel::refreshFeed,
            onAddWaypoint = viewModel::onAddWaypoint,
            onOpenDetail = viewModel::openDetail,
            onPlayDocent = viewModel::playDocent,
            onOpenStretching = viewModel::openStretching,
            onRepeatAnnouncement = viewModel::repeatAnnouncement,
            onAcceptFatigueNudge = viewModel::acceptFatigueNudge,
            onDismissFatigueNudge = viewModel::dismissFatigueNudge,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(HaegopaColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    NaviArea(
                        state = state,
                        caption = caption,
                        viewModel = viewModel,
                        modifier = Modifier
                            .weight(0.67f)
                            .fillMaxHeight(),
                    )
                    HaegopaDockWidget(
                        state = state,
                        actions = dockActions,
                        modifier = Modifier
                            .weight(0.33f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    NaviArea(
                        state = state,
                        caption = caption,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.42f),
                    )
                    HaegopaDockWidget(
                        state = state,
                        actions = dockActions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.58f),
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(16.dp),
                containerColor = HaegopaColors.SurfaceHigher,
                contentColor = HaegopaColors.TextPrimary,
            )
        }
    }

    // ── 정차 전용 화면 (주행이 시작되면 ViewModel 이 자동으로 닫는다) ──
    state.detailSpot?.let { spot ->
        SpotDetailDialog(
            spot = spot,
            isAdded = state.isAdded(spot),
            onAddWaypoint = { viewModel.onAddWaypoint(spot) },
            onPlayDocent = { viewModel.playDocent(spot) },
            onOpenStretching = { viewModel.openStretching(spot.name) },
            onDismiss = viewModel::closeDetail,
        )
    }
    if (state.stretchingOpen) {
        StretchingGuideDialog(
            placeName = state.stretchingPlace,
            onSpeak = viewModel::speak,
            onClose = viewModel::closeStretching,
        )
    }
    state.quiz?.let { quiz ->
        QuizDialog(
            quiz = quiz,
            sttStatus = state.sdk.stt,
            onAnswerByVoice = viewModel::answerByVoice,
            onSimulateAnswer = viewModel::simulateVoiceAnswer,
            onRestart = viewModel::startQuiz,
            onClose = viewModel::closeQuiz,
        )
    }
}

@Composable
private fun NaviArea(
    state: MainUiState,
    caption: SpeechCaption?,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clipToBounds()) {
        NaviMapMock(
            origin = state.origin,
            spots = state.currentFeed.items,
            category = state.selectedCategory,
            waypoints = state.waypoints,
            speedKmh = state.speedKmh,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                TurnByTurnCard()
                Spacer(Modifier.weight(1f))
                MapAreaBadge(state.sdk.navi)
            }
            AnimatedVisibility(
                visible = state.booster.enabled,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                val song = state.booster.song
                val silent by viewModel.mrSilent.collectAsStateWithLifecycle()
                if (song != null) {
                    SingAlongModal(
                        song = song,
                        positionMs = viewModel.mrPositionMs,
                        silent = silent,
                        driving = state.isDriving,
                        onNextSong = viewModel::nextSong,
                        onStartQuiz = viewModel::startQuiz,
                        onTurnOff = { viewModel.setBoosterEnabled(false) },
                    )
                }
            }
            CaptionBubble(caption)
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            DemoDrivePanel(
                speedKmh = state.speedKmh,
                gear = state.gear,
                speedSource = state.sdk.speedSource,
                onSimulateSpeed = viewModel::simulateSpeed,
                onAddTripMinutes = viewModel::addDemoTripMinutes,
            )
            Spacer(Modifier.weight(1f))
            RouteSummaryCard(waypoints = state.waypoints, onClear = viewModel::clearWaypoints)
        }
    }
}

/** 순정 내비 TBT(Turn-by-Turn) 안내를 흉내 낸 카드 */
@Composable
private fun TurnByTurnCard() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = HaegopaColors.Background.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, HaegopaColors.Outline),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("↱", fontSize = 34.sp, color = HaegopaColors.Route, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("300m", style = MaterialTheme.typography.titleLarge, color = HaegopaColors.TextPrimary)
                Text("앞에서 우회전", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun MapAreaBadge(navi: SdkStatus) {
    Surface(shape = RoundedCornerShape(14.dp), color = HaegopaColors.Background.copy(alpha = 0.8f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
            Text("순정 내비게이션 영역", style = MaterialTheme.typography.labelLarge, color = HaegopaColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(navi.backend.color()),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "지도 Mock · ${navi.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = HaegopaColors.TextSecondary,
                )
            }
        }
    }
}

/** TTS 로 읽은 문장을 자막으로도 보여 준다 (소리를 못 들은 경우 · 시연 영상용) */
@Composable
private fun CaptionBubble(caption: SpeechCaption?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(caption?.seq) {
        if (caption == null) return@LaunchedEffect
        visible = true
        delay(min(14_000L, 2_500L + caption.text.length * 90L))
        visible = false
    }
    AnimatedVisibility(visible = visible && caption != null, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.72f)) {
            Text(
                text = "🔊 ${caption?.text.orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
                color = HaegopaColors.TextPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * 속도계 + PT 데모용 가상 차속 패널.
 * 실차/Pleos 에뮬레이터에서는 VHAL 차속·Virtual Console 기어가 자동 반영되고, 이 버튼은 시연 보조용이다.
 */
@Composable
private fun DemoDrivePanel(
    speedKmh: Int,
    gear: GearPosition,
    speedSource: String,
    onSimulateSpeed: (Int) -> Unit,
    onAddTripMinutes: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = HaegopaColors.Background.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, HaegopaColors.Outline),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$speedKmh",
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (speedKmh > 0) HaegopaColors.Booster else HaegopaColors.TextPrimary,
                )
                Text(
                    text = " km/h",
                    style = MaterialTheme.typography.labelLarge,
                    color = HaegopaColors.TextSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (gear != GearPosition.UNKNOWN) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = gear.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = HaegopaColors.Background,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (gear.impliesMotion) HaegopaColors.Booster else HaegopaColors.TextSecondary)
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "데모 ▾" else "데모 ▸", color = HaegopaColors.TextTertiary)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = "🛠 PT 데모 · 가상 차속 (신호원: $speedSource)",
                        style = MaterialTheme.typography.labelSmall,
                        color = HaegopaColors.TextTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 30, 60, 100).forEach { value ->
                            DemoChip(
                                label = if (value == 0) "정차" else "$value",
                                selected = speedKmh == value,
                                onClick = { onSimulateSpeed(value) },
                            )
                        }
                        DemoChip(label = "+30분", selected = false, onClick = onAddTripMinutes)
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) HaegopaColors.Booster else HaegopaColors.SurfaceHigh,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) HaegopaColors.Background else HaegopaColors.TextPrimary,
            )
        }
    }
}

/** 순정 내비에 주입된 경유지 / 목적지 요약 */
@Composable
private fun RouteSummaryCard(waypoints: List<Waypoint>, onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = HaegopaColors.Background.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, HaegopaColors.Outline),
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("🧭 내 경로", style = MaterialTheme.typography.labelLarge, color = HaegopaColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            val destination = waypoints.firstOrNull { it.kind == WaypointKind.DESTINATION }
            val stops = waypoints.filter { it.kind == WaypointKind.WAYPOINT }.sortedBy { it.order }
            if (stops.isEmpty() && destination == null) {
                Text(
                    text = "경유지 없음 · 카드의 [경유지 추가]로 담아 보세요",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextSecondary,
                )
            }
            stops.forEach { stop ->
                Text(
                    text = "경유 ${stop.order} · ${stop.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.Booster,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "🏁 ${destination?.name ?: "목적지: 회사 (가상 경로)"}",
                style = MaterialTheme.typography.labelMedium,
                color = HaegopaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stops.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("경유지 지우기", color = HaegopaColors.TextTertiary) }
            }
        }
    }
}
