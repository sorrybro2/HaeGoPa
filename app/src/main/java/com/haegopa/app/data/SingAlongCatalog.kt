package com.haegopa.app.data

import com.haegopa.app.domain.model.Chord
import com.haegopa.app.domain.model.LyricLine
import com.haegopa.app.domain.model.SingAlongSong

/**
 * 싱얼롱 곡 목록.
 * 저작권 이슈가 없도록 가사는 모두 해고파 팀 오리지널이며, 반주는 앱이 실시간 합성한 더미 MR 이다.
 * 정식 서비스에서는 노래방 반주 저작권 보유사와의 라이선스 계약 음원(보컬 제거 MR)으로 교체한다.
 */
object SingAlongCatalog {

    private const val CREDIT = "해고파 오리지널 · 프로토타입 합성 MR"

    val songs: List<SingAlongSong> = listOf(
        SingAlongSong(
            id = "haegopa-drive",
            title = "해고파 드라이브 송",
            credit = CREDIT,
            bpm = 112,
            progression = listOf(Chord(60), Chord(55), Chord(57, minor = true), Chord(53)), // C G Am F
            lines = listOf(
                "배고파? 먹고파! 창문 열고 달려가",
                "심심해? 놀고파! 노을 따라 핸들 돌려",
                "지쳤어? 쉬고파! 쉼표 하나 찍고 가",
                "뭐라도 하고플 땐 외쳐 봐 해고파!",
                "졸음은 저 멀리 신호등은 초록빛",
                "목청껏 불러 봐 오늘도 안전 운전",
                "라라라 라라라 해고파 해고파",
                "집으로 가는 길 우리 같이 달려가",
            ).map(::LyricLine),
        ),
        SingAlongSong(
            id = "drowsy-away",
            title = "졸음아 물러가라",
            credit = CREDIT,
            bpm = 128,
            progression = listOf(Chord(57, minor = true), Chord(53), Chord(60), Chord(55)), // Am F C G
            lines = listOf(
                "눈꺼풀이 무거울 땐 소리 높여 노래해",
                "하나 둘 셋 넷 어깨를 들썩들썩",
                "창문 살짝 내리고 시원한 바람 한 줌",
                "졸음쉼터 보이면 잠깐 쉬어 가는 거야",
                "박수 대신 핸들 꼭 두 손은 제자리",
                "목소리는 크게 눈은 앞을 향해",
                "졸음아 물러가라 훠이 훠이 물러가라",
                "오늘도 무사히 집까지 씽씽 달려",
            ).map(::LyricLine),
        ),
        SingAlongSong(
            id = "commute-chorus",
            title = "퇴근길 떼창",
            credit = CREDIT,
            bpm = 100,
            progression = listOf(Chord(53), Chord(55), Chord(52, minor = true), Chord(57, minor = true)), // F G Em Am
            lines = listOf(
                "꽉 막힌 퇴근길 빨간 불빛 줄지어",
                "한숨 대신 노래 한 소절 불러 볼까",
                "앞차도 뒤차도 모두 같은 마음",
                "조금만 더 가면 따뜻한 우리 집",
                "깜빡이 켜고 양보 한 번 웃음 한 번",
                "느려도 괜찮아 천천히 가도 돼",
                "라디오 대신 떼창 해고파와 함께",
                "오늘 하루도 정말 수고 많았어",
            ).map(::LyricLine),
        ),
    )
}
