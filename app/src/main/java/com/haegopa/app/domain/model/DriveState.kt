package com.haegopa.app.domain.model

/**
 * 차량 안전 규격(Driver Distraction) 분기 기준.
 * - PARKED : Speed == 0 km/h → 2km 무한 스크롤, 상세, 소개 듣기(도슨트), 스트레칭 가이드 등 전체 기능
 * - DRIVING: Speed  > 0 km/h → 동승자용 큰 글씨 간편 목록(스크롤 가능, 개수 상한), 가장 가까운 곳 음성 안내,
 *                              상세·스트레칭(영상) 같은 정차 전용 화면은 잠금
 */
enum class DriveMode {
    PARKED,
    DRIVING;

    companion object {
        fun fromSpeed(speedKmh: Int): DriveMode = if (speedKmh > 0) DRIVING else PARKED

        /**
         * 주행 중 목록 최대 개수.
         * AAOS CarUxRestrictions 의 UX_RESTRICTIONS_LIMIT_CONTENT 는 스크롤 자체를 막지 않고
         * '누적 표시 항목 수'를 제한한다 (기본값 maxCumulativeContentItems = 21).
         */
        const val DRIVING_MAX_LIST_ITEMS = 21
    }
}

/** Pleos Vehicle SDK 의 VehicleGear 를 앱 내부 표현으로 단순화 */
enum class GearPosition(val label: String) {
    PARK("P"),
    REVERSE("R"),
    NEUTRAL("N"),
    DRIVE("D"),
    UNKNOWN("-");

    val impliesMotion: Boolean get() = this == DRIVE || this == REVERSE
}
