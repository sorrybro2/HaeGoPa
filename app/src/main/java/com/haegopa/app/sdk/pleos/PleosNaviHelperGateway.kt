package com.haegopa.app.sdk.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind
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
 * - [목적지]      : `NaviHelper.requestRoute(RouteInfo(...))` 로 해당 스팟 길안내 시작 (기존 경로는 새 경로로 바뀜)
 * - [경유지]      : 경로 안내 중이면 `NaviHelper.addWaypoint(RequestWaypointInfo(..., WaypointIndex.FIRST/SECOND))`,
 *                   경로가 없으면 [목적지]와 같이 requestRoute
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

    private val _currentAddress = MutableStateFlow<String?>(null)
    override val currentAddress: StateFlow<String?> = _currentAddress.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile
    private var naviHelper: NaviHelper? = null

    @Volatile
    private var connected = false

    // 길안내 여부: Pleos 에뮬레이터에서는 안내 중에도 RouteStateInfo.isRouting 이 false 로 오는 경우가 있어
    // NavigationStatus(ROUTE_DRIVE) · NaviMode(ROUTE/SIMULATION_GUIDING) · 남은 거리가 있는 목적지를 함께 본다.
    @Volatile
    private var routeFlag = false

    @Volatile
    private var routeDriveStatus = false

    @Volatile
    private var activeDestination: Waypoint? = null

    private val routing: Boolean get() = routeFlag || routeDriveStatus || activeDestination != null

    @Volatile
    private var naviWaypointCount = 0

    @Volatile
    private var naviStops: List<Waypoint> = emptyList()

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            routeFlag = true
            refreshRouteSnapshot()
        }

        override fun onRouteEnded() = onRouteFinished()

        override fun onRouteCancelled() = onRouteFinished()

        override fun onDestinationArrived(info: DestinationArrivedInfo) = onRouteFinished()

        override fun onRouteStateInfo(info: RouteStateInfo) {
            val mode = runCatching { info.naviMode }.getOrNull()
            routeFlag = info.isRouting || mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING
            Log.i(TAG, "routeState isRouting=${info.isRouting} naviMode=$mode → routing=$routing")
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            routeDriveStatus = status == NavigationStatus.ROUTE_DRIVE
            Log.i(TAG, "navigationStatus=$status → routing=$routing")
            if (routeDriveStatus) refreshRouteSnapshot()
        }

        override fun onDestinationInfo(info: DestinationInfo) {
            Log.i(TAG, "destinationInfo name=${info.name} lat=${info.latitude} lng=${info.longitude} remain=${info.remainDistance}m")
            val point = validPoint(info.latitude, info.longitude)
                ?: validPoint(info.displayLatitude, info.displayLongitude)
                ?: requestedPoints[info.name]
            activeDestination = if (info.remainDistance > 0 && info.name.isNotBlank() && point != null) {
                Waypoint(info.name, point, WaypointKind.DESTINATION, order = 0)
            } else {
                null
            }
            mirrorNaviRoute()
        }

        override fun onWayPointInfo(waypoints: List<WaypointInfo>) {
            waypoints.forEach {
                Log.i(TAG, "waypointInfo name=${it.name} lat=${it.latitude} lng=${it.longitude} display=${it.displayLatitude},${it.displayLongitude}")
            }
            // 에뮬레이터에서 경유지 좌표가 (0,0)으로 오는 경우가 있어 display 좌표 → 해고파가 보낸 좌표 순으로 보완
            naviStops = waypoints.filter { it.name.isNotBlank() }.mapNotNull { w ->
                val point = validPoint(w.latitude, w.longitude)
                    ?: validPoint(w.displayLatitude, w.displayLongitude)
                    ?: requestedPoints[w.name]
                    ?: return@mapNotNull null
                Waypoint(w.name, point, WaypointKind.WAYPOINT, order = 0)
            }.mapIndexed { i, w -> w.copy(order = i + 1) }
            naviWaypointCount = waypoints.count { it.name.isNotBlank() }
            mirrorNaviRoute()
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            _currentAddress.value = runCatching { info.address }.getOrNull()?.takeIf { it.isNotBlank() }
            _currentLocation.value = GeoPoint(info.latitude, info.longitude)
        }

        override fun onError(errorCode: NaviErrorCode) {
            _events.tryEmit("순정 내비 응답: ${errorCode.toKorean()}")
        }
    }

    /**
     * 해고파가 내비에 보낸 스팟 좌표. Pleos 내비의 WaypointInfo 는 좌표가 (0,0)·display=null 로 오므로
     * (Pleos Connect 에뮬레이터 v2.1.0 에서 확인) 이름으로 원래 좌표를 찾아 보완한다. 앱을 다시 켜도 남도록 저장.
     */
    private val requestedPoints = RequestedPointStore(appContext)

    private class RequestedPointStore(context: Context) {
        private val prefs = context.getSharedPreferences("navi_requested_points", Context.MODE_PRIVATE)

        operator fun get(name: String): GeoPoint? = prefs.getString(name, null)?.split(',')?.let { parts ->
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lng = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lng != null) GeoPoint(lat, lng) else null
        }

        operator fun set(name: String, point: GeoPoint) {
            prefs.edit().putString(name, "${point.latitude},${point.longitude}").apply()
        }
    }

    private fun validPoint(lat: Double?, lng: Double?): GeoPoint? {
        if (lat == null || lng == null) return null
        if (lat == 0.0 && lng == 0.0) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat, lng)
    }

    private fun onRouteFinished() {
        routeFlag = false
        routeDriveStatus = false
        activeDestination = null
        naviStops = emptyList()
        naviWaypointCount = 0
        mock.clearAll()
    }

    /** 순정 내비의 실제 목적지 · 경유지를 해고파 '내 경로' 카드와 지도에 그대로 보여 준다 */
    private fun mirrorNaviRoute() {
        val destination = activeDestination ?: return
        mock.syncFromNavi(destination, naviStops)
    }

    private fun refreshRouteSnapshot() {
        val helper = naviHelper ?: return
        runCatching {
            helper.getDestinationInfo()
            helper.getWaypointInfo()
        }.onFailure { Log.w(TAG, "route snapshot request failed", it) }
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
                    _status.value = SdkStatus("Pleos NaviHelper", SdkBackend.PLEOS, "v3.0.5 · 목적지/경유지 길안내 연동")
                    runCatching {
                        helper.addListener(listener)
                        helper.getRouteStateInfo()
                        helper.getCurrentLocationInfo()
                        helper.getDestinationInfo()
                        helper.getWaypointInfo()
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
        requestedPoints[name] = GeoPoint(latitude, longitude)

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
                startRoute(helper, name, latitude, longitude)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper request failed", t)
            WaypointResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    @Synchronized
    override fun setDestinationDetailed(name: String, latitude: Double, longitude: Double): WaypointResult {
        val helper = naviHelper
        if (!connected || helper == null) return mock.setDestinationDetailed(name, latitude, longitude)
        if (activeDestination?.name == name) return WaypointResult.AlreadyDestination
        requestedPoints[name] = GeoPoint(latitude, longitude)
        return try {
            startRoute(helper, name, latitude, longitude)
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper requestRoute failed", t)
            WaypointResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** 이 스팟을 목적지로 새 길안내를 요청한다. 순정 내비가 기존 경로(경유지 포함)를 새 경로로 바꾼다 */
    private fun startRoute(helper: NaviHelper, name: String, latitude: Double, longitude: Double): WaypointResult {
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
        routeFlag = true
        activeDestination = Waypoint(name, GeoPoint(latitude, longitude), WaypointKind.DESTINATION, order = 0)
        naviStops = emptyList()
        naviWaypointCount = 0
        mock.clearWaypoints()
        mock.recordDestination(name, latitude, longitude)
        return WaypointResult.SetAsDestination(viaPleos = true)
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

    override fun requestCurrentLocation() {
        val helper = naviHelper ?: return
        if (!connected) return
        runCatching { helper.getCurrentLocationInfo() }.onFailure { Log.w(TAG, "location request failed", it) }
    }

    /** Pleos 순정 내비 앱을 앞으로 불러온다 (Pleos 에서는 해고파와 같은 메인 영역에 열린다) */
    override fun openNavigationScreen(): Boolean {
        val intent = appContext.packageManager.getLaunchIntentForPackage(NAVI_APP_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "open navigation failed", it) }
            .isSuccess
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
        const val NAVI_APP_PACKAGE = "ai.umos.maps.android.navigation.app"
    }
}
