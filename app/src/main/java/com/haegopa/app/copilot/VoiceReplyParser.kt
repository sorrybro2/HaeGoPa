package com.haegopa.app.copilot

/**
 * "경유지로 넣을까요?"에 대한 짧은 한국어 대답을 [CopilotAnswer] 로 바꾼다. 모르면 null.
 *
 * 순서가 중요하다.
 * 1. "목적지"가 들어 있으면(…말고 제외) → DESTINATION   ("아니, 목적지로 해 줘")
 * 2. 거절 표현 → DECLINE                                ("안 가" 가 "가"로 승낙되지 않게 먼저 본다)
 * 3. 승낙 표현 → ADD
 * 한 글자 대답(응 · 어 · 네 · 예)은 그 글자로만 이뤄졌을 때만 승낙으로 본다 ("어디야?"는 승낙이 아님).
 */
object VoiceReplyParser {

    fun parse(text: String): CopilotAnswer? {
        val t = text.lowercase().replace(NOISE, "")
        if (t.isEmpty()) return null
        if (t.contains("목적지") && !t.contains("말고")) return CopilotAnswer.DESTINATION
        if (DECLINE.any { t.contains(it) }) return CopilotAnswer.DECLINE
        if (isShortYes(t) || ACCEPT.any { t.contains(it) }) return CopilotAnswer.ADD
        return null
    }

    /** "응" · "응응" · "네네" · "네 좋아요" 처럼 짧은 긍정으로 시작하는 대답 */
    private fun isShortYes(t: String): Boolean {
        if (t.all { it in SHORT_YES_CHARS }) return true
        // "네 ~" · "응 ~" · "예 ~" 로 시작하면 긍정 ("어 ~"는 "어디" · "어떤" 때문에 제외)
        return t.length >= 2 && t[0] in LEADING_YES_CHARS && t[1] !in NOT_FOLLOWING_YES
    }

    private val NOISE = Regex("[\\s.,!?~…'\"]")

    private const val SHORT_YES_CHARS = "응어네예웅엉ㅇ"
    private const val LEADING_YES_CHARS = "응네예"

    /** "예약" · "네비" · "응급" 처럼 긍정 글자로 시작하는 다른 낱말 */
    private const val NOT_FOLLOWING_YES = "약비급원"

    private val DECLINE = listOf(
        "아니", "아뇨", "아냐", "됐어", "됐습니다", "괜찮", "싫어", "싫다", "안돼", "안되", "안가", "안갈", "안넣", "안좋",
        "다음에", "나중에", "패스", "그만", "취소", "넘어가", "노노", "no", "nope",
    )

    private val ACCEPT = listOf(
        "그래", "좋아", "좋습니다", "좋지", "콜", "오케이", "ok", "yes", "추가", "넣어", "넣자", "넣을게", "가자", "가요",
        "갈래", "갈게", "들르", "들러", "들렀", "경유", "부탁", "ㅇㅋ", "알겠", "고고", "그러자", "그럽시다",
    )
}
