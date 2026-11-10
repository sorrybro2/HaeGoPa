package com.haegopa.app.domain.util

/**
 * 조사를 앞말의 마지막 소리에 맞춰 고른다.
 * TTS가 "김밥(으)로"를 괄호째 읽지 않도록, 화면 메시지와 음성 안내 모두 이걸 쓴다.
 *
 * - '(으)로' : 받침이 없거나 ㄹ 받침 → "로" (사우나로 · 숲길로), 그 밖의 받침 → "으로" (김밥으로)
 * - '이/가'  : 받침이 있으면 "이" (순두부가 · 산책로가 · 국밥이)
 * - '을/를'  : 받침이 있으면 "을" (순두부를 · 국밥을)
 * - 한글이 아닌 끝 글자는 읽는 소리로 판단 (DT→"티"로 · 3→"삼"으로 · M→"엠"으로 · 1→"일"로)
 */
object KoreanJosa {

    fun ro(word: String): String {
        val sound = finalSound(word)
        return if (sound == Final.CONSONANT) "으로" else "로"
    }

    fun iGa(word: String): String = if (finalSound(word) == Final.VOWEL) "가" else "이"

    fun eulReul(word: String): String = if (finalSound(word) == Final.VOWEL) "를" else "을"

    private enum class Final { VOWEL, RIEUL, CONSONANT }

    private fun finalSound(word: String): Final {
        val last = word.trimEnd().lastOrNull() ?: return Final.VOWEL
        return when (last) {
            in '가'..'힣' -> when ((last - '가') % 28) {
                0 -> Final.VOWEL
                RIEUL -> Final.RIEUL
                else -> Final.CONSONANT
            }
            // 일(1) · 칠(7) · 팔(8) · 엘(L) · 알(R): ㄹ 받침
            '1', '7', '8', 'L', 'l', 'R', 'r' -> Final.RIEUL
            // 영(0) · 삼(3) · 육(6) · 엠(M) · 엔(N): ㄹ이 아닌 받침
            '0', '3', '6', 'M', 'm', 'N', 'n' -> Final.CONSONANT
            else -> Final.VOWEL
        }
    }

    private const val RIEUL = 8
}
