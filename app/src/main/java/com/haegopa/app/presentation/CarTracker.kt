package com.haegopa.app.presentation

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.route.CarPosition
import com.haegopa.app.domain.route.RoadGrid
import com.haegopa.app.domain.route.RoadRouter
import com.haegopa.app.domain.route.RoutePath
import com.haegopa.app.domain.route.Vec
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 차의 현재 위치 — 지도 카메라(차를 화면 한자리에 고정) · 검색 반경 원 · 주변 스팟 검색의 중심.
 *
 * - 실제 위치: 순정 내비(NaviHelper)가 알려 주는 위치가 [REAL_MOVE_MIN_METERS] 넘게 바뀌면 그대로 따라간다.
 * - 시연 주행: 실제 위치가 움직이지 않는데 차속이 있으면(PT 데모 · Virtual Console 기어 D) 내 경로를 따라
 *   빨리 감아 달린다 ([advance]). 실제 위치가 움직이기 시작하면 [REAL_HOLD_MILLIS] 동안 시연 주행을 멈춘다.
 * - 경로: 차 위치 → 경유지 → 목적지를 지도 도로망으로 잇는다. 목적지가 없으면 출발점 둘레 '동네 한 바퀴'를 계속 돈다.
 */
class CarTracker(start: GeoPoint = GeoPoint.DEFAULT_MOCK_ORIGIN) {

    /** 지도 · 도로망 · 가상 스팟의 기준점 (출발 위치) */
    var anchor: GeoPoint = start
        private set

    private val _car = MutableStateFlow(CarPosition(start))
    val car: StateFlow<CarPosition> = _car.asStateFlow()

    private val _path = MutableStateFlow(RoutePath.single(start, start))

    /** 지금 달리는 경로 (차 위치에서 시작). 지도는 [CarPosition.traveledMeters] 까지를 지나온 길로 칠한다 */
    val path: StateFlow<RoutePath> = _path.asStateFlow()

    /** 목적지가 없어 시연 경로(동네 한 바퀴)를 도는 중 (한 바퀴 끝나면 다시 돈다) */
    var virtualRoute: Boolean = true
        private set

    private var waypoints: List<Waypoint> = emptyList()
    private var lastReal: GeoPoint? = null
    private var lastRealMoveAt: Long? = null

    /** 출발 위치가 정해졌을 때 (가상 위치 → 순정 내비 위치 등): 지도 기준점과 차를 그 자리로 옮긴다 */
    fun reset(anchor: GeoPoint) {
        this.anchor = anchor
        lastReal = null
        lastRealMoveAt = null
        _car.value = CarPosition(anchor)
        rebuild()
    }

    /** 순정 내비의 목적지 · 경유지가 바뀌면 지금 위치에서 다시 길을 낸다 */
    fun setWaypoints(list: List<Waypoint>) {
        if (list == waypoints) return
        waypoints = list
        rebuild()
    }

    /** 시연 주행: 경로를 따라 [meters] 만큼 간다 */
    fun advance(meters: Double) {
        if (meters <= 0.0) return
        val current = _car.value
        if (current.traveledMeters >= _path.value.length) {
            if (!virtualRoute) return // 목적지에 도착 — 그 자리에 선다
            extendVirtual()
        }
        val path = _path.value
        val traveled = (_car.value.traveledMeters + meters).coerceAtMost(path.length)
        val point = path.at(traveled)
        _car.value = CarPosition(point.location, point.headingDegrees, traveled)
    }

    /** 실제 위치(NaviHelper). 움직였으면 true — 차를 그 자리로 옮기고 거기서 다시 길을 낸다 */
    fun onRealLocation(location: GeoPoint, nowMillis: Long): Boolean {
        val previous = lastReal
        lastReal = location
        if (previous == null || GeoUtils.distanceMeters(previous, location) < REAL_MOVE_MIN_METERS) return false
        lastRealMoveAt = nowMillis
        _car.value = CarPosition(location, GeoUtils.bearingDegrees(previous, location), 0.0)
        rebuild(keepHeading = true)
        return true
    }

    /** 실제 위치가 최근에 움직였으면 시연 주행은 쉰다 */
    fun shouldSimulate(nowMillis: Long): Boolean {
        val last = lastRealMoveAt ?: return true
        return nowMillis - last > REAL_HOLD_MILLIS
    }

    private fun rebuild(keepHeading: Boolean = false) {
        val here = _car.value
        val start = RoadGrid.toGrid(anchor, here.location)
        val stops = waypoints.filter { it.kind == WaypointKind.WAYPOINT }.sortedBy { it.order }
            .map { RoadGrid.toGrid(anchor, it.location) }
        val destination = waypoints.firstOrNull { it.kind == WaypointKind.DESTINATION }
            ?.let { RoadGrid.toGrid(anchor, it.location) }
        virtualRoute = destination == null
        val targets = when {
            stops.isEmpty() && destination == null -> virtualTargets(start)
            destination == null -> stops + Vec(stops.last().x, minOf(-14 * B, stops.last().y - 4 * B))
            else -> stops + destination
        }
        val path = RoutePath(anchor, RoadRouter.route(start, targets))
        _path.value = path
        val heading = if (keepHeading || path.length <= 0.0) here.headingDegrees else path.at(0.0).headingDegrees
        _car.value = CarPosition(here.location, heading, 0.0)
    }

    /**
     * 목적지가 없을 때의 시연 경로: 출발점 둘레를 도는 '동네 한 바퀴' (북 4 → 동 3 → 남 7 → 서 5 → 북 3 → 동 2, 약 10km).
     * 가상 스팟은 출발점 주변에만 있으므로 멀리 가 버리지 않고, 한 바퀴 돌면 출발점에서 다시 돈다.
     */
    private fun virtualTargets(start: Vec): List<Vec> {
        val sx = RoadGrid.snap(start.x)
        val sy = RoadGrid.snap(start.y)
        return LOOP.map { (dx, dy) -> Vec(sx + dx * B, sy + dy * B) }
    }

    private fun extendVirtual() = rebuild()

    companion object {
        private const val B = RoadGrid.BLOCK_METERS

        /** 동네 한 바퀴 꼭짓점 (격자 블록 단위, y 는 남쪽이 +) — 마지막이 출발점이라 닫힌 고리 */
        private val LOOP = listOf(0 to -4, 3 to -4, 3 to 3, -2 to 3, -2 to 0, 0 to 0)

        /** GPS 흔들림은 무시하고 이만큼 움직여야 실제 이동으로 본다 */
        const val REAL_MOVE_MIN_METERS = 15.0

        /** 실제 위치가 움직인 뒤 이 시간 동안은 시연 주행을 하지 않는다 */
        const val REAL_HOLD_MILLIS = 10_000L
    }
}
