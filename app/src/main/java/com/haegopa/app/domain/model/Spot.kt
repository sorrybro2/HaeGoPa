package com.haegopa.app.domain.model

/** 해고파 3대 카테고리 — "배고파? 먹고파! 심심해? 놀고파! 지쳤어? 쉬고파!" */
enum class SpotCategory(
    val emoji: String,
    val label: String,
    val question: String,
    val answer: String,
) {
    EAT("🍽️", "먹고파", "배고파?", "먹고파!"),
    PLAY("🎡", "놀고파", "심심해?", "놀고파!"),
    REST("☕", "쉬고파", "지쳤어?", "쉬고파!"),
}

/** 카드에 노출되는 특징 태그 */
enum class SpotTag(val label: String) {
    DRIVE_THRU("DT"),
    TAKEOUT("포장"),
    PICKUP("픽업"),
    CAFE("카페"),
    VIEW("뷰맛집"),
    NIGHT_VIEW("야경"),
    DRIVE_COURSE("드라이브코스"),
    HIDDEN("숨은명소"),
    LANDMARK("랜드마크"),
    DOCENT("도슨트"),
    FAMILY("동승자추천"),
    PICNIC("피크닉"),
    DROWSY_SHELTER("졸음쉼터"),
    REST_AREA("휴게소"),
    STRETCH("스트레칭"),
    PARKING("주차가능"),
    TOILET("화장실"),
    EV_CHARGING("EV충전"),
    NAP("파워냅"),
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    companion object {
        /** NaviHelper 현재 위치를 받지 못했을 때 쓰는 가상 현재 위치 (판교 테크노밸리 부근) */
        val DEFAULT_MOCK_ORIGIN = GeoPoint(37.3947, 127.1112)
    }
}

data class Spot(
    val id: String,
    val category: SpotCategory,
    val name: String,
    val summary: String,
    /** 실제 주소 대신 "현재 위치 기준 방향·거리" 설명 (더미 데이터) */
    val address: String,
    val location: GeoPoint,
    val distanceMeters: Int,
    /** 현재 위치 → 스팟 예상 소요 시간 (도심 평균 24km/h 가정) */
    val etaMinutes: Int,
    /** 기존 경로 대비 우회 시간 (먹고파는 5분 이내만 노출) */
    val detourMinutes: Int,
    val tags: List<SpotTag>,
    /** 놀고파 스팟의 TTS 도슨트 해설 */
    val docentScript: String? = null,
) {
    val hasDocent: Boolean get() = docentScript != null
    val supportsStretching: Boolean get() = category == SpotCategory.REST
}

data class SpotPage(
    val items: List<Spot>,
    val page: Int,
    val hasMore: Boolean,
    val totalInRadius: Int,
)
