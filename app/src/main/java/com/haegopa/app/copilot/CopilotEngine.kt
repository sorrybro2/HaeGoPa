package com.haegopa.app.copilot

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SearchRadius
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.usecase.AddWaypointUseCase
import com.haegopa.app.domain.usecase.GetNearbySpotsUseCase
import com.haegopa.app.domain.usecase.SetDestinationUseCase
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.SpeechGateway
import com.haegopa.app.sdk.VehicleGateway
import com.haegopa.app.sdk.WaypointKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 백그라운드 음성 코파일럿 (이슈 #2 · 최종 비전 2단계).
 *
 * 순정 내비 화면을 띄워 둔 채 해고파가 뒤에서 상황을 보다가, 말을 걸 때가 되면
 *   1. Pleos TTS 로 묻고   — "점심 시간이에요. 경로에서 3분만 돌아가면 할매 순두부가 있어요. 경유지로 넣을까요?"
 *   2. Pleos STT 로 듣고   — "응" / "목적지로" / "아니"
 *   3. NaviHelper 로 보낸다 — addWaypoint(경유지) · requestRoute(목적지) → 순정 내비 경로가 바로 바뀜
 * 말로 못 하면 헤드업 알림 · 해고파 배너의 [경유지 추가] · [괜찮아요] 버튼으로 고른다.
 *
 * 언제 말을 거는지는 [CopilotPolicy], 무슨 말을 하는지는 [CopilotScripts] 가 정한다.
 * 모든 메서드는 메인 스레드에서 불린다 (SDK 콜백은 게이트웨이가 메인으로 넘겨 준다).
 */
class CopilotEngine(
    private val scope: CoroutineScope,
    private val getNearbySpots: GetNearbySpotsUseCase,
    private val addWaypoint: AddWaypointUseCase,
    private val setDestination: SetDestinationUseCase,
    private val naviHelper: NaviHelperGateway,
    private val vehicle: VehicleGateway,
    private val speech: SpeechGateway,
    private val appVisible: StateFlow<Boolean>,
    private val settings: CopilotSettings,
    private val clock: CopilotClock = CopilotClock.System,
    private val config: CopilotConfig = CopilotConfig(),
) {

    private val _state = MutableStateFlow(CopilotState(enabled = settings.enabled))
    val state: StateFlow<CopilotState> = _state.asStateFlow()

    /** 헤드업 알림 (Android). 테스트에서는 비워 둔다 */
    var presenter: CopilotPresenter? = null

    /** 해고파 화면에서 고른 검색 반경을 그대로 쓴다 */
    var searchRadiusMeters: Int = SearchRadius.DEFAULT_METERS

    /** 검색 중심 = 차 위치 (해고파 화면이 차를 따라 옮겨 준다). 없으면 순정 내비 위치 */
    var searchCenter: GeoPoint? = null

    private var loopJob: Job? = null
    private var expiryJob: Job? = null
    private var demoJob: Job? = null
    private var memory = TriggerMemory()

    /** 이번에 거절한 스팟 — 앱을 다시 켜기 전까지 다시 권하지 않는다 */
    private val declined = mutableSetOf<String>()
    private var seq = 0L
    private var hintGiven = false
    private var reprompted = false

    init {
        if (settings.enabled) startLoop()
    }

    // ── 켜기 · 끄기 · 시연 ─────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) {
        settings.enabled = enabled
        _state.update { it.copy(enabled = enabled) }
        if (enabled) {
            startLoop()
        } else {
            loopJob?.cancel()
            loopJob = null
            demoJob?.cancel()
            demoJob = null
            _state.value.active?.let { active ->
                if (_state.value.phase == CopilotPhase.LISTENING) speech.stopListening()
                speech.stopSpeaking()
                finish(active, log = null)
            }
        }
    }

    /** PT 시연: [delayMillis] 뒤 지금 조건과 상관없이 [category] 를 하나 권한다 (그사이 순정 내비를 앞으로 띄운다) */
    fun scheduleDemo(category: SpotCategory, delayMillis: Long) {
        if (!_state.value.enabled) setEnabled(true)
        demoJob?.cancel()
        demoJob = scope.launch {
            delay(delayMillis)
            _state.value.active?.let { finish(it, log = null) }
            present(CopilotDecision(CopilotReason.DEMO, category, tripMinutes = vehicle.getTripDurationMinutes()))
        }
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                delay(config.tickMillis)
                try {
                    evaluate()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // 한 번 실패해도 다음 틱에 다시 본다
                }
            }
        }
    }

    // ── 말을 걸지 판단 ─────────────────────────────────────────────────────────

    /** 조건을 한 번 본다 (루프가 [CopilotConfig.tickMillis] 마다 부른다) */
    suspend fun evaluate() {
        val current = _state.value
        if (!current.enabled || current.active != null) return
        val waypoints = naviHelper.waypoints.value
        val input = TriggerInput(
            nowMillis = clock.nowMillis(),
            minuteOfDay = clock.minuteOfDay(),
            dayKey = clock.dayKey(),
            tripMinutes = vehicle.getTripDurationMinutes(),
            driving = vehicle.speedKmh.value > 0,
            routeActive = waypoints.any { it.kind == WaypointKind.DESTINATION },
            waypointCount = waypoints.count { it.kind == WaypointKind.WAYPOINT },
            appVisible = appVisible.value,
        )
        val (decision, updated) = CopilotPolicy.decide(input, memory, config) ?: return
        memory = updated
        present(decision)
    }

    private suspend fun present(decision: CopilotDecision): Boolean {
        if (_state.value.active != null) return false
        val origin = searchCenter ?: naviHelper.currentLocation.value ?: GeoPoint.DEFAULT_MOCK_ORIGIN
        val candidates = try {
            getNearbySpots(decision.category, origin, page = 0, pageSize = CANDIDATE_COUNT, radiusMeters = searchRadiusMeters).items
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        // 후보를 불러오는 사이 꺼졌거나 다른 제안이 시작됐으면 그만둔다
        val now = _state.value
        if (!now.enabled || now.active != null) return false

        val waypoints = naviHelper.waypoints.value
        val routeActive = waypoints.any { it.kind == WaypointKind.DESTINATION }
        val spot = CopilotPolicy.pickSpot(
            category = decision.category,
            candidates = candidates,
            exclude = declined + waypoints.map { it.name },
            reason = decision.reason,
        ) ?: return false

        val withHint = !hintGiven && speech.canListen
        if (withHint) hintGiven = true
        val suggestion = CopilotSuggestion(
            id = ++seq,
            spot = spot,
            reason = decision.reason,
            asWaypoint = routeActive,
            script = CopilotScripts.question(decision, spot, routeActive, withHint),
        )
        reprompted = false
        memory = memory.copy(lastSuggestionAt = clock.nowMillis())
        setPhase(suggestion, CopilotPhase.SPEAKING, heard = null)
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(config.responseTimeoutMillis)
            expire(suggestion.id)
        }
        speech.speakThen(suggestion.script) { onQuestionSpoken(suggestion.id) }
        return true
    }

    // ── 묻고 · 듣고 · 답하기 ───────────────────────────────────────────────────

    private fun onQuestionSpoken(id: Long) {
        val suggestion = active(id) ?: return
        if (_state.value.phase != CopilotPhase.SPEAKING) return
        val heard = _state.value.heard
        if (speech.canListen) {
            setPhase(suggestion, CopilotPhase.LISTENING, heard)
            speech.startVoiceRecognition { text -> onHeard(id, text) }
        } else {
            setPhase(suggestion, CopilotPhase.WAITING_TAP, heard)
        }
    }

    private fun onHeard(id: Long, text: String) {
        val suggestion = active(id) ?: return
        if (_state.value.phase != CopilotPhase.LISTENING) return
        val heard = text.trim().ifEmpty { null }
        val answer = VoiceReplyParser.parse(text)
        when {
            answer != null -> answer(id, answer, AnswerSource.VOICE, heard)
            // 뭔가 말했는데 못 알아들음 → 한 번만 다시 묻는다
            heard != null && !reprompted -> {
                reprompted = true
                setPhase(suggestion, CopilotPhase.SPEAKING, heard)
                speech.speakThen(CopilotScripts.REPROMPT) { onQuestionSpoken(id) }
            }
            // 조용하거나 두 번째도 모름 → 다시 말 걸지 않고 버튼을 기다린다
            else -> setPhase(suggestion, CopilotPhase.WAITING_TAP, heard)
        }
    }

    /** 대답 처리 — 음성(STT) · 헤드업 알림 버튼 · 해고파 배너 버튼이 모두 여기로 온다 */
    fun answer(suggestionId: Long, answer: CopilotAnswer, source: AnswerSource, heard: String? = null) {
        val suggestion = active(suggestionId) ?: return
        if (_state.value.phase == CopilotPhase.LISTENING) speech.stopListening()
        val result = when (answer) {
            // "경유지로 넣을까요?" → 경유지 / 길안내가 없을 때 물은 "그쪽으로 안내할까요?" → 목적지
            CopilotAnswer.ADD -> if (suggestion.asWaypoint) addWaypoint(suggestion.spot) else setDestination(suggestion.spot)
            CopilotAnswer.DESTINATION -> setDestination(suggestion.spot)
            CopilotAnswer.DECLINE -> null
        }
        memory = if (answer == CopilotAnswer.DECLINE) {
            declined += suggestion.spot.name
            memory.copy(consecutiveDeclines = memory.consecutiveDeclines + 1)
        } else {
            memory.copy(consecutiveDeclines = 0)
        }
        finish(
            suggestion,
            CopilotLogEntry(clock.nowMillis(), suggestion.spot.name, CopilotScripts.logText(answer, source, heard, result)),
        )
        speech.speakText(if (result != null) CopilotScripts.confirmation(suggestion.spot, result) else CopilotScripts.DECLINED)
    }

    /** 지금 떠 있는 제안에 답한다 (해고파 배너 버튼) */
    fun answerActive(answer: CopilotAnswer, source: AnswerSource = AnswerSource.BUTTON) {
        val active = _state.value.active ?: return
        answer(active.id, answer, source)
    }

    private fun expire(id: Long) {
        val suggestion = active(id) ?: return
        if (_state.value.phase == CopilotPhase.LISTENING) speech.stopListening()
        // 대답이 없던 것도 "지금은 아니다"로 보고 간격을 늘린다 (스팟은 다음에 다시 권할 수 있다)
        memory = memory.copy(consecutiveDeclines = memory.consecutiveDeclines + 1)
        finish(
            suggestion,
            CopilotLogEntry(clock.nowMillis(), suggestion.spot.name, CopilotScripts.logText(null, null, null, null)),
        )
    }

    private fun finish(suggestion: CopilotSuggestion, log: CopilotLogEntry?) {
        expiryJob?.cancel()
        expiryJob = null
        _state.update { it.copy(active = null, phase = CopilotPhase.IDLE, heard = null, lastLog = log ?: it.lastLog) }
        presenter?.dismiss(suggestion.id)
    }

    private fun setPhase(suggestion: CopilotSuggestion, phase: CopilotPhase, heard: String?) {
        _state.update { it.copy(active = suggestion, phase = phase, heard = heard) }
        presenter?.show(suggestion, phase, heard)
    }

    private fun active(id: Long): CopilotSuggestion? = _state.value.active?.takeIf { it.id == id }

    private companion object {
        const val CANDIDATE_COUNT = 20
    }
}
