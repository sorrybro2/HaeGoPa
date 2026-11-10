package com.haegopa.app.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceReplyParserTest {

    private fun parse(text: String) = VoiceReplyParser.parse(text)

    @Test
    fun `short and casual yes adds the waypoint`() {
        listOf("응", "응응", "어", "네", "네 좋아요", "예", "그래", "좋아!", "콜", "오케이", "넣어 줘", "그래 가자", "들렀다 가자", "ㅇㅇ")
            .forEach { assertEquals(it, CopilotAnswer.ADD, parse(it)) }
    }

    @Test
    fun `no, not now, and negated verbs decline`() {
        listOf("아니", "아니요", "아뇨", "괜찮아요", "됐어", "다음에", "나중에 갈게", "안 가", "안 가요", "싫어", "안 좋아", "패스")
            .forEach { assertEquals(it, CopilotAnswer.DECLINE, parse(it)) }
    }

    @Test
    fun `asking for the destination wins, even after no`() {
        assertEquals(CopilotAnswer.DESTINATION, parse("목적지로 해 줘"))
        assertEquals(CopilotAnswer.DESTINATION, parse("아니, 목적지로"))
        // "목적지 말고 경유지로" 는 경유지
        assertEquals(CopilotAnswer.ADD, parse("목적지 말고 경유지로"))
    }

    @Test
    fun `silence and unrelated words are not an answer`() {
        listOf("", "   ", "음...", "어디야?", "어떤 곳이야", "예약 돼?", "네비 켜 줘")
            .forEach { assertNull(it, parse(it)) }
    }
}
