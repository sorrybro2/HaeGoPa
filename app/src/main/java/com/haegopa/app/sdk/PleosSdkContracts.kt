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

    /** 이미 이 스팟으로 길안내 중 */
    data object AlreadyDestination : WaypointResult
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

    /** [경유지]: 길안내 중이면 경유지로 끼워 넣고, 경로가 없으면 목적지로 안내를 시작한다 */
    fun addWaypointDetailed(name: String, latitude: Double, longitude: Double): WaypointResult

    /** [목적지]: 진행 중인 경로가 있어도 이 스팟을 새 목적지로 길안내한다 (NaviHelper.requestRoute) */
    fun setDestinationDetailed(name: String, latitude: Double, longitude: Double): WaypointResult

    fun clearWaypoints()

    /** 순정 내비 화면으로 전환 (Pleos 에서만 가능, 성공 여부 반환) */
    fun openNavigationScreen(): Boolean = false

    /** 현재 위치를 다시 물어본다 → [currentLocation] · [currentAddress] 갱신 (NaviHelper 는 한 번 묻고 한 번 답한다) */
    fun requestCurrentLocation() = Unit

    override fun addWaypoint(name: String, latitude: Double, longitude: Double): Boolean =
        addWaypointDetailed(name, latitude, longitude).isSuccess
}

interface VehicleGateway : MockVehicleManager {
    val status: StateFlow<SdkStatus>
    val gear: StateFlow<GearPosition>

    /** 현재 차속(km/h). 리스너는 화면(ViewModel) 하나만 쓰므로, 코파일럿처럼 뒤에서 보는 쪽은 이 값을 구독한다 */
    val speedKmh: StateFlow<Int>

    /** 현재 차속 값의 출처 (VHAL 차속 / Virtual Console 기어 연동 / PT 데모 시뮬레이터) */
    val speedSource: StateFlow<String>

    /** PT 시연용 가상 차속 주입 */
    fun simulateSpeed(speedKmh: Int)

    /** PT 시연용 주행 시간 가속 (피로 알림 시연) */
    fun addSimulatedTripMinutes(minutes: Int)

    /** 런타임 권한 허용 직후 센서 재연결 */
    fun refreshSensors()
}

/** 차의 현재 위치 (Pleos FusedLocation). 없는 기기에서는 [location] 이 null 로 남는다 */
interface LocationGateway {
    val status: StateFlow<SdkStatus>
    val location: StateFlow<GeoPoint?>

    /** 런타임 권한을 허용한 직후 다시 연결 */
    fun refresh() = Unit
}

/** 화면 자막(TTS 낭독 문장) — 같은 문장을 다시 말해도 갱신되도록 seq 포함 */
data class SpeechCaption(val text: String, val seq: Long)

interface SpeechGateway : MockSpeechHelper {
    val ttsStatus: StateFlow<SdkStatus>
    val sttStatus: StateFlow<SdkStatus>
    val caption: StateFlow<SpeechCaption?>
    fun stopSpeaking()

    /**
     * 문장을 읽고, 다 읽으면 [onDone] 을 메인 스레드에서 부른다 (끝나거나 · 멈추거나 · 실패해도 한 번).
     * 코파일럿이 질문을 다 읽은 뒤에 듣기를 시작할 때 쓴다. 그사이 다른 문장을 읽으면 [onDone] 은 오지 않는다.
     */
    fun speakThen(script: String, onDone: () -> Unit)

    /** 지금 대답을 들을 수 있는지 (마이크 권한 + 음성 인식 엔진) */
    val canListen: Boolean

    /** 듣고 있던 음성 인식을 취소한다. 취소된 요청의 결과 콜백은 오지 않는다 */
    fun stopListening()
}
