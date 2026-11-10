package com.haegopa.app.data

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotCategory.EAT
import com.haegopa.app.domain.model.SpotCategory.PLAY
import com.haegopa.app.domain.model.SpotCategory.REST
import com.haegopa.app.domain.model.SpotPage
import com.haegopa.app.domain.model.SpotTag
import com.haegopa.app.domain.model.SpotTag.ATTRACTION
import com.haegopa.app.domain.model.SpotTag.BATHHOUSE
import com.haegopa.app.domain.model.SpotTag.CAFE
import com.haegopa.app.domain.model.SpotTag.CAR_CAMPING
import com.haegopa.app.domain.model.SpotTag.DEPARTMENT_STORE
import com.haegopa.app.domain.model.SpotTag.DESSERT
import com.haegopa.app.domain.model.SpotTag.FAMILY
import com.haegopa.app.domain.model.SpotTag.HIDDEN
import com.haegopa.app.domain.model.SpotTag.INDOOR
import com.haegopa.app.domain.model.SpotTag.LANDMARK
import com.haegopa.app.domain.model.SpotTag.MASSAGE
import com.haegopa.app.domain.model.SpotTag.NIGHT_VIEW
import com.haegopa.app.domain.model.SpotTag.PARK
import com.haegopa.app.domain.model.SpotTag.PARKING
import com.haegopa.app.domain.model.SpotTag.PICNIC
import com.haegopa.app.domain.model.SpotTag.RESTAURANT
import com.haegopa.app.domain.model.SpotTag.SAUNA
import com.haegopa.app.domain.model.SpotTag.SLEEP_ROOM
import com.haegopa.app.domain.model.SpotTag.TAKEOUT
import com.haegopa.app.domain.model.SpotTag.THEME_PARK
import com.haegopa.app.domain.model.SpotTag.TOILET
import com.haegopa.app.domain.model.SpotTag.TRAIL
import com.haegopa.app.domain.model.SpotTag.VIEW
import com.haegopa.app.domain.repository.SpotRepository
import com.haegopa.app.domain.util.GeoUtils
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 더미 POI 저장소: 먹고파 · 놀고파 · 쉬고파 각 15곳(반경 2km 안) + 2~18km 원거리 스팟 + 우회 초과 검증용 샘플.
 * - 먹고파: 식당 맛집 · 카페/디저트 맛집
 * - 놀고파: 공원 · 백화점 · 놀이공원 · 명소 · 랜드마크 (명소 · 랜드마크는 TTS 도슨트)
 * - 쉬고파: 산책로 · 공원(돗자리 · 차박) · 목욕탕 · 사우나 · 마사지 (산책로 · 공원은 1분 스트레칭)
 * 검색 반경(2 · 5 · 10km · 사용자 지정)에 따라 포함되는 스팟이 달라진다.
 *
 * 스팟은 "기준점 기준 거리·방위"로 정의되어 있어, NaviHelper 가 알려준 실제 출발 위치든
 * 가상 위치(판교)든 항상 같은 상대 배치로 깔린다. 상호명은 모두 가상의 이름이다.
 * 각 스팟의 첫 태그는 종류(맛집 · 공원 · 사우나 …)다.
 *
 * 차가 움직여도 스팟은 제자리에 있어야 하므로, [anchorAt] 으로 정한 기준점(출발 위치)에 한 번 깔고
 * 검색할 때마다 차 위치(검색 중심)에서 거리 · 방향 · 소요 시간을 다시 잰다. 기준점을 안 정했으면 검색 중심에 깐다.
 */
class MockDataRepository(
    private val latencyMillis: Long = DEFAULT_LATENCY_MS,
) : SpotRepository {

    private val cache = HashMap<GeoPoint, List<Spot>>()

    @Volatile
    private var anchor: GeoPoint? = null

    override fun anchorAt(point: GeoPoint) {
        anchor = point
    }

    override suspend fun getNearbySpots(
        category: SpotCategory,
        origin: GeoPoint,
        page: Int,
        pageSize: Int,
        radiusMeters: Int,
    ): SpotPage {
        require(page >= 0 && pageSize > 0)
        if (latencyMillis > 0) delay(latencyMillis) // 네트워크 페이지 로딩 흉내 (무한 스크롤 로딩 표시)

        val inRadius = materialize(anchor ?: origin)
            .asSequence()
            .filter { it.category == category }
            .map { it.measuredFrom(origin) }
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

    private fun materialize(anchor: GeoPoint): List<Spot> = synchronized(cache) {
        cache.getOrPut(anchor) { SEEDS.map { it.toSpot(anchor) } }
    }

    /** 검색 중심(차 위치)에서 잰 거리 · 방향 · 소요 시간 */
    private fun Spot.measuredFrom(center: GeoPoint): Spot {
        val distance = GeoUtils.distanceMeters(center, location).roundToInt()
        val bearing = GeoUtils.bearingDegrees(center, location)
        return copy(
            distanceMeters = distance,
            etaMinutes = GeoUtils.etaMinutes(distance),
            address = "현재 위치 ${GeoUtils.bearingToKorean(bearing)}쪽 ${GeoUtils.formatDistance(distance)} (가상 좌표)",
        )
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
            // ── 🍽️ 먹고파: 식당 맛집 · 카페/디저트 맛집 (경로 우회 5분 이내) ──────────────────
            SpotSeed("e01", EAT, "골목 손칼국수", "직접 뽑은 면에 바지락 듬뿍, 동네에서 줄 서는 칼국수집", 180, 25.0, listOf(RESTAURANT, PARKING), detourMinutes = 1),
            SpotSeed("e02", EAT, "온기 베이커리", "아침 8시에 갓 구운 소금빵이 나오는 동네 빵집", 260, 140.0, listOf(DESSERT, TAKEOUT), detourMinutes = 2),
            SpotSeed("e03", EAT, "로스터리 숲", "직접 볶은 원두로 내리는 핸드드립, 통창 너머 숲 뷰", 340, 300.0, listOf(CAFE, VIEW), detourMinutes = 1),
            SpotSeed("e04", EAT, "할매 순두부", "새벽에 만든 손두부로 끓이는 얼큰 순두부찌개", 410, 60.0, listOf(RESTAURANT, FAMILY), detourMinutes = 2),
            SpotSeed("e05", EAT, "숯불 닭갈비 골목집", "참숯에 굽는 닭갈비, 마무리는 볶음밥", 520, 200.0, listOf(RESTAURANT, PARKING), detourMinutes = 3),
            SpotSeed("e06", EAT, "젤라또 스튜디오", "매일 아침 만드는 제철 과일 젤라또", 640, 95.0, listOf(DESSERT, TAKEOUT), detourMinutes = 3),
            SpotSeed("e07", EAT, "한그릇 국밥", "밤새 끓인 진한 돼지국밥, 혼밥 좌석 있음", 720, 330.0, listOf(RESTAURANT), detourMinutes = 2),
            SpotSeed("e08", EAT, "크로플 공방", "겉바속촉 크로플에 아이스크림 한 스쿱", 860, 170.0, listOf(DESSERT, FAMILY), detourMinutes = 2),
            SpotSeed("e09", EAT, "정원 브런치 카페", "잔디 정원이 보이는 브런치와 라떼", 950, 250.0, listOf(CAFE, VIEW, PARKING), detourMinutes = 3),
            SpotSeed("e10", EAT, "수제 돈까스 연구소", "두툼한 등심 돈까스, 아이 메뉴 있음", 1080, 15.0, listOf(RESTAURANT, FAMILY), detourMinutes = 2),
            SpotSeed("e11", EAT, "팥빙수 이층집", "국산 팥을 직접 쑤는 사계절 빙수집", 1210, 120.0, listOf(DESSERT), detourMinutes = 4),
            SpotSeed("e12", EAT, "골목 파스타", "8석 남짓한 오픈 키친 파스타집", 1340, 280.0, listOf(RESTAURANT), detourMinutes = 3),
            SpotSeed("e13", EAT, "루프탑 카페 구름", "옥상에서 내려다보는 시내 풍경과 시그니처 라떼", 1490, 45.0, listOf(CAFE, VIEW), detourMinutes = 4),
            SpotSeed("e14", EAT, "평양냉면 옥류", "슴슴한 육수의 평양냉면과 녹두전", 1650, 215.0, listOf(RESTAURANT, PARKING), detourMinutes = 5),
            SpotSeed("e15", EAT, "마카롱 상점", "색색의 수제 마카롱 12종, 선물 포장 가능", 1820, 350.0, listOf(DESSERT, TAKEOUT), detourMinutes = 4),
            // 검증용: 우회 7분 → 항상 제외
            SpotSeed("e16", EAT, "한정식 본가", "예약 필수 한정식 (경로 우회 7분)", 1720, 160.0, listOf(RESTAURANT), detourMinutes = 7),
            // 2km 밖 — 반경을 5km · 10km · 사용자 지정으로 넓히면 나타난다
            SpotSeed("e17", EAT, "수제버거 하우스", "두툼한 패티를 숯불에 굽는 수제버거", 2150, 75.0, listOf(RESTAURANT, TAKEOUT), detourMinutes = 4),
            SpotSeed("e18", EAT, "화덕 피자 플래닛", "화덕에서 90초 만에 굽는 나폴리 피자", 2480, 235.0, listOf(RESTAURANT, FAMILY), detourMinutes = 5),
            SpotSeed("e19", EAT, "국도변 한우국밥", "국도 바로 옆 24시간 국밥집, 넓은 주차장", 3100, 310.0, listOf(RESTAURANT, PARKING), detourMinutes = 3),
            SpotSeed("e20", EAT, "베이커리 팜", "농장 직영 베이커리 카페, 넓은 잔디 마당", 3900, 40.0, listOf(CAFE, DESSERT, PARKING), detourMinutes = 2),
            SpotSeed("e21", EAT, "손만두 명가", "직접 빚은 손만두와 칼칼한 만둣국", 4600, 190.0, listOf(RESTAURANT, TAKEOUT), detourMinutes = 4),
            SpotSeed("e22", EAT, "막국수 정원", "정원 평상에서 먹는 시원한 막국수", 5800, 260.0, listOf(RESTAURANT, PARKING), detourMinutes = 5),
            SpotSeed("e23", EAT, "호숫가 디저트 카페", "호수가 보이는 창가 자리와 수제 케이크", 7200, 120.0, listOf(CAFE, DESSERT, VIEW), detourMinutes = 4),
            SpotSeed("e24", EAT, "어촌 회덮밥", "항구 앞에서 바로 썰어 내는 회덮밥", 8900, 20.0, listOf(RESTAURANT), detourMinutes = 5),
            SpotSeed("e25", EAT, "고개 너머 순대국", "고갯길 아래 40년 된 순대국집", 12400, 330.0, listOf(RESTAURANT, PARKING), detourMinutes = 3),
            SpotSeed("e26", EAT, "시골 장터 호떡", "오일장 명물 씨앗호떡", 16800, 150.0, listOf(DESSERT, TAKEOUT), detourMinutes = 5),

            // ── 🎡 놀고파: 공원 · 백화점 · 놀이공원 · 명소 · 랜드마크 (명소 · 랜드마크는 TTS 도슨트) ──
            SpotSeed(
                "p01", PLAY, "노을 전망 언덕", "해 질 녘 서쪽 하늘이 가장 붉게 물드는 숨은 뷰포인트", 230, 270.0, listOf(ATTRACTION, VIEW, HIDDEN),
                docent = "노을 전망 언덕은 동네 주민들만 알던 작은 언덕이에요. 해 지기 20분 전에 도착하면 하늘이 주황에서 보랏빛으로 바뀌는 순간을 볼 수 있어요. 언덕 아래 주차장에 차를 세우고 3분만 걸어 올라가 보세요.",
            ),
            SpotSeed("p02", PLAY, "중앙 어린이대공원", "분수 광장 · 놀이터 · 자전거 대여까지, 아이랑 놀기 좋은 공원", 380, 30.0, listOf(PARK, FAMILY, PARKING)),
            SpotSeed("p03", PLAY, "하늘 백화점", "비 오는 날에도 OK, 옥상 정원과 주말 팝업 스토어", 470, 160.0, listOf(DEPARTMENT_STORE, INDOOR, PARKING)),
            SpotSeed(
                "p04", PLAY, "레트로 오락실 거리", "동승자와 함께 즐기는 추억의 오락실 골목", 590, 110.0, listOf(ATTRACTION, FAMILY),
                docent = "레트로 오락실 거리에는 옛날 게임기와 뽑기 기계가 모여 있어요. 동승자와 함께 잠깐 들러 추억 한 판, 운전 피로도 함께 풀어 보세요.",
            ),
            SpotSeed(
                "p05", PLAY, "별빛 야경 전망대", "주차장에서 바로 보이는 도시 야경", 710, 330.0, listOf(ATTRACTION, NIGHT_VIEW, PARKING),
                docent = "별빛 야경 전망대는 차에서 내리지 않아도 도시 불빛이 보이는 드문 장소예요. 맑은 날 밤 9시 무렵이 가장 화려하고, 시동을 끄고 창문을 내리면 바람 소리도 좋아요.",
            ),
            SpotSeed(
                "p06", PLAY, "시계탑 광장", "100년 된 붉은 벽돌 시계탑이 서 있는 도심 광장", 830, 75.0, listOf(LANDMARK, FAMILY),
                docent = "시계탑 광장의 붉은 벽돌 시계탑은 백 년 가까이 이 자리를 지켜 왔어요. 매 정시마다 종이 울리고, 저녁이면 탑에 조명이 켜져 광장이 사진 명소가 돼요.",
            ),
            SpotSeed("p07", PLAY, "야외 조각 공원", "잔디 위 대형 조각 사이를 걷는 공원", 960, 210.0, listOf(PARK, FAMILY)),
            SpotSeed("p08", PLAY, "구름나라 놀이공원", "롤러코스터 · 회전목마, 저녁엔 불빛 퍼레이드", 1100, 300.0, listOf(THEME_PARK, FAMILY, PARKING)),
            SpotSeed("p09", PLAY, "별마루 백화점", "쇼핑 · 푸드홀 · 영화관이 한 건물에", 1230, 135.0, listOf(DEPARTMENT_STORE, INDOOR, FAMILY)),
            SpotSeed(
                "p10", PLAY, "옛 시청 근대 건축관", "1930년대 근대 건축을 그대로 살린 옛 시청사", 1370, 20.0, listOf(LANDMARK, HIDDEN),
                docent = "옛 시청 근대 건축관은 1930년대에 지은 시청사를 고쳐 만든 전시관이에요. 아치형 창문과 나무 계단이 그대로 남아 있고, 옥상에 오르면 옛 도심 골목이 한눈에 보여요.",
            ),
            SpotSeed(
                "p11", PLAY, "천문대 언덕", "맑은 밤 별자리를 찾아보는 관측 명소", 1500, 250.0, listOf(ATTRACTION, NIGHT_VIEW, HIDDEN),
                docent = "천문대 언덕은 주변에 높은 건물이 없어 맑은 밤이면 별자리가 또렷하게 보여요. 겨울엔 오리온자리를 먼저 찾아보세요.",
            ),
            SpotSeed(
                "p12", PLAY, "드라이브인 시어터 광장", "주말 밤 차 안에서 보는 야외 상영", 1640, 95.0, listOf(ATTRACTION, FAMILY),
                docent = "드라이브인 시어터 광장에서는 주말 밤마다 대형 스크린으로 영화를 틀어 줘요. 소리는 차량 라디오 주파수로 들을 수 있어요.",
            ),
            SpotSeed("p13", PLAY, "물놀이 숲 공원", "여름엔 바닥분수, 겨울엔 눈썰매장이 열리는 공원", 1760, 185.0, listOf(PARK, FAMILY, PARKING)),
            SpotSeed(
                "p14", PLAY, "다리 위 야경 포인트", "조명이 켜지는 다리를 건너는 야경 드라이브", 1880, 345.0, listOf(ATTRACTION, NIGHT_VIEW),
                docent = "다리 위 야경 포인트는 해가 지면 다리 조명이 차례로 켜지는 곳이에요. 다리 위에서는 정차하지 말고, 건넌 뒤 강변 공원에서 감상해 주세요.",
            ),
            SpotSeed(
                "p15", PLAY, "구름다리 전망대", "산자락을 잇는 구름다리에서 보는 시내 전경", 1960, 60.0, listOf(LANDMARK, VIEW),
                docent = "구름다리 전망대는 두 산자락을 잇는 출렁다리 위에서 시내를 내려다보는 곳이에요. 바람이 세게 불면 살짝 흔들리니 난간을 잡고 건너세요.",
            ),
            // 2km 밖 — 반경을 넓히면 나타난다
            SpotSeed(
                "p16", PLAY, "해안 일주 도로", "바다를 끼고 달리는 시원한 해안 드라이브", 2300, 120.0, listOf(ATTRACTION, VIEW),
                docent = "해안 일주 도로는 바다를 오른쪽에 끼고 달리는 드라이브 코스예요. 창문을 조금 내리면 파도 소리가 들려요. 전망 주차장마다 잠깐 세워 바다를 보고 가세요.",
            ),
            SpotSeed(
                "p17", PLAY, "고분 역사 공원", "천오백 년 전 고분 사이를 걷는 역사 산책길", 3300, 80.0, listOf(LANDMARK, FAMILY),
                docent = "고분 역사 공원에는 천오백 년 전에 만들어진 둥근 무덤들이 언덕처럼 이어져 있어요. 해 질 녘 고분 능선 위로 지는 해가 특히 아름다워요.",
            ),
            SpotSeed("p18", PLAY, "바다 놀이동산", "대관람차에서 바다가 내려다보이는 놀이동산", 4400, 220.0, listOf(THEME_PARK, FAMILY, VIEW)),
            SpotSeed(
                "p19", PLAY, "폐광 동굴 탐험길", "옛 광산 갱도를 빛과 소리로 꾸민 동굴", 6100, 300.0, listOf(ATTRACTION, FAMILY),
                docent = "폐광 동굴 탐험길은 문을 닫은 광산 갱도를 빛과 소리의 동굴로 바꾼 곳이에요. 안은 한여름에도 서늘하니 겉옷을 챙기세요.",
            ),
            SpotSeed(
                "p20", PLAY, "등대 전망 방파제", "빨간 등대까지 이어지는 방파제 야경", 8300, 170.0, listOf(LANDMARK, VIEW, NIGHT_VIEW),
                docent = "등대 전망 방파제 끝에는 빨간 등대가 서 있어요. 밤이 되면 등대 불빛과 항구 조명이 바다에 길게 비쳐요.",
            ),
            SpotSeed("p21", PLAY, "들녘 백화점 아울렛", "교외 대형 아울렛, 주말 드라이브 쇼핑", 11200, 40.0, listOf(DEPARTMENT_STORE, INDOOR, PARKING)),
            SpotSeed(
                "p22", PLAY, "댐 전망대", "거대한 댐과 호수를 내려다보는 전망대", 15600, 280.0, listOf(LANDMARK, VIEW),
                docent = "댐 전망대에서는 높은 댐 아래로 넓은 호수가 펼쳐져요. 방류하는 날에는 하얀 물보라가 장관을 이뤄요.",
            ),

            // ── 🌿 쉬고파: 산책로 · 공원(돗자리 · 차박) · 목욕탕 · 사우나 · 마사지 ────────────────
            SpotSeed("r01", REST, "옛 철길 숲길", "기차가 다니던 철길을 따라 걷는 숲 산책로", 300, 5.0, listOf(TRAIL, PARKING)),
            SpotSeed("r02", REST, "행복 목욕탕", "40년 된 동네 목욕탕, 열탕 · 냉탕 · 세신", 420, 225.0, listOf(BATHHOUSE, PARKING)),
            SpotSeed("r03", REST, "강변 잔디공원", "강바람 맞으며 돗자리 펴기 좋은 넓은 잔디밭", 560, 90.0, listOf(PARK, PICNIC, TOILET)),
            SpotSeed("r04", REST, "쉼표 발마사지", "30분 발 마사지로 페달 밟던 다리 풀기", 680, 155.0, listOf(MASSAGE)),
            SpotSeed("r05", REST, "호수 둘레길", "평지 3km 호수 한 바퀴, 곳곳에 벤치", 790, 315.0, listOf(TRAIL, VIEW, TOILET)),
            SpotSeed("r06", REST, "편백 찜질 사우나", "편백 찜질방 · 수면실, 24시간 운영", 910, 50.0, listOf(SAUNA, SLEEP_ROOM, PARKING)),
            SpotSeed("r07", REST, "숲속 피크닉 잔디광장", "주차장 바로 옆 잔디밭, 트렁크 열고 돗자리 펴기", 1030, 265.0, listOf(PARK, PICNIC, PARKING)),
            SpotSeed("r08", REST, "타이 마사지 바람", "60분 전신 타이 마사지, 예약하면 대기 없음", 1160, 130.0, listOf(MASSAGE)),
            SpotSeed("r09", REST, "은행나무 산책길", "가을이면 노란 터널이 되는 1km 산책로", 1290, 345.0, listOf(TRAIL, VIEW)),
            SpotSeed("r10", REST, "오토캠핑 공원", "차박 사이트 · 개수대 · 화장실, 1박 예약 가능", 1410, 190.0, listOf(PARK, CAR_CAMPING, TOILET)),
            SpotSeed("r11", REST, "참숯 한증막", "참숯 가마 한증막에서 땀 빼고 식혜 한 잔", 1540, 70.0, listOf(SAUNA, PARKING)),
            SpotSeed("r12", REST, "연꽃 습지 데크길", "여름 아침 연꽃 핀 습지를 걷는 데크 산책로", 1670, 240.0, listOf(TRAIL, VIEW)),
            SpotSeed("r13", REST, "스파 휴", "아로마 마사지와 족욕, 동승자와 함께 쓰는 2인실", 1780, 10.0, listOf(MASSAGE, FAMILY)),
            SpotSeed("r14", REST, "온천 대중탕", "천연 온천수 대중탕, 노천탕 있음", 1890, 290.0, listOf(BATHHOUSE, VIEW)),
            SpotSeed("r15", REST, "별빛 언덕 캠핑공원", "밤하늘 보며 차박하는 언덕 위 공원", 1990, 115.0, listOf(PARK, CAR_CAMPING, NIGHT_VIEW)),
            // 2km 밖 — 반경을 넓히면 나타난다
            SpotSeed("r16", REST, "수목원 숲 터널", "메타세쿼이아가 하늘을 덮는 숲 터널 산책", 2600, 30.0, listOf(TRAIL, VIEW, PARKING)),
            SpotSeed("r17", REST, "편백 산림욕 숲길", "편백나무 사이를 걷는 산림욕 산책로", 3500, 140.0, listOf(TRAIL)),
            SpotSeed("r18", REST, "계곡 피크닉 공원", "계곡물 소리 들으며 돗자리 펴는 그늘 공원", 4700, 350.0, listOf(PARK, PICNIC, TOILET)),
            SpotSeed("r19", REST, "해수 사우나", "바닷물을 데운 해수탕과 수면실", 6400, 60.0, listOf(SAUNA, SLEEP_ROOM)),
            SpotSeed("r20", REST, "온천 마을 대중탕", "온천 마을 한가운데 있는 오래된 대중탕", 9100, 230.0, listOf(BATHHOUSE, PARKING)),
            SpotSeed("r21", REST, "바닷가 차박 공원", "파도 소리 들으며 하룻밤 쉬는 해변 차박지", 13300, 15.0, listOf(PARK, CAR_CAMPING, VIEW)),
            SpotSeed("r22", REST, "호숫가 캠핑 공원", "호숫가 그늘에서 돗자리 · 차박 모두 가능", 17900, 200.0, listOf(PARK, CAR_CAMPING, PICNIC)),
        )
    }
}
