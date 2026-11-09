package com.haegopa.app.domain.model

/**
 * 해고파 3대 카테고리 — "배고파? 먹고파! 심심해? 놀고파! 지쳤어? 쉬고파!"
 * - 먹고파: 식당 맛집 · 카페/디저트 맛집
 * - 놀고파: 공원 · 백화점 · 놀이공원 · 명소 · 랜드마크 — 가서 "할 거리"
 * - 쉬고파: 산책로 · 공원(돗자리 · 차박) · 목욕탕 · 사우나 · 마사지 — 진짜 쉬러 가는 곳
 */
enum class SpotCategory(
    val label: String,
    val question: String,
    val answer: String,
) {
    EAT("먹고파", "배고파?", "먹고파!"),
    PLAY("놀고파", "심심해?", "놀고파!"),
    REST("쉬고파", "지쳤어?", "쉬고파!"),
}

/**
 * 카드에 노출되는 태그. [isKind] 가 true 인 태그는 스팟의 "종류"(맛집 · 공원 · 사우나 …)로,
 * 카드에서 맨 앞에 진하게 보이고 도슨트 · 스트레칭 같은 부가 기능을 정한다.
 */
enum class SpotTag(val label: String, val isKind: Boolean = false) {
    // 먹고파 종류
    RESTAURANT("맛집", isKind = true),
    CAFE("카페", isKind = true),
    DESSERT("디저트", isKind = true),

    // 놀고파 종류
    PARK("공원", isKind = true),
    DEPARTMENT_STORE("백화점", isKind = true),
    THEME_PARK("놀이공원", isKind = true),
    ATTRACTION("명소", isKind = true),
    LANDMARK("랜드마크", isKind = true),

    // 쉬고파 종류 (공원은 놀고파와 같은 태그 — 쉬고파에서는 돗자리 · 차박 위주)
    TRAIL("산책로", isKind = true),
    BATHHOUSE("목욕탕", isKind = true),
    SAUNA("사우나", isKind = true),
    MASSAGE("마사지", isKind = true),

    // 특징
    PICNIC("돗자리"),
    CAR_CAMPING("차박"),
    TAKEOUT("포장"),
    VIEW("뷰맛집"),
    NIGHT_VIEW("야경"),
    HIDDEN("숨은명소"),
    INDOOR("실내"),
    FAMILY("동승자추천"),
    PARKING("주차가능"),
    TOILET("화장실"),
    SLEEP_ROOM("수면실"),
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
    /** 첫 태그가 종류(맛집 · 공원 · 사우나 …), 나머지는 특징 */
    val tags: List<SpotTag>,
    /** 놀고파 명소 · 랜드마크의 음성 소개(도슨트) — 화면 버튼 이름은 "소개 듣기" */
    val docentScript: String? = null,
) {
    val kind: SpotTag? get() = tags.firstOrNull { it.isKind }
    val hasDocent: Boolean get() = docentScript != null

    /** 1분 스트레칭은 걷거나 자리를 펴는 쉬고파(산책로 · 공원)에서만 권한다 — 사우나 · 마사지는 제외 */
    val supportsStretching: Boolean
        get() = category == SpotCategory.REST && (kind == SpotTag.TRAIL || kind == SpotTag.PARK)
}

data class SpotPage(
    val items: List<Spot>,
    val page: Int,
    val hasMore: Boolean,
    val totalInRadius: Int,
)
