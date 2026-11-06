package com.haegopa.app.domain.model

import kotlin.math.floor

/** 반주(MR) 합성에 쓰는 3화음. rootMidi 는 4옥타브 근처(53~60) 기준 */
data class Chord(val rootMidi: Int, val minor: Boolean = false) {
    val tones: List<Int> get() = listOf(rootMidi, rootMidi + if (minor) 3 else 4, rootMidi + 7)
}

data class LyricLine(val text: String)

/**
 * 싱얼롱 곡 정의. 가사는 [introBeats] 전주 뒤 한 줄당 [beatsPerLine] 박자씩 흘러간다.
 * 곡은 끝나면 처음부터 반복(loop)된다.
 */
data class SingAlongSong(
    val id: String,
    val title: String,
    val credit: String,
    val bpm: Int,
    val progression: List<Chord>,
    val lines: List<LyricLine>,
    val introBeats: Int = 4,
    val beatsPerLine: Int = 8,
) {
    init {
        require(bpm > 0 && lines.isNotEmpty() && progression.isNotEmpty())
    }

    val totalBeats: Int get() = introBeats + lines.size * beatsPerLine
    val durationMs: Long get() = totalBeats * 60_000L / bpm

    /** 재생 위치(ms, 누적) → 곡 안에서의 박자 위치 */
    fun beatAt(positionMs: Long): Double = (positionMs.coerceAtLeast(0) % durationMs) * bpm / 60_000.0

    /** 현재 불러야 할 가사 줄. 전주 중이면 -1 */
    fun lineIndexAt(positionMs: Long): Int {
        val beat = beatAt(positionMs)
        if (beat < introBeats) return -1
        return floor((beat - introBeats) / beatsPerLine).toInt().coerceIn(0, lines.lastIndex)
    }

    /** 현재 줄의 진행률(0..1) — 노래방 자막 색 채우기 효과에 사용 */
    fun lineProgressAt(positionMs: Long): Float {
        val beat = beatAt(positionMs)
        if (beat < introBeats) return 0f
        val inLine = (beat - introBeats) % beatsPerLine
        return (inLine / beatsPerLine).toFloat().coerceIn(0f, 1f)
    }

    /** 전주 카운트다운 (4,3,2,1). 전주가 아니면 null */
    fun countInAt(positionMs: Long): Int? {
        val beat = beatAt(positionMs)
        if (beat >= introBeats) return null
        return introBeats - floor(beat).toInt()
    }
}
