package com.haegopa.app.domain.route

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.util.GeoUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin

/** 격자 좌표(m). x = 격자 '가로' 방향, y = 격자 '세로' 방향 (화면처럼 아래가 +) */
data class Vec(val x: Double, val y: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    fun length(): Double = hypot(x, y)
}

/**
 * 해고파 지도의 일러스트 도로망 — 지도 기준점(출발 위치)을 교차로로 하는 [BLOCK_METERS] 간격 바둑판을
 * 화면에서 [ANGLE_DEGREES] 만큼 돌려 놓은 것. 지도(SpotMap)가 그리는 도로와 경로 · 달리는 차가 같은 도로를 쓴다.
 * 도로는 미터로 고정이라 검색 반경(2 · 5 · 10km)을 바꿔도 길이 움직이지 않는다 (멀리 보면 작은 길만 생략).
 */
object RoadGrid {
    const val BLOCK_METERS = 420.0

    /** 화면에서 격자가 기울어진 각도 (SpotMap 의 GRID_ANGLE 과 같은 값) */
    const val ANGLE_DEGREES = -14.0

    private val cosA = cos(Math.toRadians(ANGLE_DEGREES))
    private val sinA = sin(Math.toRadians(ANGLE_DEGREES))

    /** 위경도 → 격자 좌표 (기준점 [anchor] 이 원점 교차로) */
    fun toGrid(anchor: GeoPoint, point: GeoPoint): Vec {
        val (east, north) = GeoUtils.toLocalMeters(anchor, point)
        val sx = east
        val sy = -north
        return Vec(sx * cosA + sy * sinA, -sx * sinA + sy * cosA)
    }

    fun fromGrid(anchor: GeoPoint, g: Vec): GeoPoint {
        val (east, sy) = screenMeters(g)
        return GeoUtils.fromLocalMeters(anchor, east = east, north = -sy)
    }

    /** 격자 방향 → 화면 방향 (동쪽 m, 남쪽 m) */
    fun screenMeters(g: Vec): Pair<Double, Double> = (g.x * cosA - g.y * sinA) to (g.x * sinA + g.y * cosA)

    /** 격자 방향 벡터 → 화면에서의 진행 방향 (위 = 0°, 시계방향) */
    fun screenHeading(direction: Vec): Double {
        val (sx, sy) = screenMeters(direction)
        return Math.toDegrees(atan2(sx, -sy))
    }

    fun snap(v: Double, block: Double = BLOCK_METERS): Double = (v / block).roundToLong() * block

    fun isOnLine(v: Double, block: Double = BLOCK_METERS): Boolean = abs(v - snap(v, block)) < ON_ROAD_EPS

    internal const val ON_ROAD_EPS = 0.5
}

/**
 * 격자 도로를 따라 ㄱ · ㄷ 자로 꺾이는 경로를 만든다.
 * 도로 밖 지점(스팟)은 가장 가까운 도로에 붙은 진입로로 들어가고 나온다 → 첫 · 끝 짧은 구간 말고는 모두 도로 위.
 */
object RoadRouter {

    fun route(start: Vec, targets: List<Vec>, block: Double = RoadGrid.BLOCK_METERS): List<Vec> {
        val points = mutableListOf(start)
        var current = start
        for (target in targets) {
            legs(current, target, block).forEach { p -> if ((p - points.last()).length() > RoadGrid.ON_ROAD_EPS) points += p }
            current = target
        }
        return points
    }

    private data class RoadPoint(val point: Vec, val vertical: Boolean, val horizontal: Boolean)

    /** 도로 위 가장 가까운 진입점 (이미 도로 위면 그 자리) */
    private fun access(q: Vec, block: Double): RoadPoint {
        val onV = RoadGrid.isOnLine(q.x, block)
        val onH = RoadGrid.isOnLine(q.y, block)
        if (onV || onH) return RoadPoint(q, vertical = onV, horizontal = onH)
        val toHorizontal = abs(q.y - RoadGrid.snap(q.y, block))
        val toVertical = abs(q.x - RoadGrid.snap(q.x, block))
        return if (toHorizontal <= toVertical) {
            RoadPoint(Vec(q.x, RoadGrid.snap(q.y, block)), vertical = false, horizontal = true)
        } else {
            RoadPoint(Vec(RoadGrid.snap(q.x, block), q.y), vertical = true, horizontal = false)
        }
    }

    private fun legs(from: Vec, to: Vec, block: Double): List<Vec> {
        val a = access(from, block)
        val b = access(to, block)
        val options = buildList {
            for (aVertical in listOfNotNull(true.takeIf { a.vertical }, false.takeIf { a.horizontal })) {
                for (bVertical in listOfNotNull(true.takeIf { b.vertical }, false.takeIf { b.horizontal })) {
                    add(corners(a.point, aVertical, b.point, bVertical, block))
                }
            }
        }
        // 꺾는 횟수가 적은 길 → 짧은 길
        val best = options.minWith(compareBy<List<Vec>>({ it.size }, { pathLength(listOf(a.point) + it + b.point) }))
        return listOf(a.point) + best + b.point + to
    }

    /** a(세로길/가로길 위) → b(세로길/가로길 위) 사이의 꺾이는 점들 */
    private fun corners(a: Vec, aVertical: Boolean, b: Vec, bVertical: Boolean, block: Double): List<Vec> = when {
        aVertical && !bVertical -> listOf(Vec(a.x, b.y))
        !aVertical && bVertical -> listOf(Vec(b.x, a.y))
        aVertical && bVertical ->
            if (abs(a.x - b.x) < RoadGrid.ON_ROAD_EPS) {
                emptyList()
            } else {
                val y = RoadGrid.snap(b.y, block)
                listOf(Vec(a.x, y), Vec(b.x, y))
            }
        else ->
            if (abs(a.y - b.y) < RoadGrid.ON_ROAD_EPS) {
                emptyList()
            } else {
                val x = RoadGrid.snap(b.x, block)
                listOf(Vec(x, a.y), Vec(x, b.y))
            }
    }

    private fun pathLength(points: List<Vec>): Double = points.zipWithNext { p, q -> (q - p).length() }.sum()
}

/** 경로 위 한 지점 */
data class RoutePoint(val location: GeoPoint, val headingDegrees: Double)

/** 기준점 [anchor] 의 격자 좌표로 만든 경로. 출발점에서 [distance] m 간 지점 · 진행 방향을 구한다 */
class RoutePath(val anchor: GeoPoint, val grid: List<Vec>) {

    val points: List<GeoPoint> = grid.map { RoadGrid.fromGrid(anchor, it) }

    private val cumulative: DoubleArray = DoubleArray(grid.size).also { acc ->
        for (i in 1 until grid.size) acc[i] = acc[i - 1] + (grid[i] - grid[i - 1]).length()
    }

    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    fun at(distance: Double): RoutePoint {
        if (grid.size < 2) return RoutePoint(points.firstOrNull() ?: anchor, 0.0)
        val d = distance.coerceIn(0.0, length)
        var i = 1
        while (i < grid.size - 1 && cumulative[i] < d) i++
        val a = grid[i - 1]
        val b = grid[i]
        val segment = cumulative[i] - cumulative[i - 1]
        val t = if (segment <= 0.0) 0.0 else (d - cumulative[i - 1]) / segment
        val p = Vec(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        return RoutePoint(RoadGrid.fromGrid(anchor, p), RoadGrid.screenHeading(b - a))
    }

    /** 출발점 ~ [distance] 까지의 지나온 길 */
    fun passed(distance: Double): List<GeoPoint> {
        if (grid.size < 2 || distance <= 0.0) return emptyList()
        val d = distance.coerceAtMost(length)
        val out = mutableListOf(points.first())
        for (i in 1 until grid.size) {
            if (cumulative[i] <= d) {
                out += points[i]
            } else {
                out += at(d).location
                break
            }
        }
        return out
    }

    companion object {
        fun single(anchor: GeoPoint, at: GeoPoint) = RoutePath(anchor, listOf(RoadGrid.toGrid(anchor, at)))
    }
}

/** 지도에 그리는 차 — 위치 · 진행 방향(화면 위 = 0°) · 이번 경로에서 지나온 거리 */
data class CarPosition(
    val location: GeoPoint,
    val headingDegrees: Double = 0.0,
    val traveledMeters: Double = 0.0,
)
