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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haegopa.app.domain.model.GearPosition
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.presentation.MainUiState
import com.haegopa.app.presentation.MainViewModel
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.SpeechCaption
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind
import com.haegopa.app.ui.theme.HaegopaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.min

/**
 * 가로 화면 분할 구조
 * ┌──────────────────── 63~66% ────────────────────┬──── 34~37% ────┐
 * │ 해고파 주변 스팟 지도 (반경 2km 핀 · 내 경로)      │ 해고파 도크      │
 * │  └ 졸음 방지 모드 ON 이면 위쪽에 싱얼롱 가사 패널   │                 │
 * └────────────────────────────────────────────────┴─────────────────┘
 * Pleos Connect 에서는 이 화면이 차량 패널 오른쪽 메인 영역(순정 내비와 같은 자리)에 열린다.
 * 실제 길안내는 순정 내비가 맡고, 해고파는 NaviHelper 로 경유지를 보낸다. 세로 화면에서는 위(지도) / 아래(도크)로 쌓는다.
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
                val dockFraction = if (maxWidth < 1200.dp) 0.37f else 0.34f
                Row(Modifier.fillMaxSize()) {
                    MapArea(
                        state = state,
                        caption = caption,
                        viewModel = viewModel,
                        modifier = Modifier
                            .weight(1f - dockFraction)
                            .fillMaxHeight(),
                    )
                    HaegopaDockWidget(
                        state = state,
                        actions = dockActions,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier
                            .weight(dockFraction)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    MapArea(
                        state = state,
                        caption = caption,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.40f),
                    )
                    HaegopaDockWidget(
                        state = state,
                        actions = dockActions,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.60f),
                    )
                }
            }
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
    if (state.radiusDialogOpen) {
        CustomRadiusDialog(
            initialMeters = state.customRadiusMeters ?: state.radiusMeters,
            category = state.selectedCategory,
            onApply = viewModel::applyCustomRadius,
            onDismiss = viewModel::closeCustomRadius,
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
private fun MapArea(
    state: MainUiState,
    caption: SpeechCaption?,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clipToBounds()) {
        SpotMap(
            origin = state.origin,
            spots = state.mapSpots.filter { it.category == state.selectedCategory }.ifEmpty { state.currentFeed.items },
            category = state.selectedCategory,
            waypoints = state.waypoints,
            speedKmh = state.speedKmh,
            radiusMeters = state.radiusMeters,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MapTitleChip(
                    category = state.selectedCategory,
                    count = state.currentFeed.totalInRadius,
                    radiusMeters = state.radiusMeters,
                )
                Spacer(Modifier.width(10.dp))
                RadiusSelector(
                    radiusMeters = state.radiusMeters,
                    customRadiusMeters = state.customRadiusMeters,
                    category = state.selectedCategory,
                    onSelect = viewModel::selectRadius,
                    onCustom = viewModel::openCustomRadius,
                )
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
            RouteSummaryCard(
                waypoints = state.waypoints,
                navi = state.sdk.navi,
                onOpenNavi = viewModel::openNavigation,
                onClear = viewModel::clearWaypoints,
            )
        }
    }
}

/** 지도 위 떠 있는 카드 공통 스타일 */
@Composable
private fun FloatingCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = HaegopaColors.Surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, HaegopaColors.Outline.copy(alpha = if (HaegopaColors.IsDark) 1f else 0.6f)),
        shadowElevation = if (HaegopaColors.IsDark) 0.dp else 6.dp,
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun MapTitleChip(category: SpotCategory, count: Int, radiusMeters: Int) {
    FloatingCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            CategoryIconTile(category, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("내 주변 ${category.label}", style = MaterialTheme.typography.titleSmall, color = HaegopaColors.TextPrimary)
                Text(
                    text = GeoUtils.formatRadius(radiusMeters) + if (count > 0) " 안 ${count}곳 · 가까운 순" else " · 찾는 중",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextSecondary,
                )
            }
        }
    }
}

/** 순정 내비와의 연결 상태 (경유지가 어디로 들어가는지 알려 준다) */
private fun naviLinkLabel(navi: SdkStatus): Pair<String, Color> = when (navi.backend) {
    SdkBackend.PLEOS -> "Pleos 내비 연동" to HaegopaColors.Success
    SdkBackend.ANDROID -> "내비 연동" to HaegopaColors.Info
    SdkBackend.MOCK -> "내비 데모 모드" to HaegopaColors.Neutral
    SdkBackend.CONNECTING -> "내비 연결 중" to HaegopaColors.Booster
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
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xF0171B22),
            shadowElevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                HIcon(HaegopaIcons.Wave, tint = HaegopaColors.Booster, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = caption?.text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 속도 + PT 시연용 가상 차속 패널 (기본은 접힘).
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    FloatingCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$speedKmh",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (speedKmh > 0) HaegopaColors.BoosterInk else HaegopaColors.TextPrimary,
                )
                Text(
                    text = " km/h",
                    style = MaterialTheme.typography.labelLarge,
                    color = HaegopaColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (gear != GearPosition.UNKNOWN) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = gear.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (gear.impliesMotion) HaegopaColors.OnAccent else HaegopaColors.TextPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (gear.impliesMotion) HaegopaColors.Booster else HaegopaColors.SurfaceHigher)
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    onClick = { expanded = !expanded },
                    shape = RoundedCornerShape(50),
                    color = HaegopaColors.SurfaceHigher,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        HIcon(HaegopaIcons.Tune, tint = HaegopaColors.TextSecondary, size = 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("시연", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextSecondary)
                        HIcon(
                            if (expanded) HaegopaIcons.ExpandLess else HaegopaIcons.ExpandMore,
                            tint = HaegopaColors.TextSecondary,
                            size = 18.dp,
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "PT 시연용 가상 차속 · 신호: $speedSource",
                        style = MaterialTheme.typography.labelSmall,
                        color = HaegopaColors.TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) HaegopaColors.Booster else HaegopaColors.SurfaceHigher,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) HaegopaColors.OnAccent else HaegopaColors.TextPrimary,
            )
        }
    }
}

/** 순정 내비에 보낸 경유지 / 목적지 요약 + [내비 열기] */
@Composable
private fun RouteSummaryCard(
    waypoints: List<Waypoint>,
    navi: SdkStatus,
    onOpenNavi: () -> Unit,
    onClear: () -> Unit,
) {
    val destination = waypoints.firstOrNull { it.kind == WaypointKind.DESTINATION }
    val stops = waypoints.filter { it.kind == WaypointKind.WAYPOINT }.sortedBy { it.order }
    val naviLinked = navi.backend == SdkBackend.PLEOS
    val (linkLabel, linkDot) = naviLinkLabel(navi)
    FloatingCard(modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIcon(HaegopaIcons.Route, tint = HaegopaColors.Route, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("내 경로", style = MaterialTheme.typography.titleSmall, color = HaegopaColors.TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(linkDot),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(linkLabel, style = MaterialTheme.typography.labelSmall, color = HaegopaColors.TextSecondary)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (stops.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("지우기", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextTertiary)
                    }
                }
                if (naviLinked) {
                    Surface(
                        onClick = onOpenNavi,
                        shape = RoundedCornerShape(50),
                        color = HaegopaColors.Route.copy(alpha = 0.12f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            HIcon(HaegopaIcons.NearMe, tint = HaegopaColors.Route, size = 16.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("내비 열기", style = MaterialTheme.typography.labelLarge, color = HaegopaColors.Route)
                        }
                    }
                } else if (stops.isEmpty()) {
                    Spacer(Modifier.height(40.dp))
                }
            }
            RouteStopRow(marker = { RouteDot(HaegopaColors.Route) }, label = "현재 위치", emphasize = false)
            stops.forEach { stop ->
                RouteStopRow(marker = { RouteNumber(stop.order) }, label = stop.name, emphasize = true)
            }
            RouteStopRow(
                marker = { HIcon(HaegopaIcons.Flag, tint = HaegopaColors.Danger, size = 18.dp) },
                label = destination?.name ?: "목적지 · 회사 (가상 경로)",
                emphasize = destination != null,
            )
            if (stops.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "카드의 [경유지]를 누르면 내비 경로에 바로 들어가요",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun RouteStopRow(marker: @Composable () -> Unit, label: String, emphasize: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp)) { marker() }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = if (emphasize) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            color = if (emphasize) HaegopaColors.TextPrimary else HaegopaColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RouteDot(color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun RouteNumber(order: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(HaegopaColors.Booster),
    ) {
        Text("$order", style = MaterialTheme.typography.labelSmall, color = HaegopaColors.OnAccent)
    }
}
