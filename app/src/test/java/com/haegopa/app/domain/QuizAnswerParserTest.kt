package com.haegopa.app.domain

import com.haegopa.app.domain.model.QuizAnswerParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuizAnswerParserTest {

    private val planets = listOf("목성", "토성", "지구")
    private val temperatures = listOf("90도", "100도", "120도")
    private val regions = listOf("경상북도 울릉군", "강원도 강릉시", "경상남도 통영시")

    @Test
    fun `numbered answers`() {
        assertEquals(0, QuizAnswerParser.parse("1번!", planets))
        assertEquals(1, QuizAnswerParser.parse("정답은 2번이요", planets))
        assertEquals(2, QuizAnswerParser.parse("삼 번", planets))
    }

    @Test
    fun `ordinal answers`() {
        assertEquals(1, QuizAnswerParser.parse("두 번째", planets))
        assertEquals(2, QuizAnswerParser.parse("셋", planets))
        assertEquals(0, QuizAnswerParser.parse("첫 번째 거", planets))
    }

    @Test
    fun `choice text answers`() {
        assertEquals(1, QuizAnswerParser.parse("토성 아닌가?", planets))
        assertEquals(1, QuizAnswerParser.parse("100도", temperatures))
        assertEquals(2, QuizAnswerParser.parse("120도요", temperatures))
        assertEquals(0, QuizAnswerParser.parse("울릉군", regions))
    }

    @Test
    fun `unrecognized answers`() {
        assertNull(QuizAnswerParser.parse("", planets))
        assertNull(QuizAnswerParser.parse("잘 모르겠어요", planets))
    }
}
