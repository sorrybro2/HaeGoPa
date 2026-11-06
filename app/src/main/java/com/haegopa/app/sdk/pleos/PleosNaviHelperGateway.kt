package com.haegopa.app.sdk.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointResult
import com.haegopa.app.sdk.mock.MockNaviHelperImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos NaviHelper SDK(v3.0.5) 어댑터.
 *
 * - 경로 안내 중  : `NaviHelper.addWaypoint(RequestWaypointInfo(..., WaypointIndex.FIRST/SECOND))`
 * - 경로가 없을 때 : `NaviHelper.requestRoute(RouteInfo(...))` 로 해당 스팟 길안내 시작
 * - 현재 위치     : `getCurrentLocationInfo()` → `onCurrentLocationInfo` (2km 반경 원점)
 * - Pleos 내비가 없는 기기(일반 AVD)에서는 [MockNaviHelperImpl] 로 자동 폴백한다.
 */
class PleosNaviHelperGateway(context: Context) : NaviHelperGateway {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mock = MockNaviHelperImpl()

    private val _status = MutableStateFlow(SdkStatus("Pleos NaviHelper", SdkBackend.CONNECTING, "연결 중"))
    override val status: StateFlow<SdkStatus> = _status.asStateFlow()

    override val waypoints: StateFlow<List<Waypoint>> = mock.waypoints

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    override val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile
    private var naviHelper: NaviHelper? = null

    @Volatile
    private var connected = false

    @Volatile
    private var routing = false

    @Volatile
    private var naviWaypointCount = 0

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            routing = true
        }

        override fun onRouteEnded() {
            routing = false
            naviWaypointCount = 0
        }

        override fun onRouteCancelled() {
            routing = false
            naviWaypointCount = 0
        }

        override fun onRouteStateInfo(info: RouteStateInfo) {
            routing = info.isRouting
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            _currentLocation.value = GeoPoint(info.latitude, info.longitude)
        }

        override fun onError(errorCode: NaviErrorCode) {
            _events.tryEmit("순정 내비 응답: ${errorCode.toKorean()}")
        }
    }

    init {
        if (PleosServiceProbe.isNaviServiceAvailable(appContext)) {
            connect()
            mainHandler.postDelayed({
                if (!connected && _status.value.backend == SdkBackend.CONNECTING) fallbackToMock(null)
            }, CONNECT_TIMEOUT_MS)
        } else {
            // Pleos 내비 서비스가 없는 기기: SDK 재연결 루프를 만들지 않고 바로 Mock
            fallbackToMock(null)
        }
    }

    private fun connect() {
        val helper = try {
            NaviHelper(appContext)
        } catch (t: Throwable) {
            fallbackToMock(t)
            return
        }
        naviHelper = helper
        try {
            helper.initialize(
                {
                    connected = true
                    _status.value = SdkStatus("Pleos NaviHelper", SdkBackend.PLEOS, "v3.0.5 · 경유지/길안내 연동")
                    runCatching {
                        helper.addListener(listener)
                        helper.getRouteStateInfo()
                        helper.getCurrentLocationInfo()
                    }.onFailure { Log.w(TAG, "post-initialize request failed", it) }
                },
                { error -> fallbackToMock(error) },
            )
        } catch (t: Throwable) {
            fallbackToMock(t)
        }
    }

    private fun fallbackToMock(error: Throwable?) {
        connected = false
        Log.i(TAG, "Pleos NaviHelper unavailable → Mock", error)
        _status.value = SdkStatus("Mock NaviHelper", SdkBackend.MOCK, "Pleos 내비 미탑재 기기 → 가상 경로")
    }

    @Synchronized
    override fun addWaypointDetailed(name: String, latitude: Double, longitude: Double): WaypointResult {
        val helper = naviHelper
        if (!connected || helper == null) return mock.addWaypointDetailed(name, latitude, longitude)
        if (mock.contains(name)) return WaypointResult.AlreadyAdded

        return try {
            if (routing) {
                if (naviWaypointCount >= MockNaviHelperImpl.MAX_WAYPOINTS) {
                    return WaypointResult.LimitReached(MockNaviHelperImpl.MAX_WAYPOINTS)
                }
                val index = if (naviWaypointCount == 0) WaypointIndex.FIRST else WaypointIndex.SECOND
                helper.addWaypoint(RequestWaypointInfo(latitude, longitude, name, index, "", "", "", ""))
                naviWaypointCount += 1
                mock.addWaypointDetailed(name, latitude, longitude)
                WaypointResult.AddedAsWaypoint(order = naviWaypointCount, viaPleos = true)
            } else {
                helper.requestRoute(
                    RouteInfo(
                        latitude = latitude,
                        longitude = longitude,
                        poiName = name,
                        poiId = "",
                        address = "",
                        poiSubId = "",
                    ),
                )
                routing = true
                naviWaypointCount = 0
                mock.clearWaypoints()
                mock.recordDestination(name, latitude, longitude)
                WaypointResult.SetAsDestination(viaPleos = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper request failed", t)
            WaypointResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    @Synchronized
    override fun clearWaypoints() {
        val helper = naviHelper
        if (connected && helper != null && naviWaypointCount > 0) {
            runCatching { helper.removeWaypoint(WaypointIndex.ALL) }
        }
        naviWaypointCount = 0
        mock.clearWaypoints()
    }

    private fun NaviErrorCode.toKorean(): String = when (name) {
        "CAUSE_LOW_BATTERY" -> "배터리 잔량 부족으로 경로를 만들 수 없어요"
        "CAUSE_SEARCH_ROUTE_OVER_DISTANCE" -> "탐색 가능 거리를 넘었어요"
        "CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE" -> "목적지가 너무 가까워요"
        "CAUSE_SEARCH_ROUTE" -> "경로 탐색에 실패했어요"
        "CAUSE_SEARCH_TRIP" -> "여정 탐색에 실패했어요"
        else -> name
    }

    private companion object {
        const val TAG = "PleosNaviHelper"
        const val CONNECT_TIMEOUT_MS = 4_000L
    }
}
