package com.haegopa.app.audio

import com.haegopa.app.domain.model.SingAlongSong
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 보컬 없는 반주(MR) 실시간 합성기 — 프로토타입용 "더미 음원".
 * 패드(3화음) + 베이스 + 킥/스네어/하이햇으로 1박자씩 PCM 16bit mono 를 생성한다.
 * 정식 서비스에서는 이 자리에 라이선스 계약된 노래방 반주 스트리밍이 들어간다.
 */
class MrSynth(private val song: SingAlongSong, private val sampleRate: Int) {

    val samplesPerBeat: Int = sampleRate * 60 / song.bpm

    private val random = Random(7L)
    private var lastNoise = 0.0

    fun renderBeat(globalBeat: Long): ShortArray {
        val beatInSong = (globalBeat % song.totalBeats).toInt()
        val isIntro = beatInSong < song.introBeats
        val beatInBar = beatInSong % 4
        val bar = beatInSong / 4
        val chord = song.progression[bar % song.progression.size]
        val padHz = chord.tones.map(::midiToHz)
        val bassHz = midiToHz(chord.rootMidi - 24)
        val halfBeat = samplesPerBeat / 2
        val out = ShortArray(samplesPerBeat)
        val firstSample = globalBeat * samplesPerBeat

        for (i in 0 until samplesPerBeat) {
            val t = i.toDouble() / sampleRate // 박 안에서의 시간(s)
            val globalT = (firstSample + i).toDouble() / sampleRate // 위상 연속용 전체 시간(s)
            val tEighth = (i % halfBeat).toDouble() / sampleRate // 8분음표 안에서의 시간(s)
            var s = 0.0

            // 하이햇 (8분음표) — 간단한 1차 하이패스 노이즈
            val noise = random.nextDouble() * 2 - 1
            val highPassed = noise - lastNoise
            lastNoise = noise
            s += 0.05 * exp(-tEighth * 60) * highPassed

            if (isIntro) {
                // 전주: 카운트인 클릭 (1박 강하게)
                val clickHz = if (beatInBar == 0) 1_320.0 else 880.0
                s += 0.25 * exp(-t * 40) * sin(2 * PI * clickHz * t)
            } else {
                // 패드: 마디 단위 어택/릴리즈
                val barPos = (beatInBar * samplesPerBeat + i).toDouble() / (4 * samplesPerBeat)
                val padEnv = min(1.0, barPos * 14) * min(1.0, (1 - barPos) * 24)
                for (hz in padHz) s += 0.065 * padEnv * softSaw(globalT * hz)

                // 베이스: 8분음표 펄스
                s += 0.22 * exp(-tEighth * 6) * sin(2 * PI * bassHz * globalT)

                // 킥 (1·3박) — 주파수 스윕을 적분한 위상
                if (beatInBar == 0 || beatInBar == 2) {
                    val phase = 2 * PI * (50 * t + 70.0 / 30.0 * (1 - exp(-30 * t)))
                    s += 0.55 * exp(-t * 9) * sin(phase)
                }
                // 스네어 (2·4박)
                if (beatInBar == 1 || beatInBar == 3) {
                    s += 0.20 * exp(-t * 22) * noise + 0.08 * exp(-t * 18) * sin(2 * PI * 185 * t)
                }
            }
            out[i] = (tanh(s * 1.2) * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun softSaw(cycles: Double): Double {
        val x = 2 * PI * cycles
        return 0.57 * (sin(x) + 0.5 * sin(2 * x) + 0.25 * sin(3 * x))
    }

    private fun midiToHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
}
