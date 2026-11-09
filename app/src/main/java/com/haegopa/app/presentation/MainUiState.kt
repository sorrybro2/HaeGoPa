package com.haegopa.app.presentation

import com.haegopa.app.domain.model.DriveMode
import com.haegopa.app.domain.model.GearPosition
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.QuizQuestion
import com.haegopa.app.domain.model.SearchRadius
import com.haegopa.app.domain.model.SingAlongSong
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind

/** 카테고리별 무한 스크롤 피드 상태 */
data class FeedState(
    val items: List<Spot> = emptyList(),
    val isLoading: Boolean = false,
    val endReached: Boolean = false,
    val nextPage: Int = 0,
    val totalInRadius: Int = 0,
    val error: String? = null,
)

data class BoosterUiState(
    val enabled: Boolean = false,
    val songIndex: Int = 0,
    val song: SingAlongSong? = null,
)

enum class QuizPhase { ASKING, LISTENING, ANSWERED, FINISHED }

data class QuizUiState(
    val deck: List<QuizQuestion>,
    val index: Int = 0,
    val score: Int = 0,
    val answered: Int = 0,
    val remainingSeconds: Int = QUIZ_SECONDS,
    val phase: QuizPhase = QuizPhase.ASKING,
    val heard: String? = null,
    val selectedIndex: Int? = null,
    val lastCorrect: Boolean? = null,
) {
    val current: QuizQuestion get() = deck[index]

    companion object {
        const val QUIZ_SECONDS = 60
    }
}

data class SdkSummary(
    val navi: SdkStatus = SdkStatus("NaviHelper", SdkBackend.CONNECTING),
    val vehicle: SdkStatus = SdkStatus("Vehicle", SdkBackend.CONNECTING),
    val tts: SdkStatus = SdkStatus("TextToSpeech", SdkBackend.CONNECTING),
    val stt: SdkStatus = SdkStatus("SpeechToText", SdkBackend.CONNECTING),
    val speedSource: String = "-",
)

data class MainUiState(
    val selectedCategory: SpotCategory = SpotCategory.EAT,
    val feeds: Map<SpotCategory, FeedState> = SpotCategory.entries.associateWith { FeedState() },
    val origin: GeoPoint = GeoPoint.DEFAULT_MOCK_ORIGIN,
    val originLabel: String = "현재 위치 확인 중",
    /** 차량(현재 위치) 기준 검색 반경 */
    val radiusMeters: Int = SearchRadius.DEFAULT_METERS,
    /** 마지막으로 직접 설정한 반경 (4번째 칩에 표시) */
    val customRadiusMeters: Int? = null,
    val radiusDialogOpen: Boolean = false,
    /** 지도 핀용: 목록 페이징과 무관하게 현재 카테고리 · 반경 안의 스팟 전부 */
    val mapSpots: List<Spot> = emptyList(),
    val speedKmh: Int = 0,
    val gear: GearPosition = GearPosition.UNKNOWN,
    val tripMinutes: Int = 0,
    val waypoints: List<Waypoint> = emptyList(),
    val detailSpot: Spot? = null,
    val stretchingOpen: Boolean = false,
    val stretchingPlace: String? = null,
    val booster: BoosterUiState = BoosterUiState(),
    val quiz: QuizUiState? = null,
    val fatigueNudgeVisible: Boolean = false,
    val fatigueNudgeDismissed: Boolean = false,
    val sdk: SdkSummary = SdkSummary(),
) {
    val driveMode: DriveMode get() = DriveMode.fromSpeed(speedKmh)
    val isDriving: Boolean get() = driveMode == DriveMode.DRIVING
    val currentFeed: FeedState get() = feeds.getValue(selectedCategory)

    fun isAdded(spot: Spot): Boolean = waypoints.any { it.name == spot.name }

    /** 이 스팟이 순정 내비 경로에서 맡은 역할 (목적지 / 경유지 / 없음) — [목적지]·[경유지] 버튼 상태 */
    fun routeRole(spot: Spot): WaypointKind? = waypoints.firstOrNull { it.name == spot.name }?.kind
}
