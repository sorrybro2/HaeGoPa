package com.haegopa.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haegopa.app.di.AppContainer
import com.haegopa.app.domain.model.DriveMode
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SearchRadius
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.usecase.AddWaypointUseCase
import com.haegopa.app.domain.usecase.GetNearbySpotsUseCase
import com.haegopa.app.domain.usecase.SetDestinationUseCase
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.domain.util.KoreanJosa
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SpeechCaption
import com.haegopa.app.sdk.SpeechGateway
import com.haegopa.app.sdk.VehicleGateway
import com.haegopa.app.sdk.WaypointResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 해고파 메인 화면 ViewModel (MVVM · 단방향 데이터 흐름).
 * - 먹·놀·쉬 반경 거리순 무한 피드 / 원터치 [목적지] · [경유지] (순정 내비 NaviHelper)
 * - 차속에 따른 정차·주행 UI 분기 (Driver Distraction) · 주행 중 TTS 음성 안내
 * - 장시간 운전 피로 알림 → 쉬고파
 */
class MainViewModel(
    private val getNearbySpots: GetNearbySpotsUseCase,
    private val addWaypoint: AddWaypointUseCase,
    private val setDestination: SetDestinationUseCase,
    private val naviHelper: NaviHelperGateway,
    private val vehicleManager: VehicleGateway,
    private val speechHelper: SpeechGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val caption: StateFlow<SpeechCaption?> = speechHelper.caption

    private var fatigueAnnounced = false

    init {
        vehicleManager.setSpeedChangeListener { speed -> onSpeedChanged(speed) }
        observeSdk()
        startTripTicker()
        viewModelScope.launch { resolveOriginAndLoad() }
    }

    // ── 초기화 ────────────────────────────────────────────────────────────────

    private fun observeSdk() {
        viewModelScope.launch {
            naviHelper.waypoints.collect { list -> _uiState.update { it.copy(waypoints = list) } }
        }
        viewModelScope.launch {
            vehicleManager.gear.collect { gear -> _uiState.update { it.copy(gear = gear) } }
        }
        viewModelScope.launch {
            combine(
                naviHelper.status,
                vehicleManager.status,
                speechHelper.ttsStatus,
                vehicleManager.speedSource,
            ) { navi, vehicle, tts, source -> SdkSummary(navi, vehicle, tts, source) }
                .collect { summary -> _uiState.update { it.copy(sdk = summary) } }
        }
        viewModelScope.launch {
            naviHelper.events.collect { _messages.emit(it) }
        }
    }

    /** NaviHelper 현재 위치(있으면)를 2km 반경 원점으로 사용, 없으면 가상 위치 */
    private suspend fun resolveOriginAndLoad() {
        updateFeed(_uiState.value.selectedCategory) { it.copy(isLoading = true) }
        val location = withTimeoutOrNull(ORIGIN_WAIT_MS) {
            combine(naviHelper.currentLocation, naviHelper.status) { loc, status -> loc to status.backend }
                .first { (loc, backend) -> loc != null || backend == SdkBackend.MOCK }
                .first
        }
        val origin = location ?: GeoPoint.DEFAULT_MOCK_ORIGIN
        _uiState.update { it.copy(origin = origin, originLabel = originLabel(location != null)) }
        updateFeed(_uiState.value.selectedCategory) { it.copy(isLoading = false) }
        loadNextPage(_uiState.value.selectedCategory)
        refreshMapSpots()
        if (location == null) adoptLateNaviLocation()
    }

    /** 순정 내비 위치가 늦게 도착하면 그 위치로 원점을 옮기고 주변 목록을 다시 불러온다 */
    private fun adoptLateNaviLocation() {
        viewModelScope.launch {
            val late = naviHelper.currentLocation.filterNotNull().first()
            _uiState.update { state ->
                state.copy(
                    origin = late,
                    originLabel = originLabel(true),
                    feeds = SpotCategory.entries.associateWith { FeedState() },
                )
            }
            loadNextPage(_uiState.value.selectedCategory)
            refreshMapSpots()
        }
    }

    private fun originLabel(fromNavi: Boolean): String =
        if (fromNavi) naviHelper.currentAddress.value ?: "Pleos 내비 현재 위치" else "가상 현재 위치(판교)"

    /** [내비 열기] — Pleos 순정 내비 화면으로 전환 */
    fun openNavigation() {
        if (!naviHelper.openNavigationScreen()) {
            _messages.tryEmit("순정 내비가 없는 기기예요 — 해고파 지도의 경로로 확인해 주세요")
        }
    }

    private fun startTripTicker() {
        viewModelScope.launch {
            while (isActive) {
                refreshTripMinutes()
                delay(TRIP_POLL_MS)
            }
        }
    }

    private fun refreshTripMinutes() {
        val minutes = vehicleManager.getTripDurationMinutes()
        val overThreshold = minutes >= FATIGUE_THRESHOLD_MINUTES
        _uiState.update { state ->
            state.copy(
                tripMinutes = minutes,
                fatigueNudgeVisible = overThreshold && !state.fatigueNudgeDismissed,
            )
        }
        if (overThreshold && !fatigueAnnounced) {
            fatigueAnnounced = true
            speak("${minutes / 60}시간 넘게 운전 중이에요. 쉬고파에서 가까운 쉴 곳을 찾아 잠깐 쉬어 가세요.")
        }
    }

    // ── 먹·놀·쉬 피드 ─────────────────────────────────────────────────────────

    private var mapSpotsJob: Job? = null

    /** 지도 핀은 목록 무한 스크롤과 따로, 반경 안 스팟을 한 번에 불러온다 (반경을 넓히면 먼 스팟도 바로 보이도록) */
    private fun refreshMapSpots() {
        val state = _uiState.value
        val category = state.selectedCategory
        val origin = state.origin
        val radius = state.radiusMeters
        mapSpotsJob?.cancel()
        mapSpotsJob = viewModelScope.launch {
            runCatching { getNearbySpots(category, origin, page = 0, pageSize = MAP_PIN_LIMIT, radiusMeters = radius) }
                .onSuccess { page ->
                    val now = _uiState.value
                    if (now.selectedCategory == category && now.origin == origin && now.radiusMeters == radius) {
                        _uiState.update { it.copy(mapSpots = page.items) }
                    }
                }
        }
    }

    fun selectCategory(category: SpotCategory) {
        if (_uiState.value.selectedCategory == category) return
        _uiState.update { it.copy(selectedCategory = category) }
        refreshMapSpots()
        val feed = _uiState.value.feeds.getValue(category)
        if (feed.items.isEmpty() && !feed.endReached) {
            loadNextPage(category, announceWhenDriving = true)
        } else if (_uiState.value.isDriving) {
            announceTopSpot()
        }
    }

    /** 무한 스크롤: 리스트 끝에 가까워지면 다음 페이지(가까운 순)를 이어 붙인다 */
    fun loadNextPage(
        category: SpotCategory = _uiState.value.selectedCategory,
        announceWhenDriving: Boolean = false,
    ) {
        val feed = _uiState.value.feeds.getValue(category)
        if (feed.isLoading || feed.endReached) return
        updateFeed(category) { it.copy(isLoading = true, error = null) }
        // 요청 시점의 원점·반경. 응답 전에 바뀌면(반경 변경 · 내비 위치 도착) 낡은 결과는 버린다
        val origin = _uiState.value.origin
        val radius = _uiState.value.radiusMeters
        fun stale() = _uiState.value.origin != origin || _uiState.value.radiusMeters != radius
        viewModelScope.launch {
            runCatching { getNearbySpots(category, origin, feed.nextPage, radiusMeters = radius) }
                .onSuccess { page ->
                    if (stale()) return@onSuccess
                    updateFeed(category) { current ->
                        current.copy(
                            items = current.items + page.items,
                            nextPage = page.page + 1,
                            endReached = !page.hasMore,
                            totalInRadius = page.totalInRadius,
                            isLoading = false,
                        )
                    }
                    val state = _uiState.value
                    if (announceWhenDriving && state.isDriving && state.selectedCategory == category) announceTopSpot()
                }
                .onFailure { error ->
                    if (stale()) return@onFailure
                    updateFeed(category) { it.copy(isLoading = false, error = error.message ?: "불러오기 실패") }
                }
        }
    }

    fun refreshFeed() {
        val category = _uiState.value.selectedCategory
        updateFeed(category) { FeedState() }
        loadNextPage(category)
    }

    // ── 검색 반경 (2 · 5 · 10km · 사용자 지정) ───────────────────────────────────

    fun selectRadius(meters: Int) {
        if (_uiState.value.radiusMeters == meters) return
        _uiState.update { state ->
            state.copy(
                radiusMeters = meters,
                feeds = SpotCategory.entries.associateWith { FeedState() },
            )
        }
        val driving = _uiState.value.isDriving
        if (driving) speak("검색 반경을 ${GeoUtils.spokenRadius(meters)}로 바꿨어요.")
        loadNextPage(_uiState.value.selectedCategory, announceWhenDriving = driving)
        refreshMapSpots()
    }

    /** [직접 설정] — 슬라이더 조작이 필요하므로 정차 중에만 연다 */
    fun openCustomRadius() {
        if (_uiState.value.isDriving) {
            _messages.tryEmit("반경 직접 설정은 정차 중에 할 수 있어요")
            speak("반경 직접 설정은 차를 세운 뒤에 할 수 있어요.")
            return
        }
        _uiState.update { it.copy(radiusDialogOpen = true) }
    }

    fun applyCustomRadius(meters: Int) {
        val normalized = SearchRadius.normalizeCustom(meters)
        _uiState.update { it.copy(radiusDialogOpen = false, customRadiusMeters = normalized) }
        selectRadius(normalized)
    }

    fun closeCustomRadius() {
        _uiState.update { it.copy(radiusDialogOpen = false) }
    }

    private fun updateFeed(category: SpotCategory, transform: (FeedState) -> FeedState) {
        _uiState.update { state ->
            state.copy(feeds = state.feeds + (category to transform(state.feeds.getValue(category))))
        }
    }

    // ── 원터치 목적지 · 경유지 (순정 내비 NaviHelper) ─────────────────────────

    /** [목적지]: 진행 중인 경로가 있어도 이 스팟으로 새로 길안내 */
    fun onSetDestination(spot: Spot) {
        val result = setDestination(spot)
        val to = KoreanJosa.ro(spot.name)
        val message = when (result) {
            is WaypointResult.SetAsDestination -> "'${spot.name}'$to 길안내를 시작했어요"
            WaypointResult.AlreadyDestination -> "이미 '${spot.name}'$to 안내 중이에요"
            is WaypointResult.Failed -> "길안내 요청 실패: ${result.reason}"
            else -> null
        }
        message?.let { _messages.tryEmit(it) }
        if (result.isSuccess && _uiState.value.isDriving) speak("${spot.name}$to 안내를 시작할게요.")
    }

    /** [경유지]: 길안내 중이면 지금 경로에 끼워 넣고, 경로가 없으면 목적지로 안내 */
    fun onAddWaypoint(spot: Spot) {
        val result = addWaypoint(spot)
        val to = KoreanJosa.ro(spot.name)
        val message = when (result) {
            is WaypointResult.AddedAsWaypoint -> buildString {
                append("'${spot.name}' — 순정 내비 경유지 ${result.order}${KoreanJosa.ro(result.order.toString())} 추가했어요")
                if (spot.detourMinutes > 0) append(" (경로 +${spot.detourMinutes}분)")
            }
            is WaypointResult.SetAsDestination -> "진행 중인 경로가 없어 '${spot.name}'$to 길안내를 시작했어요"
            WaypointResult.AlreadyAdded -> "이미 경로에 담긴 스팟이에요"
            WaypointResult.AlreadyDestination -> "이미 '${spot.name}'$to 안내 중이에요"
            is WaypointResult.LimitReached -> "경유지는 최대 ${result.max}곳까지 추가할 수 있어요 (순정 내비 규격)"
            is WaypointResult.Failed -> "경유지 추가 실패: ${result.reason}"
        }
        _messages.tryEmit(message)
        if (result.isSuccess && _uiState.value.isDriving) {
            speak(if (result is WaypointResult.SetAsDestination) "${spot.name}$to 안내를 시작할게요." else "${spot.name}, 경유지로 추가했어요.")
        }
    }

    fun clearWaypoints() {
        naviHelper.clearWaypoints()
        _messages.tryEmit("경유지를 모두 지웠어요")
    }

    // ── 상세 · 소개 듣기(도슨트) · 스트레칭 (정차 중 전용 인터랙션) ─────────────────────────

    fun openDetail(spot: Spot) {
        if (_uiState.value.isDriving) {
            announceSpot(spot)
            return
        }
        _uiState.update { it.copy(detailSpot = spot) }
    }

    fun closeDetail() {
        _uiState.update { it.copy(detailSpot = null) }
    }

    fun playDocent(spot: Spot) {
        val script = spot.docentScript ?: return
        speak("${spot.name} 소개. $script")
    }

    fun openStretching(placeName: String?) {
        if (_uiState.value.isDriving) {
            _messages.tryEmit("스트레칭 가이드는 정차 중에만 열 수 있어요")
            speak("스트레칭 가이드는 차를 세운 뒤에 열 수 있어요.")
            return
        }
        _uiState.update { it.copy(stretchingOpen = true, stretchingPlace = placeName, detailSpot = null) }
    }

    fun closeStretching() {
        _uiState.update { it.copy(stretchingOpen = false, stretchingPlace = null) }
    }

    fun repeatAnnouncement() = announceTopSpot()

    // ── 주행 상태 분기 ────────────────────────────────────────────────────────

    fun simulateSpeed(speedKmh: Int) = vehicleManager.simulateSpeed(speedKmh)

    fun addDemoTripMinutes() {
        vehicleManager.addSimulatedTripMinutes(DEMO_TRIP_STEP_MINUTES)
        refreshTripMinutes()
    }

    private fun onSpeedChanged(speedKmh: Int) {
        val previous = _uiState.value.driveMode
        _uiState.update { state ->
            val driving = speedKmh > 0
            state.copy(
                speedKmh = speedKmh,
                // 주행 시작 시 정차 전용 화면(상세, 스트레칭)을 즉시 닫는다
                detailSpot = if (driving) null else state.detailSpot,
                stretchingOpen = if (driving) false else state.stretchingOpen,
                radiusDialogOpen = if (driving) false else state.radiusDialogOpen,
            )
        }
        val current = _uiState.value.driveMode
        if (previous != current) onDriveModeChanged(current)
    }

    private fun onDriveModeChanged(mode: DriveMode) {
        when (mode) {
            DriveMode.DRIVING -> {
                val state = _uiState.value
                val top = state.currentFeed.items.firstOrNull()
                val script = if (top != null) {
                    "주행 중에는 큰 글씨 목록으로 바꿨어요. 가장 가까운 ${state.selectedCategory.label}는 " +
                        "${GeoUtils.spokenDistance(top.distanceMeters)} 거리의 ${top.name}입니다."
                } else {
                    "주행 중에는 큰 글씨 목록으로 바꿨어요."
                }
                speak(script)
            }
            DriveMode.PARKED -> _messages.tryEmit("정차 중 — 상세 정보 · 소개 듣기 · 스트레칭을 다시 열 수 있어요")
        }
    }

    private fun announceTopSpot() {
        val state = _uiState.value
        val top = state.currentFeed.items.firstOrNull()
        if (top == null) {
            speak("${state.selectedCategory.label}, 반경 ${GeoUtils.spokenRadius(state.radiusMeters)} 안에서 찾고 있어요.")
            return
        }
        announceSpot(top)
    }

    private fun announceSpot(spot: Spot) {
        val detour = if (spot.category == SpotCategory.EAT) " 경로에서 ${spot.detourMinutes}분만 돌아가면 돼요." else ""
        speak("${spot.category.label} 추천, ${GeoUtils.spokenDistance(spot.distanceMeters)} 거리의 ${spot.name}.$detour 목적지나 경유지 버튼을 누르면 순정 내비로 바로 안내해 드려요.")
    }

    // ── 피로 알림 ─────────────────────────────────────────────────────────────

    fun acceptFatigueNudge() {
        _uiState.update { it.copy(fatigueNudgeDismissed = true, fatigueNudgeVisible = false) }
        selectCategory(SpotCategory.REST)
        speak("가까운 쉴 곳을 보여 드릴게요.")
    }

    fun dismissFatigueNudge() {
        _uiState.update { it.copy(fatigueNudgeDismissed = true, fatigueNudgeVisible = false) }
    }

    // ── 공통 ──────────────────────────────────────────────────────────────────

    fun speak(script: String) = speechHelper.speakText(script)

    override fun onCleared() {
        speechHelper.stopSpeaking()
        super.onCleared()
    }

    companion object {
        private const val ORIGIN_WAIT_MS = 3_000L
        private const val MAP_PIN_LIMIT = 60
        private const val TRIP_POLL_MS = 5_000L
        const val FATIGUE_THRESHOLD_MINUTES = 120
        const val DEMO_TRIP_STEP_MINUTES = 30

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    getNearbySpots = container.getNearbySpots,
                    addWaypoint = container.addWaypoint,
                    setDestination = container.setDestination,
                    naviHelper = container.naviHelper,
                    vehicleManager = container.vehicleManager,
                    speechHelper = container.speechHelper,
                )
            }
        }
    }
}
