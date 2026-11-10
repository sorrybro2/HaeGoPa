package com.haegopa.app.copilot

import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory

/** 코파일럿이 말을 거는 이유 */
enum class CopilotReason {
    /** 점심 · 저녁 시간대 → 먹고파 (경로에서 덜 돌아가는 곳) */
    MEAL,

    /** 연속 운전 2시간 이상 → 쉬고파 */
    FATIGUE,

    /** PT 시연: [시연 → 코파일럿] 버튼으로 몇 초 뒤 바로 제안 */
    DEMO,
}

/** 정책이 "지금 말을 걸자"고 정한 결과 */
data class CopilotDecision(
    val reason: CopilotReason,
    val category: SpotCategory,
    /** 식사 시간대 이름 ("점심" · "저녁") */
    val mealLabel: String? = null,
    val tripMinutes: Int = 0,
)

data class CopilotSuggestion(
    val id: Long,
    val spot: Spot,
    val reason: CopilotReason,
    /** 순정 내비가 길안내 중이면 경유지로, 아니면 그 스팟으로 길안내를 시작한다 */
    val asWaypoint: Boolean,
    /** TTS 로 읽는 질문 */
    val script: String,
)

/** 운전자의 대답 */
enum class CopilotAnswer {
    /** 응 · 좋아 · 넣어 줘 → [경유지] (경로가 없으면 그 스팟으로 길안내) */
    ADD,

    /** 목적지로 해 줘 → [목적지] */
    DESTINATION,

    /** 아니 · 괜찮아 · 다음에 → 넘어감 (이번 운행 동안 같은 곳은 다시 권하지 않음) */
    DECLINE,
}

enum class AnswerSource(val label: String) { VOICE("음성"), BUTTON("버튼") }

enum class CopilotPhase {
    IDLE,

    /** 질문을 읽는 중 */
    SPEAKING,

    /** 대답을 듣는 중 (Pleos STT) */
    LISTENING,

    /** 못 알아들었거나 마이크가 없음 → 알림 · 배너 버튼을 기다림 */
    WAITING_TAP,
}

data class CopilotLogEntry(
    val atMillis: Long,
    val spotName: String,
    val text: String,
)

data class CopilotState(
    val enabled: Boolean = true,
    val active: CopilotSuggestion? = null,
    val phase: CopilotPhase = CopilotPhase.IDLE,
    /** 마지막으로 알아들은 말 (화면 · 알림에 "들은 말: 응" 표시) */
    val heard: String? = null,
    val lastLog: CopilotLogEntry? = null,
)

/** 제안을 운전자에게 보여 주는 곳 — Android 헤드업 알림 (테스트에서는 없음) */
interface CopilotPresenter {
    fun show(suggestion: CopilotSuggestion, phase: CopilotPhase, heard: String?)
    fun dismiss(suggestionId: Long)
}

/** 켜짐/꺼짐 저장소 (앱을 다시 켜도 유지) */
interface CopilotSettings {
    var enabled: Boolean
}

class InMemoryCopilotSettings(override var enabled: Boolean = true) : CopilotSettings

/** 시각 · 날짜 (테스트에서 바꿔 끼운다) */
interface CopilotClock {
    fun nowMillis(): Long

    /** 0..1439 (자정부터 분) */
    fun minuteOfDay(): Int

    /** "2026-11-10" — 식사 시간대를 하루에 한 번만 권하려고 쓴다 */
    fun dayKey(): String

    object System : CopilotClock {
        override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
        override fun minuteOfDay(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        override fun dayKey(): String = java.time.LocalDate.now().toString()
    }
}
