package com.haegopa.app.domain.model

data class QuizQuestion(
    val id: String,
    val question: String,
    val choices: List<String>,
    val answerIndex: Int,
    val explanation: String,
) {
    init {
        require(choices.size == 3 && answerIndex in choices.indices)
    }
}

/**
 * STT 인식 문장 → 보기 번호(0-based).
 * "2번", "이 번", "두 번째", "둘", 보기 텍스트("목성") 등 운전자가 실제로 말할 법한 표현을 처리한다.
 */
object QuizAnswerParser {

    private val ordinalTokens = listOf(
        listOf("1번", "일번", "첫번째", "첫째", "하나", "첫번"),
        listOf("2번", "이번", "두번째", "둘째", "둘", "두번"),
        listOf("3번", "삼번", "세번째", "셋째", "셋", "세번"),
    )

    fun parse(spoken: String, choices: List<String>): Int? {
        val text = normalize(spoken)
        if (text.isEmpty()) return null

        // 1) 보기 텍스트를 직접 말한 경우 (숫자형 보기 "100도" 도 여기서 먼저 처리)
        choices.forEachIndexed { index, choice ->
            val normalizedChoice = normalize(choice)
            if (normalizedChoice.isNotEmpty() && text.contains(normalizedChoice)) return index
        }
        // 1-1) 보기의 일부 단어만 말한 경우 ("경상북도 울릉군" → "울릉군"). 한 보기에만 해당될 때만 인정
        val partialMatches = choices.indices.filter { index ->
            choices[index].split(' ')
                .map(::normalize)
                .any { word -> word.length >= 2 && text.contains(word) }
        }
        if (partialMatches.size == 1) return partialMatches.first()
        // 2) "n번", "두 번째", "셋" 같은 서수 표현
        ordinalTokens.forEachIndexed { index, tokens ->
            if (index < choices.size && tokens.any { text.contains(it) }) return index
        }
        // 3) 숫자만 말한 경우 ("2")
        val digits = text.filter { it in '1'..'3' }.toSet()
        if (digits.size == 1) {
            val index = digits.first() - '1'
            if (index in choices.indices) return index
        }
        return null
    }

    private fun normalize(value: String): String =
        value.lowercase().filterNot { it.isWhitespace() || it in ".,!?~\"'" }
}
