package com.haegopa.app.copilot

import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotTag

/** 식사 시간대 (자정부터 분) */
data class MealWindow(val label: String, val startMinute: Int, val endMinute: Int) {
    operator fun contains(minuteOfDay: Int): Boolean = minuteOfDay in startMinute until endMinute
}

data class CopilotConfig(
    /** 조건을 다시 보는 간격 */
    val tickMillis: Long = 20_000L,
    /** 제안 사이 최소 간격 — 거절이 이어질수록 늘어난다 (최대 4배) */
    val cooldownMillis: Long = 15 * 60_000L,
    /**
     * 대답이 없으면 이 시간 뒤 조용히 넘어간다 (알림도 같이 사라짐).
     * 질문 읽기 ~10초 + 듣기 ~10초 (+ 다시 묻기) 뒤에도 알림 센터 버튼을 누를 시간이 남도록 1분.
     */
    val responseTimeoutMillis: Long = 60_000L,
    val fatigueMinutes: Int = 120,
    /** 쉬어 가기를 다시 권하기까지 */
    val fatigueRepeatMillis: Long = 60 * 60_000L,
    val mealWindows: List<MealWindow> = listOf(
        MealWindow("점심", 11 * 60, 13 * 60 + 30),
        MealWindow("저녁", 17 * 60 + 30, 19 * 60 + 30),
    ),
    /** 순정 내비 경유지 슬롯 (NaviHelper WaypointIndex FIRST · SECOND) */
    val maxWaypoints: Int = 2,
    /**
     * 질문 뒤 Pleos STT 로 "응 / 아니" 대답을 들을지.
     * 공모전 베타 앱은 false (BuildConfig.COPILOT_VOICE_REPLY) — 실차 마이크로 인식을 확인한 뒤 켠다.
     * false 면 마이크가 있어도 듣지 않고 알림 · 배너 버튼만 기다린다.
     */
    val voiceReply: Boolean = true,
)

/** 지금 상황 (엔진이 SDK 에서 모아 넘긴다) */
data class TriggerInput(
    val nowMillis: Long,
    val minuteOfDay: Int,
    val dayKey: String,
    val tripMinutes: Int,
    val driving: Boolean,
    val routeActive: Boolean,
    val waypointCount: Int,
    /** 해고파 화면이 앞에 있으면 목록이 이미 보이므로 말을 걸지 않는다 (순정 내비가 앞에 있을 때가 코파일럿 차례) */
    val appVisible: Boolean,
)

/** 이미 권한 기록 — 같은 이유로 자꾸 말하지 않게 */
data class TriggerMemory(
    val lastSuggestionAt: Long? = null,
    val lastFatigueAt: Long? = null,
    /** "2026-11-10#점심" — 그 끼니는 이미 권함 */
    val mealKeysFired: Set<String> = emptySet(),
    /** 연속 거절 · 무응답 횟수 → 간격이 늘어난다 */
    val consecutiveDeclines: Int = 0,
)

object CopilotPolicy {

    /**
     * 지금 말을 걸지 정한다. 걸기로 했으면 (결정, 갱신된 기록)을, 아니면 null.
     * - 해고파 화면이 앞에 있거나 · 주행도 길안내도 아닐 때 · 경유지 두 칸이 다 찼을 때는 말하지 않는다
     * - 직전 제안 뒤 쿨다운(거절이 이어지면 2 · 3 · 4배) 안에는 말하지 않는다
     * - 우선순위: 연속 운전 2시간(쉬고파) > 식사 시간대(먹고파, 끼니마다 한 번)
     */
    fun decide(input: TriggerInput, memory: TriggerMemory, config: CopilotConfig): Pair<CopilotDecision, TriggerMemory>? {
        if (input.appVisible) return null
        if (!input.driving && !input.routeActive) return null
        if (input.routeActive && input.waypointCount >= config.maxWaypoints) return null
        memory.lastSuggestionAt?.let { last ->
            val factor = 1 + memory.consecutiveDeclines.coerceAtMost(3)
            if (input.nowMillis - last < config.cooldownMillis * factor) return null
        }

        val fatigueDue = input.tripMinutes >= config.fatigueMinutes &&
            (memory.lastFatigueAt == null || input.nowMillis - memory.lastFatigueAt >= config.fatigueRepeatMillis)
        if (fatigueDue) {
            return CopilotDecision(CopilotReason.FATIGUE, SpotCategory.REST, tripMinutes = input.tripMinutes) to
                memory.copy(lastFatigueAt = input.nowMillis)
        }

        val meal = config.mealWindows.firstOrNull { input.minuteOfDay in it }
        if (meal != null) {
            val key = "${input.dayKey}#${meal.label}"
            if (key !in memory.mealKeysFired) {
                return CopilotDecision(CopilotReason.MEAL, SpotCategory.EAT, mealLabel = meal.label, tripMinutes = input.tripMinutes) to
                    memory.copy(mealKeysFired = memory.mealKeysFired + key)
            }
        }
        return null
    }

    /**
     * 후보 중 하나를 고른다 (이미 경로에 있거나 이번 운행에 거절한 곳은 뺀다).
     * - 먹고파: (식사 시간이면 카페 · 디저트보다 식당) → 경로에서 덜 돌아가는 곳 → 가까운 곳
     * - 쉬고파: 걷거나 자리를 펼 수 있는 산책로 · 공원(1분 스트레칭) → 가까운 곳
     * - 놀고파: 음성 소개가 있는 명소 · 랜드마크 → 가까운 곳
     */
    fun pickSpot(
        category: SpotCategory,
        candidates: List<Spot>,
        exclude: Set<String>,
        reason: CopilotReason = CopilotReason.DEMO,
    ): Spot? {
        val pool = candidates.filter { it.category == category && it.name !in exclude }
        val mealTime = reason == CopilotReason.MEAL
        val order: Comparator<Spot> = when (category) {
            SpotCategory.EAT -> compareBy({ mealTime && it.kind != SpotTag.RESTAURANT }, { it.detourMinutes }, { it.distanceMeters })
            SpotCategory.REST -> compareBy({ !it.supportsStretching }, { it.distanceMeters })
            SpotCategory.PLAY -> compareBy({ !it.hasDocent }, { it.distanceMeters })
        }
        return pool.minWithOrNull(order)
    }
}
