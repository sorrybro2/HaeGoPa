package com.haegopa.app.data

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotCategory.EAT
import com.haegopa.app.domain.model.SpotCategory.PLAY
import com.haegopa.app.domain.model.SpotCategory.REST
import com.haegopa.app.domain.model.SpotPage
import com.haegopa.app.domain.model.SpotTag
import com.haegopa.app.domain.model.SpotTag.CAFE
import com.haegopa.app.domain.model.SpotTag.DOCENT
import com.haegopa.app.domain.model.SpotTag.DRIVE_COURSE
import com.haegopa.app.domain.model.SpotTag.DRIVE_THRU
import com.haegopa.app.domain.model.SpotTag.DROWSY_SHELTER
import com.haegopa.app.domain.model.SpotTag.EV_CHARGING
import com.haegopa.app.domain.model.SpotTag.FAMILY
import com.haegopa.app.domain.model.SpotTag.HIDDEN
import com.haegopa.app.domain.model.SpotTag.LANDMARK
import com.haegopa.app.domain.model.SpotTag.NAP
import com.haegopa.app.domain.model.SpotTag.NIGHT_VIEW
import com.haegopa.app.domain.model.SpotTag.PARKING
import com.haegopa.app.domain.model.SpotTag.PICKUP
import com.haegopa.app.domain.model.SpotTag.PICNIC
import com.haegopa.app.domain.model.SpotTag.REST_AREA
import com.haegopa.app.domain.model.SpotTag.STRETCH
import com.haegopa.app.domain.model.SpotTag.TAKEOUT
import com.haegopa.app.domain.model.SpotTag.TOILET
import com.haegopa.app.domain.model.SpotTag.VIEW
import com.haegopa.app.domain.repository.SpotRepository
import com.haegopa.app.domain.util.GeoUtils
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 더미 POI 저장소: 먹고파 · 놀고파 · 쉬고파 각 15곳(반경 2km 안) + 2~18km 원거리 스팟 + 우회 초과 검증용 샘플.
 * 검색 반경(2 · 5 · 10km · 사용자 지정)에 따라 포함되는 스팟이 달라진다.
 *
 * 스팟은 "현재 위치 기준 거리·방위"로 정의되어 있어, NaviHelper 가 알려준 실제 현재 위치든
 * 가상 위치(판교)든 항상 같은 상대 배치로 나타난다. 상호명은 모두 가상의 이름이다.
 */
class MockDataRepository(
    private val latencyMillis: Long = DEFAULT_LATENCY_MS,
) : SpotRepository {

    private val cache = HashMap<GeoPoint, List<Spot>>()

    override suspend fun getNearbySpots(
        category: SpotCategory,
        origin: GeoPoint,
        page: Int,
        pageSize: Int,
        radiusMeters: Int,
    ): SpotPage {
        require(page >= 0 && pageSize > 0)
        if (latencyMillis > 0) delay(latencyMillis) // 네트워크 페이지 로딩 흉내 (무한 스크롤 로딩 표시)

        val inRadius = materialize(origin)
            .asSequence()
            .filter { it.category == category }
            .filter { it.distanceMeters <= radiusMeters }
            .filter { it.category != EAT || it.detourMinutes <= MAX_EAT_DETOUR_MINUTES }
            .sortedBy { it.distanceMeters }
            .toList()

        val from = page * pageSize
        val items = inRadius.drop(from).take(pageSize)
        return SpotPage(
            items = items,
            page = page,
            hasMore = from + items.size < inRadius.size,
            totalInRadius = inRadius.size,
        )
    }

    private fun materialize(origin: GeoPoint): List<Spot> = synchronized(cache) {
        cache.getOrPut(origin) { SEEDS.map { it.toSpot(origin) } }
    }

    private data class SpotSeed(
        val id: String,
        val category: SpotCategory,
        val name: String,
        val summary: String,
        val distanceMeters: Int,
        val bearingDegrees: Double,
        val tags: List<SpotTag>,
        val detourMinutes: Int = 0,
        val docent: String? = null,
    ) {
        fun toSpot(origin: GeoPoint): Spot {
            val location = GeoUtils.offset(origin, distanceMeters.toDouble(), bearingDegrees)
            val distance = GeoUtils.distanceMeters(origin, location).roundToInt()
            return Spot(
                id = id,
                category = category,
                name = name,
                summary = summary,
                address = "현재 위치 ${GeoUtils.bearingToKorean(bearingDegrees)}쪽 ${GeoUtils.formatDistance(distance)} (가상 좌표)",
                location = location,
                distanceMeters = distance,
                etaMinutes = GeoUtils.etaMinutes(distance),
                detourMinutes = detourMinutes,
                tags = tags,
                docentScript = docent,
            )
        }
    }

    companion object {
        const val DEFAULT_LATENCY_MS = 350L
        const val MAX_EAT_DETOUR_MINUTES = 5

        private val SEEDS: List<SpotSeed> = listOf(
            // ── 🍽️ 먹고파: 경로 우회 5분 이내 픽업/포장 · 드라이브스루 ────────────────────────
            SpotSeed("e01", EAT, "버거 스테이션 DT", "주문부터 픽업까지 평균 3분, 드라이브스루 버거", 180, 25.0, listOf(DRIVE_THRU, TAKEOUT), detourMinutes = 1),
            SpotSeed("e02", EAT, "아침공방 김밥", "출근길 미리 주문하면 매장 앞에서 차량 픽업", 260, 140.0, listOf(PICKUP, TAKEOUT), detourMinutes = 2),
            SpotSeed("e03", EAT, "커피온 드라이브스루", "아메리카노·샌드위치를 차에서 내리지 않고 주문", 340, 300.0, listOf(DRIVE_THRU, CAFE), detourMinutes = 1),
            SpotSeed("e04", EAT, "골목 떡볶이 픽업존", "전화 주문 후 비상등 켜고 2분 픽업", 410, 60.0, listOf(PICKUP, TAKEOUT), detourMinutes = 2),
            SpotSeed("e05", EAT, "바삭치킨 차량픽업", "앱 주문 시 직원이 차량까지 전달", 520, 200.0, listOf(PICKUP), detourMinutes = 3),
            SpotSeed("e06", EAT, "한그릇 국밥 포장", "뜨끈한 국밥 포장, 흘림 방지 밀폐 용기 제공", 640, 95.0, listOf(TAKEOUT), detourMinutes = 3),
            SpotSeed("e07", EAT, "샐러드랩 테이크아웃", "한 손으로 먹기 좋은 랩과 착즙 주스", 720, 330.0, listOf(TAKEOUT, PICKUP), detourMinutes = 2),
            SpotSeed("e08", EAT, "도넛하우스 DT", "도넛 6구와 커피 드라이브스루 세트", 860, 170.0, listOf(DRIVE_THRU), detourMinutes = 2),
            SpotSeed("e09", EAT, "만두명가 포장", "갓 찐 만두 10분 포장, 동승자 간식으로 인기", 950, 250.0, listOf(TAKEOUT, FAMILY), detourMinutes = 3),
            SpotSeed("e10", EAT, "토스트 킹 픽업", "계란 토스트 1분 컷, 출근길 단골 픽업", 1080, 15.0, listOf(PICKUP, TAKEOUT), detourMinutes = 2),
            SpotSeed("e11", EAT, "주먹밥 공작소", "4개 묶음 포장, 아이와 함께 탈 때 추천", 1210, 120.0, listOf(TAKEOUT, FAMILY), detourMinutes = 4),
            SpotSeed("e12", EAT, "베이글 팩토리 DT", "갓 구운 베이글과 크림치즈 드라이브스루", 1340, 280.0, listOf(DRIVE_THRU), detourMinutes = 3),
            SpotSeed("e13", EAT, "타코 트럭 스테이션", "주차장 상설 푸드트럭, 주차 후 5분 포장", 1490, 45.0, listOf(TAKEOUT, PARKING), detourMinutes = 4),
            SpotSeed("e14", EAT, "누룽지 통닭 포장", "예약 픽업 시 대기 없이 바로 수령", 1650, 215.0, listOf(TAKEOUT, PICKUP), detourMinutes = 5),
            SpotSeed("e15", EAT, "생과일 주스 DT", "졸음 쫓는 상큼한 착즙 주스 드라이브스루", 1820, 350.0, listOf(DRIVE_THRU), detourMinutes = 4),
            // 검증용: 우회 7분 → 항상 제외
            SpotSeed("e16", EAT, "칼국수 본점", "줄 서는 칼국수집 (경로 우회 7분)", 1720, 160.0, listOf(TAKEOUT), detourMinutes = 7),
            // 2km 밖 — 반경을 5km · 10km · 사용자 지정으로 넓히면 나타난다
            SpotSeed("e17", EAT, "수제버거 하우스", "두툼한 패티 수제버거, 전화 주문 후 픽업", 2150, 75.0, listOf(TAKEOUT, PICKUP), detourMinutes = 4),
            SpotSeed("e18", EAT, "피자 플래닛 픽업", "레귤러 피자 15분 완성, 차량 픽업존 운영", 2480, 235.0, listOf(PICKUP), detourMinutes = 5),
            SpotSeed("e19", EAT, "국도변 한우국밥", "국도 바로 옆 24시간 국밥집, 넓은 주차장", 3100, 310.0, listOf(TAKEOUT, PARKING), detourMinutes = 3),
            SpotSeed("e20", EAT, "브런치 드라이브스루", "샌드위치·라떼 세트를 차 안에서 받는 브런치 DT", 3900, 40.0, listOf(DRIVE_THRU, CAFE), detourMinutes = 2),
            SpotSeed("e21", EAT, "손만두 휴게 식당", "직접 빚은 손만두, 동승자 간식 포장", 4600, 190.0, listOf(TAKEOUT, FAMILY), detourMinutes = 4),
            SpotSeed("e22", EAT, "막국수 정원", "시원한 막국수, 정원 주차장에서 바로 식사", 5800, 260.0, listOf(PARKING), detourMinutes = 5),
            SpotSeed("e23", EAT, "베이커리 팜 DT", "농장 직영 빵집 드라이브스루", 7200, 120.0, listOf(DRIVE_THRU), detourMinutes = 4),
            SpotSeed("e24", EAT, "어촌 회덮밥 포장", "항구 앞 회덮밥 포장 전문점", 8900, 20.0, listOf(TAKEOUT), detourMinutes = 5),
            SpotSeed("e25", EAT, "고속도로 우동 명가", "휴게소 옆 우동집, 주문 3분이면 완성", 12400, 330.0, listOf(TAKEOUT, PARKING), detourMinutes = 3),
            SpotSeed("e26", EAT, "시골 장터 호떡", "오일장 명물 씨앗호떡 포장", 16800, 150.0, listOf(TAKEOUT, FAMILY), detourMinutes = 5),

            // ── 🎡 놀고파: 랜드마크 · 드라이브 명소 · 숨은 뷰포인트 (TTS 도슨트) ─────────────────
            SpotSeed(
                "p01", PLAY, "노을 전망 언덕", "해 질 녘 서쪽 하늘이 가장 붉게 물드는 숨은 뷰포인트", 230, 270.0, listOf(VIEW, HIDDEN, DOCENT),
                docent = "노을 전망 언덕은 동네 주민들만 알던 작은 언덕이에요. 해 지기 20분 전에 도착하면 하늘이 주황에서 보랏빛으로 바뀌는 순간을 볼 수 있어요. 언덕 아래 주차장에 차를 세우고 3분만 걸어 올라가 보세요.",
            ),
            SpotSeed(
                "p02", PLAY, "벽화 골목 저속 드라이브", "시속 20km로 천천히 즐기는 알록달록 벽화 길", 380, 30.0, listOf(DRIVE_COURSE, FAMILY, DOCENT),
                docent = "이 골목은 오래된 담장을 동네 예술가들이 함께 칠해 만든 길이에요. 고래 그림을 지나면 계절마다 바뀌는 그림이 이어져요. 보행자가 많으니 아주 천천히 지나가 주세요.",
            ),
            SpotSeed(
                "p03", PLAY, "강변 전망 데크", "물멍하기 좋은 강변 데크, 주차장 바로 옆", 470, 160.0, listOf(VIEW, PARKING, DOCENT),
                docent = "강변 전망 데크는 물길이 크게 휘어지는 곳에 있어 양쪽 강변이 한눈에 들어와요. 바람 부는 오후엔 물결이 반짝여 윤슬 맛집으로 불려요.",
            ),
            SpotSeed(
                "p04", PLAY, "옛 철길 숲길", "기차가 다니던 철길을 걷는 숲 산책로", 590, 110.0, listOf(LANDMARK, DOCENT),
                docent = "이곳은 예전에 화물 열차가 다니던 철길이었어요. 운행이 멈춘 뒤 레일 일부를 남겨 숲길로 바꿨답니다. 침목 사이를 세며 걸으면 옛 기찻길의 리듬이 느껴져요.",
            ),
            SpotSeed(
                "p05", PLAY, "별빛 야경 전망대", "주차장에서 바로 보이는 도시 야경", 710, 330.0, listOf(NIGHT_VIEW, PARKING, DOCENT),
                docent = "별빛 야경 전망대는 차에서 내리지 않아도 도시 불빛이 보이는 드문 장소예요. 맑은 날 밤 9시 무렵이 가장 화려하고, 시동을 끄고 창문을 내리면 바람 소리도 좋아요.",
            ),
            SpotSeed(
                "p06", PLAY, "은행나무 가로수길", "가을이면 노란 터널이 되는 드라이브 코스", 830, 75.0, listOf(DRIVE_COURSE, VIEW, DOCENT),
                docent = "은행나무 가로수길은 약 1킬로미터 내내 나무가 양쪽에서 맞닿아 터널을 만들어요. 가을 단풍철에는 천천히 달리며 창밖을 감상해 보세요.",
            ),
            SpotSeed(
                "p07", PLAY, "야외 조각 공원", "잔디 위 대형 조각 사이를 걷는 산책 코스", 960, 210.0, listOf(LANDMARK, FAMILY, DOCENT),
                docent = "야외 조각 공원에는 잔디 위에 커다란 조각 작품들이 놓여 있어요. 아이들은 작품 사이 숨바꼭질을, 어른들은 그늘 벤치에서 잠깐 쉬어 가기 좋아요.",
            ),
            SpotSeed(
                "p08", PLAY, "호수 둘레길 뷰포인트", "잔잔한 호수에 비친 반영 사진 명소", 1100, 300.0, listOf(VIEW, HIDDEN, DOCENT),
                docent = "호수 둘레길 뷰포인트는 바람이 잦아드는 이른 아침에 호수가 거울처럼 변해요. 건너편 산 능선이 물에 그대로 비쳐 반영 사진 명소로 꼽혀요.",
            ),
            SpotSeed(
                "p09", PLAY, "레트로 오락실 거리", "동승자와 함께 즐기는 추억의 오락실 골목", 1230, 135.0, listOf(FAMILY, LANDMARK, DOCENT),
                docent = "레트로 오락실 거리에는 옛날 게임기와 뽑기 기계가 모여 있어요. 동승자와 함께 잠깐 들러 추억 한 판, 운전 피로도 함께 풀어 보세요.",
            ),
            SpotSeed(
                "p10", PLAY, "숲속 피크닉 잔디광장", "트렁크 피크닉이 가능한 넓은 잔디밭", 1370, 20.0, listOf(PICNIC, PARKING, DOCENT),
                docent = "숲속 피크닉 잔디광장은 주차장과 잔디밭이 바로 붙어 있어 트렁크를 열고 피크닉을 즐길 수 있어요. 주말 오전에 가면 그늘 자리를 잡기 쉬워요.",
            ),
            SpotSeed(
                "p11", PLAY, "천문대 언덕", "맑은 밤 별자리를 찾아보는 관측 명소", 1500, 250.0, listOf(NIGHT_VIEW, HIDDEN, DOCENT),
                docent = "천문대 언덕은 주변에 높은 건물이 없어 맑은 밤이면 별자리가 또렷하게 보여요. 겨울엔 오리온자리를 먼저 찾아보세요.",
            ),
            SpotSeed(
                "p12", PLAY, "드라이브인 시어터 광장", "주말 밤 차 안에서 보는 야외 상영", 1640, 95.0, listOf(LANDMARK, FAMILY, DOCENT),
                docent = "드라이브인 시어터 광장에서는 주말 밤마다 대형 스크린으로 영화를 틀어 줘요. 소리는 차량 라디오 주파수로 들을 수 있어요.",
            ),
            SpotSeed(
                "p13", PLAY, "연꽃 습지 생태공원", "여름 아침 연꽃이 피는 습지 데크길", 1760, 185.0, listOf(HIDDEN, VIEW, DOCENT),
                docent = "연꽃 습지 생태공원은 여름 아침 일찍 연꽃이 활짝 피었다가 한낮이면 오므라들어요. 데크길을 따라 걸으면 개구리 소리가 배경음악이 되어 줘요.",
            ),
            SpotSeed(
                "p14", PLAY, "다리 위 야경 포인트", "조명이 켜지는 다리를 건너는 야경 드라이브", 1880, 345.0, listOf(NIGHT_VIEW, DRIVE_COURSE, DOCENT),
                docent = "다리 위 야경 포인트는 해가 지면 다리 조명이 차례로 켜지는 곳이에요. 다리 위에서는 정차하지 말고, 건넌 뒤 강변 쉼터에서 감상해 주세요.",
            ),
            SpotSeed(
                "p15", PLAY, "구름다리 전망대", "산자락을 잇는 구름다리에서 보는 시내 전경", 1960, 60.0, listOf(VIEW, LANDMARK, DOCENT),
                docent = "구름다리 전망대는 두 산자락을 잇는 출렁다리 위에서 시내를 내려다보는 곳이에요. 바람이 세게 불면 살짝 흔들리니 난간을 잡고 건너세요.",
            ),
            // 2km 밖 — 반경을 넓히면 나타난다
            SpotSeed(
                "p16", PLAY, "해안 일주 도로", "바다를 끼고 달리는 시원한 해안 드라이브", 2300, 120.0, listOf(DRIVE_COURSE, VIEW, DOCENT),
                docent = "해안 일주 도로는 바다를 오른쪽에 끼고 달리는 드라이브 코스예요. 창문을 조금 내리면 파도 소리가 들려요. 전망 쉼터마다 잠깐 세워 바다를 보고 가세요.",
            ),
            SpotSeed(
                "p17", PLAY, "고분 역사 공원", "천오백 년 전 고분 사이를 걷는 역사 산책길", 3300, 80.0, listOf(LANDMARK, FAMILY, DOCENT),
                docent = "고분 역사 공원에는 천오백 년 전에 만들어진 둥근 무덤들이 언덕처럼 이어져 있어요. 해 질 녘 고분 능선 위로 지는 해가 특히 아름다워요.",
            ),
            SpotSeed(
                "p18", PLAY, "억새 언덕 드라이브", "가을이면 은빛 억새가 출렁이는 언덕길", 4400, 220.0, listOf(DRIVE_COURSE, VIEW, DOCENT),
                docent = "억새 언덕은 가을이면 언덕 전체가 은빛으로 출렁여요. 바람이 부는 날 천천히 달리면 억새가 파도처럼 일렁이는 모습을 볼 수 있어요.",
            ),
            SpotSeed(
                "p19", PLAY, "폐광 동굴 테마파크", "옛 광산 갱도를 개조한 동굴 테마파크", 6100, 300.0, listOf(LANDMARK, FAMILY, DOCENT),
                docent = "폐광 동굴 테마파크는 문을 닫은 광산 갱도를 빛과 소리의 동굴로 바꾼 곳이에요. 안은 한여름에도 서늘하니 겉옷을 챙기세요.",
            ),
            SpotSeed(
                "p20", PLAY, "등대 전망 방파제", "빨간 등대까지 이어지는 방파제 야경", 8300, 170.0, listOf(VIEW, NIGHT_VIEW, DOCENT),
                docent = "등대 전망 방파제 끝에는 빨간 등대가 서 있어요. 밤이 되면 등대 불빛과 항구 조명이 바다에 길게 비쳐요.",
            ),
            SpotSeed(
                "p21", PLAY, "수목원 숲 터널", "메타세쿼이아가 하늘을 덮는 숲 터널", 11200, 40.0, listOf(VIEW, PICNIC, DOCENT),
                docent = "수목원 숲 터널은 키 큰 메타세쿼이아가 양쪽에서 하늘을 덮는 길이에요. 숲 냄새를 맡으며 천천히 걸으면 운전 피로가 풀려요.",
            ),
            SpotSeed(
                "p22", PLAY, "댐 전망 쉼터", "거대한 댐과 호수를 내려다보는 전망 쉼터", 15600, 280.0, listOf(VIEW, LANDMARK, DOCENT),
                docent = "댐 전망 쉼터에서는 높은 댐 아래로 넓은 호수가 펼쳐져요. 방류하는 날에는 하얀 물보라가 장관을 이뤄요.",
            ),

            // ── ☕ 쉬고파: 졸음쉼터 · 휴게소 · 뷰 카페 (1분 힐링 스트레칭) ───────────────────────
            SpotSeed("r01", REST, "솔바람 졸음쉼터", "그늘막·화장실 완비, 10분 눈 붙이기 좋은 쉼터", 300, 5.0, listOf(DROWSY_SHELTER, TOILET, STRETCH)),
            SpotSeed("r02", REST, "숲뷰 카페 쉼표", "통창 너머 숲 뷰, 디카페인 메뉴 있음", 420, 225.0, listOf(CAFE, VIEW)),
            SpotSeed("r03", REST, "하늘정원 휴게소", "옥상 정원 산책로와 푸드코트가 있는 휴게소", 560, 90.0, listOf(REST_AREA, TOILET, STRETCH)),
            SpotSeed("r04", REST, "공영주차장 쉼터", "30분 무료 주차, 벤치에서 스트레칭", 680, 155.0, listOf(PARKING, STRETCH)),
            SpotSeed("r05", REST, "호숫가 테라스 카페", "호수 바람 맞으며 쉬는 테라스 좌석", 790, 315.0, listOf(CAFE, VIEW)),
            SpotSeed("r06", REST, "느티나무 졸음쉼터", "큰 느티나무 그늘 아래 조용한 쉼터", 910, 50.0, listOf(DROWSY_SHELTER, STRETCH)),
            SpotSeed("r07", REST, "EV 충전 라운지", "충전하는 동안 쉬어 가는 라운지 카페", 1030, 265.0, listOf(EV_CHARGING, CAFE, TOILET)),
            SpotSeed("r08", REST, "북카페 책갈피", "조용한 1인석에서 15분 독서 휴식", 1160, 130.0, listOf(CAFE)),
            SpotSeed("r09", REST, "파워냅 라운지", "리클라이너에서 15분 낮잠, 시간제 이용", 1290, 345.0, listOf(NAP, TOILET)),
            SpotSeed("r10", REST, "강변 졸음쉼터", "강바람 부는 쉼터, 체조 안내판 설치", 1410, 190.0, listOf(DROWSY_SHELTER, STRETCH, TOILET)),
            SpotSeed("r11", REST, "루프탑 뷰 카페 구름", "옥상 정원에서 내려다보는 시내 풍경", 1540, 70.0, listOf(CAFE, VIEW)),
            SpotSeed("r12", REST, "족욕 카페 온기", "따뜻한 족욕으로 다리 피로 풀기", 1670, 240.0, listOf(CAFE)),
            SpotSeed("r13", REST, "고개마루 졸음쉼터", "고갯마루 전망 쉼터, 자판기 있음", 1780, 10.0, listOf(DROWSY_SHELTER, VIEW)),
            SpotSeed("r14", REST, "숲속 명상 쉼터", "새소리 들으며 1분 호흡 명상", 1890, 290.0, listOf(STRETCH, VIEW)),
            SpotSeed("r15", REST, "언덕 위 휴게소", "주유·편의점·화장실 원스톱 휴게소", 1990, 115.0, listOf(REST_AREA, TOILET, EV_CHARGING)),
            // 2km 밖 — 반경을 넓히면 나타난다
            SpotSeed("r16", REST, "상행선 휴게소", "주유·전기차 충전·푸드코트를 갖춘 대형 휴게소", 2600, 30.0, listOf(REST_AREA, TOILET, EV_CHARGING)),
            SpotSeed("r17", REST, "산림욕 졸음쉼터", "편백나무 숲 사이 조용한 졸음쉼터", 3500, 140.0, listOf(DROWSY_SHELTER, STRETCH)),
            SpotSeed("r18", REST, "드라이브인 카페 바람", "차에 탄 채 주문하고 전망 주차칸에서 쉬는 카페", 4700, 350.0, listOf(CAFE, VIEW)),
            SpotSeed("r19", REST, "하늘길 졸음쉼터", "화장실·운동기구가 있는 고갯길 쉼터", 6400, 60.0, listOf(DROWSY_SHELTER, TOILET, STRETCH)),
            SpotSeed("r20", REST, "온천 족욕 휴게소", "온천수 족욕장이 있는 휴게소", 9100, 230.0, listOf(REST_AREA, TOILET)),
            SpotSeed("r21", REST, "고속도로 졸음쉼터", "본선 바로 옆, 10분 쪽잠에 좋은 쉼터", 13300, 15.0, listOf(DROWSY_SHELTER, TOILET)),
            SpotSeed("r22", REST, "호숫가 캠핑 쉼터", "호숫가 그늘에서 파워냅, 차박 가능", 17900, 200.0, listOf(VIEW, NAP)),
        )
    }
}
