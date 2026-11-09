package com.haegopa.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanJosaTest {

    @Test
    fun `picks 으로 after a final consonant other than rieul`() {
        assertEquals("으로", KoreanJosa.ro("아침공방 김밥"))
        assertEquals("으로", KoreanJosa.ro("노을 전망 언덕"))
        assertEquals("으로", KoreanJosa.ro("3"))
    }

    @Test
    fun `picks 로 after a vowel, rieul, or a letter read with a vowel ending`() {
        assertEquals("로", KoreanJosa.ro("솔바람 졸음쉼터"))
        assertEquals("로", KoreanJosa.ro("옛 철길 숲길"))
        assertEquals("로", KoreanJosa.ro("버거 스테이션 DT"))
        assertEquals("로", KoreanJosa.ro("1"))
    }
}
