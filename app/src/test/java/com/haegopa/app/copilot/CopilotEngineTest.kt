package com.haegopa.app.copilot

import com.haegopa.app.data.MockDataRepository
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.usecase.AddWaypointUseCase
import com.haegopa.app.domain.usecase.GetNearbySpotsUseCase
import com.haegopa.app.domain.usecase.SetDestinationUseCase
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.SpeechCaption
import com.haegopa.app.sdk.SpeechGateway
import com.haegopa.app.sdk.WaypointKind
import com.haegopa.app.sdk.mock.MockNaviHelperImpl
import com.haegopa.app.sdk.mock.MockVehicleManagerImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CopilotEngineTest {

    /** TTS · STT 를 손으로 진행시키는 가짜 음성 엔진 */
    private class FakeSpeech : SpeechGateway {
        val spoken = mutableListOf<String>()
        private var pendingDone: (() -> Unit)? = null
        private var pendingListen: ((String) -> Unit)? = null
        var stopListeningCalls = 0
        override var canListen = true

        override val ttsStatus: StateFlow<SdkStatus> = MutableStateFlow(SdkStatus("fake", SdkBackend.PLEOS))
        override val sttStatus: StateFlow<SdkStatus> = MutableStateFlow(SdkStatus("fake", SdkBackend.PLEOS))
        override val caption: StateFlow<SpeechCaption?> = MutableStateFlow(null)

        override fun speakText(script: String) {
            spoken += script
            pendingDone = null
        }

        override fun speakThen(script: String, onDone: () -> Unit) {
            spoken += script
            pendingDone = onDone
        }

        override fun stopSpeaking() {
            pendingDone = null
        }

        override fun startVoiceRecognition(callback: (command: String) -> Unit) {
            pendingListen = callback
        }

        override fun stopListening() {
            stopListeningCalls++
            pendingListen = null
        }

        val listening: Boolean get() = pendingListen != null

        fun finishSpeaking() = pendingDone?.also { pendingDone = null }?.invoke()

        fun reply(text: String) = pendingListen?.also { pendingListen = null }?.invoke(text)
    }

    private class FakeClock(private val scope: TestScope) : CopilotClock {
        var minute = 15 * 60
        var offsetMillis = 0L
        override fun nowMillis(): Long = scope.testScheduler.currentTime + offsetMillis
        override fun minuteOfDay(): Int = minute
        override fun dayKey(): String = "2026-11-10"
    }

    private class Harness(scope: TestScope, tripMinutes: Int = 30) {
        val navi = MockNaviHelperImpl()
        val vehicle = MockVehicleManagerImpl(clock = { 0L }, initialTripMinutes = tripMinutes)
        val speech = FakeSpeech()
        val visible = MutableStateFlow(false)
        val clock = FakeClock(scope)
        val config = CopilotConfig()
        val engine = CopilotEngine(
            scope = scope.backgroundScope,
            getNearbySpots = GetNearbySpotsUseCase(MockDataRepository(latencyMillis = 0)),
            addWaypoint = AddWaypointUseCase(navi),
            setDestination = SetDestinationUseCase(navi),
            naviHelper = navi,
            vehicle = vehicle,
            speech = speech,
            appVisible = visible,
            settings = InMemoryCopilotSettings(enabled = true),
            clock = clock,
            config = config,
        )

        /** 순정 내비가 회사로 길안내 중 */
        fun guiding() = navi.recordDestination("회사", 37.40, 127.10)

        val active get() = engine.state.value.active
        val phase get() = engine.state.value.phase
    }

    @Test
    fun `at lunch it asks by voice and 응 adds the restaurant as a navi waypoint`() = runTest {
        val h = Harness(this)
        h.guiding()
        h.clock.minute = 12 * 60

        h.engine.evaluate()
        val suggestion = h.active!!
        assertEquals(CopilotReason.MEAL, suggestion.reason)
        assertTrue(suggestion.asWaypoint)
        assertEquals(CopilotPhase.SPEAKING, h.phase)
        val question = h.speech.spoken.last()
        assertTrue(question, question.startsWith("점심 시간이에요."))
        assertTrue(question, question.contains("경유지로 넣을까요?"))
        // 처음 한 번은 대답하는 법을 알려 준다
        assertTrue(question, question.endsWith("응, 아니로 대답해 주세요."))

        h.speech.finishSpeaking()
        assertEquals(CopilotPhase.LISTENING, h.phase)
        h.speech.reply("응")

        val stop = h.navi.waypoints.value.single { it.kind == WaypointKind.WAYPOINT }
        assertEquals(suggestion.spot.name, stop.name)
        assertNull(h.active)
        assertEquals(CopilotPhase.IDLE, h.phase)
        assertEquals("경유지 1로 추가 · 음성 \"응\"", h.engine.state.value.lastLog?.text)
        assertTrue(h.speech.spoken.last(), h.speech.spoken.last().endsWith("경유지로 넣었어요."))
    }

    @Test
    fun `목적지로 replaces the route with the suggested spot`() = runTest {
        val h = Harness(this, tripMinutes = 130)
        h.guiding()
        h.engine.evaluate()
        val suggestion = h.active!!
        assertEquals(CopilotReason.FATIGUE, suggestion.reason)
        assertTrue(h.speech.spoken.last().startsWith("2시간 넘게 운전하셨어요."))

        h.speech.finishSpeaking()
        h.speech.reply("목적지로 해 줘")

        val destination = h.navi.waypoints.value.single()
        assertEquals(WaypointKind.DESTINATION, destination.kind)
        assertEquals(suggestion.spot.name, destination.name)
    }

    @Test
    fun `a declined spot is not suggested again`() = runTest {
        val h = Harness(this, tripMinutes = 130)
        h.guiding()
        h.engine.evaluate()
        val first = h.active!!.spot
        h.speech.finishSpeaking()
        h.speech.reply("아니요")
        assertNull(h.active)
        assertTrue(h.navi.waypoints.value.none { it.kind == WaypointKind.WAYPOINT })
        assertEquals("알겠어요. 다음에 알려 드릴게요.", h.speech.spoken.last())

        // 쿨다운(거절 1번 → 2배) · 쉬어 가기 재권유 간격이 지난 뒤
        h.clock.offsetMillis = h.config.fatigueRepeatMillis + 1
        h.engine.evaluate()
        val second = h.active!!.spot
        assertEquals(SpotCategory.REST, second.category)
        assertNotEquals(first.name, second.name)
    }

    @Test
    fun `tapping the notification button while listening cancels recognition`() = runTest {
        val h = Harness(this, tripMinutes = 130)
        h.guiding()
        h.engine.evaluate()
        h.speech.finishSpeaking()
        assertTrue(h.speech.listening)

        h.engine.answer(h.active!!.id, CopilotAnswer.ADD, AnswerSource.BUTTON)

        assertEquals(1, h.speech.stopListeningCalls)
        assertEquals(1, h.navi.waypointCount)
        assertEquals("경유지 1로 추가 · 버튼", h.engine.state.value.lastLog?.text)
        // 늦게 도착한 음성 결과는 무시된다
        h.speech.reply("아니")
        assertEquals(1, h.navi.waypointCount)
    }

    @Test
    fun `mumbling gets one re-ask, then it waits for a button and expires quietly`() = runTest {
        val h = Harness(this, tripMinutes = 130)
        h.guiding()
        h.engine.evaluate()
        h.speech.finishSpeaking()

        h.speech.reply("음 글쎄")
        assertEquals(CopilotPhase.SPEAKING, h.phase)
        assertEquals("잘 못 들었어요. 응, 아니로 말씀해 주세요.", h.speech.spoken.last())
        h.speech.finishSpeaking()
        h.speech.reply("흠")
        assertEquals(CopilotPhase.WAITING_TAP, h.phase)
        assertEquals("흠", h.engine.state.value.heard)
        val spokenBefore = h.speech.spoken.size

        advanceTimeBy(h.config.responseTimeoutMillis + 1)
        runCurrent()

        assertNull(h.active)
        assertEquals("응답 없어 넘어감", h.engine.state.value.lastLog?.text)
        assertEquals("expiring should not talk again", spokenBefore, h.speech.spoken.size)
        assertEquals(0, h.navi.waypointCount)
    }

    @Test
    fun `without a microphone it skips listening and waits for buttons`() = runTest {
        val h = Harness(this, tripMinutes = 130)
        h.speech.canListen = false
        h.guiding()
        h.engine.evaluate()
        assertTrue("no answer hint without a mic", !h.speech.spoken.last().contains("대답해 주세요"))
        h.speech.finishSpeaking()
        assertEquals(CopilotPhase.WAITING_TAP, h.phase)
    }

    @Test
    fun `stays quiet while HAEGOPA itself is on screen`() = runTest {
        val h = Harness(this, tripMinutes = 130)
        h.guiding()
        h.visible.value = true
        h.engine.evaluate()
        assertNull(h.active)
        h.visible.value = false
        h.engine.evaluate()
        assertNotNull(h.active)
    }

    @Test
    fun `the PT demo suggests from the chosen tab after the delay, with no route it offers guidance`() = runTest {
        val h = Harness(this)
        h.engine.scheduleDemo(SpotCategory.PLAY, delayMillis = 6_000)
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(h.active)
        advanceTimeBy(1_001)
        runCurrent()

        val suggestion = h.active!!
        assertEquals(SpotCategory.PLAY, suggestion.spot.category)
        assertTrue(suggestion.spot.hasDocent)
        assertTrue(!suggestion.asWaypoint)
        assertTrue(h.speech.spoken.last(), h.speech.spoken.last().startsWith("심심하지 않으세요?"))
        assertTrue(h.speech.spoken.last(), h.speech.spoken.last().contains("그쪽으로 안내할까요?"))

        h.speech.finishSpeaking()
        h.speech.reply("좋아")
        assertEquals(WaypointKind.DESTINATION, h.navi.waypoints.value.single().kind)
    }

    @Test
    fun `turning it off drops the pending question`() = runTest {
        val h = Harness(this, tripMinutes = 130)
        h.guiding()
        h.engine.evaluate()
        h.speech.finishSpeaking()
        h.engine.setEnabled(false)
        assertNull(h.active)
        assertEquals(1, h.speech.stopListeningCalls)
        h.engine.evaluate()
        assertNull(h.active)
    }
}
