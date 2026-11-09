package com.haegopa.app

/**
 * 제출 빌드에서 켜고 끄는 기능 스위치.
 * 코드는 남겨 두고 화면 · 권한 요청에서만 빼므로, true 로 바꾸면 그대로 되살아난다.
 */
object FeatureFlags {
    /**
     * 졸음 방지 모드(노래방 MR · 싱얼롱 가사 · 1분 음성 퀴즈).
     * 2026-11-09 먹고파 · 놀고파 · 쉬고파 3개에 집중하기로 하고 꺼 둠. 끄면 STT(마이크)도 쓰지 않는다.
     */
    const val DROWSINESS_BOOSTER = false
}
