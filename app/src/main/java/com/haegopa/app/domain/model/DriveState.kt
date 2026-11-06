package com.haegopa.app.domain.model

/**
 * 차량 안전 규격(Driver Distraction) 분기 기준.
 * - PARKED : Speed == 0 km/h → 2km 무한 스크롤, 상세, 스트레칭 가이드 등 전체 터치 허용
 * - DRIVING: Speed  > 0 km/h → 스크롤 잠금, 최상단 1개 카드 배너, 음성(TTS) 위주 안내
 */
enum class DriveMode {
    PARKED,
    DRIVING;

    companion object {
        fun fromSpeed(speedKmh: Int): DriveMode = if (speedKmh > 0) DRIVING else PARKED
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
