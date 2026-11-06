package com.haegopa.app.domain.model

/**
 * 차량(현재 위치) 기준 검색 반경.
 * 기본 2km, 빠른 선택 5km · 10km, 사용자 지정 1~20km(0.5km 단위).
 */
object SearchRadius {
    const val DEFAULT_METERS = 2_000
    val PRESETS: List<Int> = listOf(2_000, 5_000, 10_000)

    const val CUSTOM_MIN_METERS = 1_000
    const val CUSTOM_MAX_METERS = 20_000
    const val CUSTOM_STEP_METERS = 500

    fun isPreset(meters: Int): Boolean = meters in PRESETS

    /** 사용자 지정 값을 0.5km 단위로 맞추고 허용 범위로 자른다 */
    fun normalizeCustom(meters: Int): Int {
        val stepped = ((meters + CUSTOM_STEP_METERS / 2) / CUSTOM_STEP_METERS) * CUSTOM_STEP_METERS
        return stepped.coerceIn(CUSTOM_MIN_METERS, CUSTOM_MAX_METERS)
    }
}
