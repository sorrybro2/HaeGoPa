package com.haegopa.app.copilot

import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.domain.util.KoreanJosa
import com.haegopa.app.sdk.WaypointResult

/**
 * 코파일럿이 읽는 문장. 운전 중에 듣는 말이라 짧게, 한 번에 하나만 묻는다.
 *
 * 예) "점심 시간이에요. 경로에서 3분만 돌아가면 할매 순두부가 있어요. 경유지로 넣을까요?"
 */
object CopilotScripts {

    fun question(decision: CopilotDecision, spot: Spot, asWaypoint: Boolean, withHint: Boolean): String = buildString {
        append(opening(decision))
        append(' ')
        append(spotIntro(spot, asWaypoint))
        append(' ')
        append(purpose(spot.category))
        append(if (asWaypoint) "경유지로 넣을까요?" else "그쪽으로 안내할까요?")
        if (withHint) append(" 응, 아니로 대답해 주세요.")
    }

    private fun opening(decision: CopilotDecision): String = when (decision.reason) {
        CopilotReason.MEAL -> "${decision.mealLabel ?: "식사"} 시간이에요."
        CopilotReason.FATIGUE -> "${(decision.tripMinutes / 60).coerceAtLeast(1)}시간 넘게 운전하셨어요."
        CopilotReason.DEMO -> when (decision.category) {
            SpotCategory.EAT -> "출출하지 않으세요?"
            SpotCategory.PLAY -> "심심하지 않으세요?"
            SpotCategory.REST -> "조금 지치셨죠?"
        }
    }

    private fun spotIntro(spot: Spot, asWaypoint: Boolean): String {
        val name = spot.name
        val subject = name + KoreanJosa.iGa(name)
        return if (asWaypoint && spot.category == SpotCategory.EAT && spot.detourMinutes > 0) {
            "경로에서 ${spot.detourMinutes}분만 돌아가면 $subject 있어요."
        } else {
            "${GeoUtils.spokenDistance(spot.distanceMeters)} 거리에 $subject 있어요."
        }
    }

    private fun purpose(category: SpotCategory): String = when (category) {
        SpotCategory.EAT -> ""
        SpotCategory.PLAY -> "들렀다 가게 "
        SpotCategory.REST -> "잠깐 쉬어 가게 "
    }

    const val REPROMPT = "잘 못 들었어요. 응, 아니로 말씀해 주세요."
    const val DECLINED = "알겠어요. 다음에 알려 드릴게요."

    /** 내비에 보낸 결과를 알려 주는 짧은 확인 */
    fun confirmation(spot: Spot, result: WaypointResult): String {
        val name = spot.name
        return when (result) {
            is WaypointResult.AddedAsWaypoint -> "$name${KoreanJosa.eulReul(name)} 경유지로 넣었어요."
            is WaypointResult.SetAsDestination -> "$name${KoreanJosa.ro(name)} 안내를 시작할게요."
            WaypointResult.AlreadyAdded -> "이미 경로에 들어 있는 곳이에요."
            WaypointResult.AlreadyDestination -> "이미 $name${KoreanJosa.ro(name)} 안내 중이에요."
            is WaypointResult.LimitReached -> "경유지가 ${result.max}곳으로 가득 차서 넣지 못했어요."
            is WaypointResult.Failed -> "내비에 보내지 못했어요. 잠시 뒤에 다시 해 볼게요."
        }
    }

    /** 화면 · 알림에 남기는 한 줄 기록 */
    fun logText(answer: CopilotAnswer?, source: AnswerSource?, heard: String?, result: WaypointResult?): String {
        val how = when {
            source == AnswerSource.VOICE && !heard.isNullOrBlank() -> " · 음성 \"$heard\""
            source != null -> " · ${source.label}"
            else -> ""
        }
        val what = when (answer) {
            null -> "응답 없어 넘어감"
            CopilotAnswer.DECLINE -> "괜찮아요"
            CopilotAnswer.ADD, CopilotAnswer.DESTINATION -> when (result) {
                is WaypointResult.AddedAsWaypoint -> "경유지 ${result.order}${KoreanJosa.ro(result.order.toString())} 추가"
                is WaypointResult.SetAsDestination -> "목적지로 길안내"
                WaypointResult.AlreadyAdded -> "이미 경로에 있음"
                WaypointResult.AlreadyDestination -> "이미 안내 중"
                is WaypointResult.LimitReached -> "경유지 가득 참"
                is WaypointResult.Failed -> "내비 전달 실패"
                null -> ""
            }
        }
        return what + how
    }
}
