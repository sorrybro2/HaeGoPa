package com.haegopa.app.sdk.mock

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind
import com.haegopa.app.sdk.WaypointResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 순정 내비 Mock: 가상 경로(목적지 "회사")에 경유지를 메모리로 등록한다.
 * 경유지 한도는 실제 Pleos NaviHelper 규격(WaypointIndex.FIRST / SECOND)과 같은 2곳.
 */
class MockNaviHelperImpl(
    private val maxWaypoints: Int = MAX_WAYPOINTS,
) : NaviHelperGateway {

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    override val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    private val _status = MutableStateFlow(
        SdkStatus("Mock NaviHelper", SdkBackend.MOCK, "가상 경로에 경유지 등록"),
    )
    override val status: StateFlow<SdkStatus> = _status.asStateFlow()

    override val currentLocation: StateFlow<GeoPoint?> = MutableStateFlow<GeoPoint?>(null).asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val events: SharedFlow<String> = _events.asSharedFlow()

    val waypointCount: Int get() = _waypoints.value.count { it.kind == WaypointKind.WAYPOINT }

    fun contains(name: String): Boolean = _waypoints.value.any { it.name == name }

    @Synchronized
    override fun addWaypointDetailed(name: String, latitude: Double, longitude: Double): WaypointResult {
        if (contains(name)) return WaypointResult.AlreadyAdded
        val count = waypointCount
        if (count >= maxWaypoints) return WaypointResult.LimitReached(maxWaypoints)
        val waypoint = Waypoint(name, GeoPoint(latitude, longitude), WaypointKind.WAYPOINT, order = count + 1)
        _waypoints.value = _waypoints.value + waypoint
        return WaypointResult.AddedAsWaypoint(order = waypoint.order, viaPleos = false)
    }

    /** Pleos 게이트웨이가 "목적지로 안내 시작" 결과를 지도에 반영할 때 사용 */
    @Synchronized
    fun recordDestination(name: String, latitude: Double, longitude: Double) {
        val withoutDestination = _waypoints.value.filterNot { it.kind == WaypointKind.DESTINATION }
        _waypoints.value = withoutDestination +
            Waypoint(name, GeoPoint(latitude, longitude), WaypointKind.DESTINATION, order = 0)
    }

    /** 경유지만 지우고 목적지(있다면)는 유지 */
    @Synchronized
    override fun clearWaypoints() {
        _waypoints.value = _waypoints.value.filter { it.kind == WaypointKind.DESTINATION }
    }

    companion object {
        /** Pleos NaviHelper WaypointIndex 는 FIRST, SECOND 두 슬롯 */
        const val MAX_WAYPOINTS = 2
    }
}
