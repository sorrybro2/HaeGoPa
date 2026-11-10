package com.haegopa.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanJosaTest {

    @Test
    fun `picks 으로 after a final consonant other than rieul`() {
        assertEquals("으로", KoreanJosa.ro("로스터리 숲"))
        assertEquals("으로", KoreanJosa.ro("노을 전망 언덕"))
        assertEquals("으로", KoreanJosa.ro("3"))
    }

    @Test
    fun `picks 로 after a vowel, rieul, or a letter read with a vowel ending`() {
        assertEquals("로", KoreanJosa.ro("편백 찜질 사우나"))
        assertEquals("로", KoreanJosa.ro("옛 철길 숲길"))
        assertEquals("로", KoreanJosa.ro("버거 스테이션 DT"))
        assertEquals("로", KoreanJosa.ro("1"))
    }

    @Test
    fun `subject and object particles follow any final consonant, rieul included`() {
        assertEquals("가", KoreanJosa.iGa("할매 순두부"))
        assertEquals("이", KoreanJosa.iGa("한그릇 국밥"))
        assertEquals("이", KoreanJosa.iGa("옛 철길 숲길"))
        assertEquals("를", KoreanJosa.eulReul("편백 찜질 사우나"))
        assertEquals("을", KoreanJosa.eulReul("노을 전망 언덕"))
        assertEquals("을", KoreanJosa.eulReul("옛 철길 숲길"))
        assertEquals("를", KoreanJosa.eulReul("버거 스테이션 DT"))
    }
}
