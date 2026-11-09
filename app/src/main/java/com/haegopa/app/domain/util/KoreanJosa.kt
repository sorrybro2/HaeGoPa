package com.haegopa.app.domain.util

/**
 * 조사 '(으)로'를 앞말의 마지막 소리에 맞춰 고른다.
 * TTS가 "김밥(으)로"를 괄호째 읽지 않도록, 화면 메시지와 음성 안내 모두 이걸 쓴다.
 *
 * - 받침이 없거나 ㄹ 받침 → "로"  (사우나로 · 숲길로)
 * - 그 밖의 받침 → "으로"          (김밥으로 · 언덕으로)
 * - 한글이 아닌 끝 글자는 읽는 소리로 판단 (DT→"티"로 · 3→"삼"으로 · M→"엠"으로)
 */
object KoreanJosa {

    fun ro(word: String): String = if (needsEu(word.trimEnd().lastOrNull())) "으로" else "로"

    private fun needsEu(last: Char?): Boolean = when (last) {
        null -> false
        in '가'..'힣' -> {
            val jongseong = (last - '가') % 28
            jongseong != 0 && jongseong != RIEUL
        }
        // 영(0) · 삼(3) · 육(6) · 엠(M) · 엔(N): 받침이 ㄹ이 아닌 자음
        '0', '3', '6', 'M', 'm', 'N', 'n' -> true
        else -> false
    }

    private const val RIEUL = 8
}
