package com.haegopa.app.sdk

import com.haegopa.app.domain.model.GearPosition
import com.haegopa.app.domain.model.GeoPoint
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// ─────────────────────────────────────────────────────────────────────────────
// 명세서 4장 — Pleos SDK Mock 인터페이스 (시그니처 그대로 유지)
//  · 공모전 Pleos Connect 에뮬레이터 : 실제 Pleos SDK 어댑터(sdk/pleos)가 구현
//  · 일반 AVD / 폰 / 태블릿          : 같은 어댑터가 내장 Mock(sdk/mock)으로 자동 폴백
// ─────────────────────────────────────────────────────────────────────────────

interface MockNaviHelper {
    fun addWaypoint(name: String, latitude: Double, longitude: Double): Boolean
}

interface MockVehicleManager {
    fun setSpeedChangeListener(listener: (speed: Int) -> Unit)
    fun getTripDurationMinutes(): Int
}

interface MockSpeechHelper {
    fun speakText(script: String)
    fun startVoiceRecognition(callback: (command: String) -> Unit)
}

// ─────────────────────────────────────────────────────────────────────────────
// 화면 렌더링 · 진단에 필요한 확장 계약 (위 Mock 인터페이스를 상속)
// ─────────────────────────────────────────────────────────────────────────────

/** 각 SDK 가 지금 어떤 구현으로 동작 중인지 (UI 하단 진단 배지) */
enum class SdkBackend { CONNECTING, PLEOS, ANDROID, MOCK }

data class SdkStatus(
    val name: String,
    val backend: SdkBackend,
    val detail: String = "",
)

enum class WaypointKind { WAYPOINT, DESTINATION }

data class Waypoint(
    val name: String,
    val location: GeoPoint,
    val kind: WaypointKind,
    /** 경유지 순번(1, 2). 목적지는 0 */
    val order: Int,
)

sealed interface WaypointResult {
    /** 순정 내비 경로에 경유지로 등록됨 */
    data class AddedAsWaypoint(val order: Int, val viaPleos: Boolean) : WaypointResult

    /** 진행 중인 경로가 없어 목적지로 길안내를 시작함 (NaviHelper.requestRoute) */
    data class SetAsDestination(val viaPleos: Boolean) : WaypointResult

    data object AlreadyAdded : WaypointResult
    data class LimitReached(val max: Int) : WaypointResult
    data class Failed(val reason: String) : WaypointResult

    val isSuccess: Boolean get() = this is AddedAsWaypoint || this is SetAsDestination
}

interface NaviHelperGateway : MockNaviHelper {
    val status: StateFlow<SdkStatus>
    val waypoints: StateFlow<List<Waypoint>>

    /** NaviHelper.getCurrentLocationInfo() 결과. 알 수 없으면 null */
    val currentLocation: StateFlow<GeoPoint?>

    /** 순정 내비가 알려 준 현재 위치 주소 (예: "수정구 시흥동"). 알 수 없으면 null */
    val currentAddress: StateFlow<String?>

    /** 비동기 SDK 콜백(오류 등)을 사용자 메시지로 전달 */
    val events: SharedFlow<String>

    fun addWaypointDetailed(name: String, latitude: Double, longitude: Double): WaypointResult
    fun clearWaypoints()

    /** 순정 내비 화면으로 전환 (Pleos 에서만 가능, 성공 여부 반환) */
    fun openNavigationScreen(): Boolean = false

    override fun addWaypoint(name: String, latitude: Double, longitude: Double): Boolean =
        addWaypointDetailed(name, latitude, longitude).isSuccess
}

interface VehicleGateway : MockVehicleManager {
    val status: StateFlow<SdkStatus>
    val gear: StateFlow<GearPosition>

    /** 현재 차속 값의 출처 (VHAL 차속 / Virtual Console 기어 연동 / PT 데모 시뮬레이터) */
    val speedSource: StateFlow<String>

    /** PT 시연용 가상 차속 주입 */
    fun simulateSpeed(speedKmh: Int)

    /** PT 시연용 주행 시간 가속 (피로 알림 시연) */
    fun addSimulatedTripMinutes(minutes: Int)

    /** 런타임 권한 허용 직후 센서 재연결 */
    fun refreshSensors()
}

/** 화면 자막(TTS 낭독 문장) — 같은 문장을 다시 말해도 갱신되도록 seq 포함 */
data class SpeechCaption(val text: String, val seq: Long)

interface SpeechGateway : MockSpeechHelper {
    val ttsStatus: StateFlow<SdkStatus>
    val sttStatus: StateFlow<SdkStatus>
    val caption: StateFlow<SpeechCaption?>
    fun stopSpeaking()
}
