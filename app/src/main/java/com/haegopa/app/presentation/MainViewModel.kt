package com.haegopa.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haegopa.app.copilot.AnswerSource
import com.haegopa.app.copilot.CopilotAnswer
import com.haegopa.app.copilot.CopilotEngine
import com.haegopa.app.di.AppContainer
import com.haegopa.app.domain.model.DriveMode
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SearchRadius
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.route.CarPosition
import com.haegopa.app.domain.route.RoutePath
import com.haegopa.app.domain.usecase.AddWaypointUseCase
import com.haegopa.app.domain.usecase.GetNearbySpotsUseCase
import com.haegopa.app.domain.usecase.SetDestinationUseCase
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.domain.util.KoreanJosa
import com.haegopa.app.sdk.LocationGateway
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.ceil

/**
 * 해고파 메인 화면 ViewModel (MVVM · 단방향 데이터 흐름).
 * - 먹·놀·쉬 반경 거리순 무한 피드 / 원터치 [목적지] · [경유지] (순정 내비 NaviHelper)
 * - 차속에 따른 정차·주행 UI 분기 (Driver Distraction) · 주행 중 TTS 음성 안내
 * - 장시간 운전 피로 알림 → 쉬고파
 * - 음성 코파일럿 켜기/끄기 · PT 시연 · 해고파 화면에 떠 있는 제안에 버튼으로 답하기 (판단은 [CopilotEngine])
 *
 * 순정 내비가 앞에 있을 때([appVisible] = false)는 목록용 음성 안내를 하지 않는다 — 그때 말하는 건 코파일럿 몫.
 */
class MainViewModel(
    private val getNearbySpots: GetNearbySpotsUseCase,
    private val addWaypoint: AddWaypointUseCase,
    private val setDestination: SetDestinationUseCase,
    private val naviHelper: NaviHelperGateway,
    private val vehicleManager: VehicleGateway,
    private val speechHelper: SpeechGateway,
    private val location: LocationGateway,
    private val copilot: CopilotEngine,
    private val appVisible: StateFlow<Boolean>,
    private val setCopilotEnabled: (Boolean) -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val caption: StateFlow<SpeechCaption?> = speechHelper.caption

    /**
     * 차 위치 · 지금 경로. 시연 주행 중에는 초당 30번 바뀌므로 화면 상태(uiState)와 따로 두고,
     * 지도는 그리기 단계에서만 읽는다 (화면 전체를 다시 구성하지 않게).
     */
    private val tracker = CarTracker()
    val car: StateFlow<CarPosition> = tracker.car
    val route: StateFlow<RoutePath> = tracker.path

    private var fatigueAnnounced = false
    private var originResolved = false
    private var driveJob: Job? = null

    init {
        vehicleManager.setSpeedChangeListener { speed -> onSpeedChanged(speed) }
        observeSdk()
        startTripTicker()
        startLocationPolling()
        viewModelScope.launch { resolveOriginAndLoad() }
    }

    // ── 초기화 ────────────────────────────────────────────────────────────────

    private fun observeSdk() {
        viewModelScope.launch {
            naviHelper.waypoints.collect { list ->
                _uiState.update { it.copy(waypoints = list) }
                tracker.setWaypoints(list)
            }
        }
        // 실제 위치(Pleos FusedLocation → 순정 내비)가 움직이면 차가 그 자리로 간다
        viewModelScope.launch {
            combine(location.location, naviHelper.currentLocation) { fused, navi -> fused ?: navi }
                .filterNotNull()
                .collect { here ->
                    if (originResolved && tracker.onRealLocation(here, System.currentTimeMillis())) maybeRecenter()
                }
        }
        // 순정 내비 주소가 오면 머리말 위치 이름을 다시 정한다 (내비 위치가 차 위치와 가까울 때만 그 주소를 쓴다)
        viewModelScope.launch {
            combine(naviHelper.currentAddress, naviHelper.currentLocation) { address, at -> address to at }
                .collect { if (originResolved) refreshOriginLabel() }
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
                location.status,
            ) { navi, vehicle, tts, source, place -> SdkSummary(navi, vehicle, tts, place, source) }
                .collect { summary -> _uiState.update { it.copy(sdk = summary) } }
        }
        viewModelScope.launch {
            naviHelper.events.collect { _messages.emit(it) }
        }
        copilot.searchRadiusMeters = _uiState.value.radiusMeters
        viewModelScope.launch {
            copilot.state.collect { copilotState -> _uiState.update { it.copy(copilot = copilotState) } }
        }
    }

    /**
     * 출발 위치: ① Pleos FusedLocation(기기 GPS) ② 순정 내비 현재 위치 ③ 가상 위치(판교) 순서로 정한다.
     * 순정 내비 위치는 내비 화면 기준이라 Pleos 에뮬레이터에서는 GPS 를 바꿔도 시흥동에 머문다 → FusedLocation 이 먼저.
     */
    private suspend fun resolveOriginAndLoad() {
        updateFeed(_uiState.value.selectedCategory) { it.copy(isLoading = true) }
        val fused = withTimeoutOrNull(ORIGIN_WAIT_MS) {
            combine(location.location, location.status) { at, status -> at to status.backend }
                .first { (at, backend) -> at != null || backend == SdkBackend.MOCK }
                .first
        }
        val navi = if (fused != null) {
            null
        } else {
            withTimeoutOrNull(ORIGIN_WAIT_MS) {
                combine(naviHelper.currentLocation, naviHelper.status) { at, status -> at to status.backend }
                    .first { (at, backend) -> at != null || backend == SdkBackend.MOCK }
                    .first
            }
        }
        val source = when {
            fused != null -> LocationSource.FUSED
            navi != null -> LocationSource.NAVI
            else -> LocationSource.VIRTUAL
        }
        applyOrigin(fused ?: navi ?: GeoPoint.DEFAULT_MOCK_ORIGIN, source, resetFeeds = false)
        updateFeed(_uiState.value.selectedCategory) { it.copy(isLoading = false) }
        loadNextPage(_uiState.value.selectedCategory)
        refreshMapSpots()
        if (source != LocationSource.FUSED) adoptBetterLocation(source)
    }

    /** 더 믿을 만한 위치가 늦게 오면(내비 → FusedLocation, 가상 → 어느 쪽이든) 그 자리에서 다시 시작한다 */
    private fun adoptBetterLocation(current: LocationSource) {
        viewModelScope.launch {
            val fusedFix = location.location.filterNotNull().map { it to LocationSource.FUSED }
            val naviFix = naviHelper.currentLocation.filterNotNull().map { it to LocationSource.NAVI }
            val (better, source) = if (current == LocationSource.NAVI) fusedFix.first() else merge(fusedFix, naviFix).first()
            applyOrigin(better, source, resetFeeds = true)
            loadNextPage(_uiState.value.selectedCategory)
            refreshMapSpots()
            if (source != LocationSource.FUSED) adoptBetterLocation(source)
        }
    }

    /** 출발점: 지도 기준점 · 가상 스팟 · 차 · 검색 중심을 모두 이 자리에 둔다 */
    private fun applyOrigin(origin: GeoPoint, source: LocationSource, resetFeeds: Boolean) {
        getNearbySpots.anchorAt(origin)
        tracker.reset(origin)
        tracker.setWaypoints(naviHelper.waypoints.value)
        copilot.searchCenter = origin
        originResolved = true
        _uiState.update { state ->
            state.copy(
                origin = origin,
                searchCenter = origin,
                locationSource = source,
                feeds = if (resetFeeds) SpotCategory.entries.associateWith { FeedState() } else state.feeds,
                selectedSpot = if (resetFeeds) null else state.selectedSpot,
            )
        }
        refreshOriginLabel()
    }

    /** 머리말 위치 이름: 내비 주소(내비 위치가 차와 1km 안일 때) → 좌표 → 가상 위치 */
    private fun refreshOriginLabel() {
        val state = _uiState.value
        val here = tracker.car.value.location
        val naviAt = naviHelper.currentLocation.value
        val address = naviHelper.currentAddress.value
        val label = when {
            state.locationSource == LocationSource.VIRTUAL -> "가상 현재 위치(판교)"
            address != null && naviAt != null && GeoUtils.distanceMeters(naviAt, here) <= ADDRESS_MATCH_METERS -> address
            state.locationSource == LocationSource.NAVI -> address ?: "Pleos 내비 현재 위치"
            else -> String.format(Locale.US, "현재 위치 %.4f, %.4f", here.latitude, here.longitude)
        }
        if (label != state.originLabel) _uiState.update { it.copy(originLabel = label) }
    }

    /** NaviHelper 는 위치를 한 번 묻고 한 번 답한다 → 달리는 동안은 자주, 서 있을 때는 가끔 다시 묻는다 */
    private fun startLocationPolling() {
        viewModelScope.launch {
            while (isActive) {
                delay(if (_uiState.value.isDriving) LOCATION_POLL_DRIVING_MS else LOCATION_POLL_PARKED_MS)
                naviHelper.requestCurrentLocation()
            }
        }
    }

    // ── 차 따라가기 · 검색 중심 ────────────────────────────────────────────────

    /**
     * 차속이 있는 동안 화면 프레임마다 차를 움직인다. 실제 위치가 움직이고 있으면 그걸 따르고(시연 주행 쉼),
     * 아니면 내 경로를 따라 [DEMO_TIME_LAPSE] 배 빨리 감아 달린다 (실제 30km/h 로는 화면에서 거의 안 움직인다).
     */
    private fun startDriveLoop() {
        if (driveJob?.isActive == true) return
        driveJob = viewModelScope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(FRAME_MS)
                val now = System.nanoTime()
                val seconds = (now - last) / 1_000_000_000.0
                last = now
                val speed = _uiState.value.speedKmh
                if (speed > 0 && originResolved && tracker.shouldSimulate(System.currentTimeMillis())) {
                    tracker.advance(speed / 3.6 * DEMO_TIME_LAPSE * seconds)
                    maybeRecenter()
                }
            }
        }
    }

    /** 차가 검색 중심에서 충분히 멀어지면 차 위치를 새 중심으로 — 반경 원 · 목록 · 지도 핀이 차를 따라온다 */
    private fun maybeRecenter() {
        val state = _uiState.value
        val here = tracker.car.value.location
        val threshold = maxOf(RECENTER_MIN_METERS, state.radiusMeters * RECENTER_RADIUS_RATIO)
        if (GeoUtils.distanceMeters(state.searchCenter, here) < threshold) return
        _uiState.update { it.copy(searchCenter = here) }
        copilot.searchCenter = here
        refreshMapSpots()
        reloadFeedQuietly()
        refreshOriginLabel()
    }

    private var recenterJob: Job? = null

    /**
     * 새 검색 중심으로 지금 탭의 목록을 다시 받는다. 로딩 표시 없이, 받아 온 뒤 한 번에 바꾼다
     * (달리는 동안 목록이 깜빡이지 않게). 다른 탭은 비워 두었다가 열 때 새 중심으로 받는다.
     */
    private fun reloadFeedQuietly() {
        val state = _uiState.value
        val category = state.selectedCategory
        val center = state.searchCenter
        val radius = state.radiusMeters
        val pages = maxOf(1, ceil(state.feeds.getValue(category).items.size / PAGE.toDouble()).toInt())
        _uiState.update { s ->
            s.copy(feeds = s.feeds.mapValues { (c, feed) -> if (c == category) feed else FeedState() })
        }
        recenterJob?.cancel()
        recenterJob = viewModelScope.launch {
            runCatching { getNearbySpots(category, center, page = 0, pageSize = pages * PAGE, radiusMeters = radius) }
                .onSuccess { page ->
                    val now = _uiState.value
                    if (now.searchCenter != center || now.radiusMeters != radius) return@onSuccess
                    updateFeed(category) {
                        FeedState(
                            items = page.items,
                            nextPage = pages,
                            endReached = !page.hasMore,
                            totalInRadius = page.totalInRadius,
                        )
                    }
                }
        }
    }

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
        // 순정 내비가 앞에 있을 때는 코파일럿이 쉬고파를 직접 권하므로, 화면이 보일 때만 안내한다
        if (overThreshold && !fatigueAnnounced && appVisible.value) {
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
        val center = state.searchCenter
        val radius = state.radiusMeters
        mapSpotsJob?.cancel()
        mapSpotsJob = viewModelScope.launch {
            runCatching { getNearbySpots(category, center, page = 0, pageSize = MAP_PIN_LIMIT, radiusMeters = radius) }
                .onSuccess { page ->
                    val now = _uiState.value
                    if (now.selectedCategory == category && now.searchCenter == center && now.radiusMeters == radius) {
                        _uiState.update { it.copy(mapSpots = page.items) }
                    }
                }
        }
    }

    fun selectCategory(category: SpotCategory) {
        if (_uiState.value.selectedCategory == category) return
        _uiState.update { it.copy(selectedCategory = category, selectedSpot = null) }
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
        // 요청 시점의 검색 중심(차 위치)·반경. 응답 전에 바뀌면(반경 변경 · 차 이동 · 내비 위치 도착) 낡은 결과는 버린다
        val center = _uiState.value.searchCenter
        val radius = _uiState.value.radiusMeters
        fun stale() = _uiState.value.searchCenter != center || _uiState.value.radiusMeters != radius
        viewModelScope.launch {
            runCatching { getNearbySpots(category, center, feed.nextPage, radiusMeters = radius) }
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
                selectedSpot = null,
            )
        }
        copilot.searchRadiusMeters = meters
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

    // ── 스팟 선택 · 소개 듣기(도슨트) · 스트레칭 ──────────────────────────────────

    /**
     * 목록 카드 · 지도 핀 터치: 지도가 그 스팟으로 이동하고, 정차 중이면 도크에 상세 패널이 밀려 들어온다.
     * 주행 중에는 상세 대신 음성으로 읽어 준다 (지도 이동은 그대로 — 동승자가 위치를 볼 수 있게).
     */
    fun selectSpot(spot: Spot) {
        _uiState.update { it.copy(selectedSpot = spot) }
        if (_uiState.value.isDriving) announceSpot(spot)
    }

    /** 상세 패널 [목록] · 지도 빈 곳 터치 → 선택 해제, 지도는 전체 보기로 돌아간다 */
    fun clearSelection() {
        _uiState.update { it.copy(selectedSpot = null) }
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
        _uiState.update { it.copy(stretchingOpen = true, stretchingPlace = placeName) }
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
            val modeChanged = DriveMode.fromSpeed(speedKmh) != state.driveMode
            state.copy(
                speedKmh = speedKmh,
                // 주행이 시작되거나 멈추면 고른 스팟을 풀고 지도 전체 보기로 (상세 패널도 닫힌다)
                selectedSpot = if (modeChanged) null else state.selectedSpot,
                // 주행 시작 시 정차 전용 화면(스트레칭, 반경 설정)을 즉시 닫는다
                stretchingOpen = if (driving) false else state.stretchingOpen,
                radiusDialogOpen = if (driving) false else state.radiusDialogOpen,
            )
        }
        val current = _uiState.value.driveMode
        // 달리는 동안만 프레임마다 차를 움직인다 (서면 그 자리에 선다)
        if (speedKmh > 0) startDriveLoop() else driveJob?.cancel()
        if (previous != current) onDriveModeChanged(current)
    }

    private fun onDriveModeChanged(mode: DriveMode) {
        // 화면이 가려져 있으면(순정 내비가 앞) 목록 전환 안내는 의미가 없다
        if (!appVisible.value) return
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

    // ── 음성 코파일럿 ─────────────────────────────────────────────────────────

    fun toggleCopilot() {
        val enabled = !_uiState.value.copilot.enabled
        setCopilotEnabled(enabled)
        _messages.tryEmit(
            if (enabled) "음성 코파일럿을 켰어요 — 순정 내비 화면에서도 쉴 곳 · 맛집을 말로 권해 드려요" else "음성 코파일럿을 껐어요",
        )
    }

    /** 해고파 화면에 떠 있는 제안 배너의 [경유지 추가] · [괜찮아요] */
    fun answerCopilot(answer: CopilotAnswer) = copilot.answerActive(answer, AnswerSource.BUTTON)

    /**
     * PT 시연: 지금 보고 있는 탭(먹고파 · 놀고파 · 쉬고파)으로 몇 초 뒤 코파일럿이 말을 걸게 하고, 순정 내비를 앞으로 띄운다.
     * 순정 내비가 없는 기기에서는 해고파 화면의 배너로 같은 흐름을 보여 준다.
     */
    fun demoCopilot() {
        if (!_uiState.value.copilot.enabled) setCopilotEnabled(true)
        copilot.scheduleDemo(_uiState.value.selectedCategory, COPILOT_DEMO_DELAY_MS)
        val seconds = COPILOT_DEMO_DELAY_MS / 1000
        val opened = naviHelper.openNavigationScreen()
        _messages.tryEmit(
            if (opened) "${seconds}초 뒤 코파일럿이 순정 내비 위에서 말을 걸어요" else "${seconds}초 뒤 코파일럿이 말을 걸어요",
        )
    }

    // ── 공통 ──────────────────────────────────────────────────────────────────

    fun speak(script: String) = speechHelper.speakText(script)

    override fun onCleared() {
        speechHelper.stopSpeaking()
        super.onCleared()
    }

    companion object {
        private const val ORIGIN_WAIT_MS = 3_000L

        /** 순정 내비 주소를 차 위치 이름으로 쓸 수 있는 거리 (내비 위치와 차 위치가 이만큼 가까울 때) */
        private const val ADDRESS_MATCH_METERS = 1_000.0
        private const val MAP_PIN_LIMIT = 60
        private const val TRIP_POLL_MS = 5_000L
        private const val PAGE = GetNearbySpotsUseCase.PAGE_SIZE
        private const val FRAME_MS = 33L
        private const val LOCATION_POLL_DRIVING_MS = 3_000L
        private const val LOCATION_POLL_PARKED_MS = 15_000L

        /** 시연 주행 빨리감기 배율: 30km/h 로 1블록(420m)을 약 3초에 지난다 */
        const val DEMO_TIME_LAPSE = 16.0

        /** 차가 검색 중심에서 이만큼(반경의 15%, 최소 300m) 멀어지면 목록 · 핀을 차 위치로 다시 찾는다 */
        private const val RECENTER_MIN_METERS = 300.0
        private const val RECENTER_RADIUS_RATIO = 0.15
        const val FATIGUE_THRESHOLD_MINUTES = 120
        const val DEMO_TRIP_STEP_MINUTES = 30
        const val COPILOT_DEMO_DELAY_MS = 6_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    getNearbySpots = container.getNearbySpots,
                    addWaypoint = container.addWaypoint,
                    setDestination = container.setDestination,
                    naviHelper = container.naviHelper,
                    vehicleManager = container.vehicleManager,
                    speechHelper = container.speechHelper,
                    location = container.location,
                    copilot = container.copilot,
                    appVisible = container.appVisible,
                    setCopilotEnabled = container::setCopilotEnabled,
                )
            }
        }
    }
}
