package com.haegopa.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haegopa.app.audio.MrSynthPlayer
import com.haegopa.app.data.QuizBank
import com.haegopa.app.data.SingAlongCatalog
import com.haegopa.app.di.AppContainer
import com.haegopa.app.domain.model.DriveMode
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.QuizAnswerParser
import com.haegopa.app.domain.model.QuizQuestion
import com.haegopa.app.domain.model.SearchRadius
import com.haegopa.app.domain.model.SingAlongSong
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.usecase.AddWaypointUseCase
import com.haegopa.app.domain.usecase.GetNearbySpotsUseCase
import com.haegopa.app.domain.util.GeoUtils
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
 * - 먹·놀·쉬 2km 무한 피드 / 원터치 경유지 주입
 * - 차속에 따른 정차·주행 UI 분기 (Driver Distraction)
 * - 졸음 방지 모드: 합성 MR + 싱얼롱 가사 + TTS/STT 1분 상식 퀴즈
 */
class MainViewModel(
    private val getNearbySpots: GetNearbySpotsUseCase,
    private val addWaypoint: AddWaypointUseCase,
    private val naviHelper: NaviHelperGateway,
    private val vehicleManager: VehicleGateway,
    private val speechHelper: SpeechGateway,
    private val mrPlayer: MrSynthPlayer,
    private val songs: List<SingAlongSong> = SingAlongCatalog.songs,
    private val quizBank: List<QuizQuestion> = QuizBank.questions,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 가사 싱크용 재생 위치 — 자주 바뀌므로 uiState 와 분리해 SingAlong 영역만 재구성한다 */
    val mrPositionMs: StateFlow<Long> = mrPlayer.positionMs

    /** 오디오 출력 불가 시 가사만 흐르는 무음 모드 여부 */
    val mrSilent: StateFlow<Boolean> = mrPlayer.silentMode
    val caption: StateFlow<SpeechCaption?> = speechHelper.caption

    private var quizTimerJob: Job? = null
    private var quizStepJob: Job? = null
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
                speechHelper.sttStatus,
                vehicleManager.speedSource,
            ) { navi, vehicle, tts, stt, source -> SdkSummary(navi, vehicle, tts, stt, source) }
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
            speak("${minutes / 60}시간 넘게 운전 중이에요. 가까운 쉼터에서 잠깐 쉬어 가세요.")
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

    // ── 원터치 경유지 주입 ─────────────────────────────────────────────────────

    fun onAddWaypoint(spot: Spot) {
        val result = addWaypoint(spot)
        val message = when (result) {
            is WaypointResult.AddedAsWaypoint -> buildString {
                append("'${spot.name}' — 순정 내비 경유지 ${result.order}(으)로 추가했어요")
                if (spot.detourMinutes > 0) append(" (경로 +${spot.detourMinutes}분)")
            }
            is WaypointResult.SetAsDestination -> "진행 중인 경로가 없어 '${spot.name}'(으)로 길안내를 시작했어요"
            WaypointResult.AlreadyAdded -> "이미 경로에 담긴 스팟이에요"
            is WaypointResult.LimitReached -> "경유지는 최대 ${result.max}곳까지 추가할 수 있어요 (순정 내비 규격)"
            is WaypointResult.Failed -> "경유지 추가 실패: ${result.reason}"
        }
        _messages.tryEmit(message)
        if (result.isSuccess && _uiState.value.isDriving) speak("${spot.name}, 경로에 추가했어요.")
    }

    fun clearWaypoints() {
        naviHelper.clearWaypoints()
        _messages.tryEmit("경유지를 모두 지웠어요")
    }

    // ── 상세 · 도슨트 · 스트레칭 (정차 중 전용 인터랙션) ─────────────────────────

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
        speak("${spot.name} 도슨트. $script")
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
                if (state.quiz == null) speak(script)
            }
            DriveMode.PARKED -> _messages.tryEmit("정차 중 — 상세 정보 · 도슨트 · 스트레칭을 다시 열 수 있어요")
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
        speak("${spot.category.label} 추천, ${GeoUtils.spokenDistance(spot.distanceMeters)} 거리의 ${spot.name}.$detour 경유지 추가 버튼을 누르면 내비에 바로 담아 드려요.")
    }

    // ── 졸음 방지 모드 (MR + 싱얼롱 + 퀴즈) ─────────────────────────────────────

    fun setBoosterEnabled(enabled: Boolean) {
        val booster = _uiState.value.booster
        if (enabled == booster.enabled) return
        if (enabled) {
            val song = songs[booster.songIndex % songs.size]
            mrPlayer.start(song)
            _uiState.update { it.copy(booster = it.booster.copy(enabled = true, song = song)) }
            speak("졸음 방지 모드를 켰어요. 보컬을 뺀 노래방 반주로 바꿨어요. 화면 가사를 보며 크게 따라 불러 보세요!")
        } else {
            closeQuiz()
            mrPlayer.stop()
            // song 은 남겨 두어 패널이 사라지는 애니메이션 동안에도 가사가 보이게 한다
            _uiState.update { it.copy(booster = it.booster.copy(enabled = false)) }
            speak("졸음 방지 모드를 껐어요. 오늘도 안전 운전하세요!")
        }
    }

    fun nextSong() {
        val booster = _uiState.value.booster
        if (!booster.enabled) return
        val nextIndex = (booster.songIndex + 1) % songs.size
        val song = songs[nextIndex]
        mrPlayer.start(song)
        _uiState.update { it.copy(booster = it.booster.copy(songIndex = nextIndex, song = song)) }
        _messages.tryEmit("다음 곡: ${song.title}")
    }

    fun startQuiz() {
        closeQuiz()
        mrPlayer.setDucked(true)
        _uiState.update { it.copy(quiz = QuizUiState(deck = quizBank.shuffled())) }
        askCurrentQuestion(intro = true)
        quizTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val quiz = _uiState.value.quiz ?: break
                if (quiz.phase == QuizPhase.FINISHED) break
                val remaining = quiz.remainingSeconds - 1
                if (remaining <= 0) {
                    finishQuiz()
                    break
                }
                _uiState.update { it.copy(quiz = it.quiz?.copy(remainingSeconds = remaining)) }
            }
        }
    }

    /** 🎙️ 말로 답하기 → MockSpeechHelper.startVoiceRecognition (Pleos STT / Android STT / 시뮬레이션) */
    fun answerByVoice() {
        val quiz = _uiState.value.quiz ?: return
        if (quiz.phase != QuizPhase.ASKING) return
        speechHelper.stopSpeaking()
        _uiState.update { it.copy(quiz = quiz.copy(phase = QuizPhase.LISTENING, heard = null)) }
        speechHelper.startVoiceRecognition { command -> onVoiceAnswer(command) }
    }

    /** 음성 응답 시뮬레이션 버튼: STT 결과 문자열("2번!")을 그대로 주입한다 */
    fun simulateVoiceAnswer(choiceIndex: Int) {
        val quiz = _uiState.value.quiz ?: return
        if (quiz.phase == QuizPhase.ANSWERED || quiz.phase == QuizPhase.FINISHED) return
        onVoiceAnswer("${choiceIndex + 1}번!")
    }

    private fun onVoiceAnswer(command: String) {
        val quiz = _uiState.value.quiz ?: return
        if (quiz.phase == QuizPhase.ANSWERED || quiz.phase == QuizPhase.FINISHED) return

        if (command.isBlank()) {
            _uiState.update { it.copy(quiz = quiz.copy(phase = QuizPhase.ASKING, heard = "(인식된 말이 없어요)")) }
            _messages.tryEmit("음성 인식 엔진을 쓸 수 없어요 — '음성 응답 시뮬레이션' 버튼으로 답해 보세요")
            return
        }
        val choice = QuizAnswerParser.parse(command, quiz.current.choices)
        if (choice == null) {
            _uiState.update { it.copy(quiz = quiz.copy(phase = QuizPhase.ASKING, heard = command)) }
            speak("잘 못 알아들었어요. 1번, 2번, 3번 중에서 말씀해 주세요.")
            return
        }
        val correct = choice == quiz.current.answerIndex
        val updated = quiz.copy(
            phase = QuizPhase.ANSWERED,
            heard = command,
            selectedIndex = choice,
            lastCorrect = correct,
            score = quiz.score + if (correct) 1 else 0,
            answered = quiz.answered + 1,
        )
        _uiState.update { it.copy(quiz = updated) }
        val answer = quiz.current
        val feedback = if (correct) {
            "딩동댕! 정답이에요. "
        } else {
            "아쉬워요! 정답은 ${answer.answerIndex + 1}번 ${answer.choices[answer.answerIndex]}. "
        }
        speak(feedback + answer.explanation)

        quizStepJob?.cancel()
        quizStepJob = viewModelScope.launch {
            delay(NEXT_QUESTION_DELAY_MS)
            val latest = _uiState.value.quiz ?: return@launch
            if (latest.phase != QuizPhase.ANSWERED) return@launch
            if (latest.index + 1 >= latest.deck.size) {
                finishQuiz()
                return@launch
            }
            _uiState.update {
                it.copy(
                    quiz = latest.copy(
                        index = latest.index + 1,
                        phase = QuizPhase.ASKING,
                        heard = null,
                        selectedIndex = null,
                        lastCorrect = null,
                    ),
                )
            }
            askCurrentQuestion(intro = false)
        }
    }

    private fun askCurrentQuestion(intro: Boolean) {
        val quiz = _uiState.value.quiz ?: return
        val question = quiz.current
        val choices = question.choices.mapIndexed { i, c -> "${i + 1}번 $c" }.joinToString(", ")
        val prefix = if (intro) "졸음을 깨우는 1분 상식 퀴즈! " else ""
        speak("$prefix${quiz.index + 1}번째 문제. ${question.question} $choices.")
    }

    private fun finishQuiz() {
        quizStepJob?.cancel()
        val quiz = _uiState.value.quiz ?: return
        if (quiz.phase == QuizPhase.FINISHED) return
        _uiState.update { it.copy(quiz = quiz.copy(phase = QuizPhase.FINISHED, remainingSeconds = 0)) }
        speak("1분 퀴즈 끝! ${quiz.answered}문제 중 ${quiz.score}문제를 맞히셨어요. 잠이 좀 깨셨나요?")
    }

    fun closeQuiz() {
        quizTimerJob?.cancel()
        quizStepJob?.cancel()
        quizTimerJob = null
        quizStepJob = null
        if (_uiState.value.quiz != null) {
            _uiState.update { it.copy(quiz = null) }
            speechHelper.stopSpeaking()
        }
        mrPlayer.setDucked(false)
    }

    // ── 피로 알림 ─────────────────────────────────────────────────────────────

    fun acceptFatigueNudge() {
        _uiState.update { it.copy(fatigueNudgeDismissed = true, fatigueNudgeVisible = false) }
        selectCategory(SpotCategory.REST)
        speak("가까운 쉼터를 보여 드릴게요.")
    }

    fun dismissFatigueNudge() {
        _uiState.update { it.copy(fatigueNudgeDismissed = true, fatigueNudgeVisible = false) }
    }

    // ── 공통 ──────────────────────────────────────────────────────────────────

    fun speak(script: String) = speechHelper.speakText(script)

    override fun onCleared() {
        mrPlayer.stop()
        speechHelper.stopSpeaking()
        super.onCleared()
    }

    companion object {
        private const val ORIGIN_WAIT_MS = 3_000L
        private const val MAP_PIN_LIMIT = 60
        private const val TRIP_POLL_MS = 5_000L
        private const val NEXT_QUESTION_DELAY_MS = 5_000L
        const val FATIGUE_THRESHOLD_MINUTES = 120
        const val DEMO_TRIP_STEP_MINUTES = 30

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    getNearbySpots = container.getNearbySpots,
                    addWaypoint = container.addWaypoint,
                    naviHelper = container.naviHelper,
                    vehicleManager = container.vehicleManager,
                    speechHelper = container.speechHelper,
                    mrPlayer = container.mrPlayer,
                )
            }
        }
    }
}
