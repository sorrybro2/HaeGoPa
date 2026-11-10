package com.haegopa.app.domain.route

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoadRouteTest {

    private val b = RoadGrid.BLOCK_METERS
    private val anchor = GeoPoint(37.5682, 126.9977)

    private fun onRoad(p: Vec) = RoadGrid.isOnLine(p.x) || RoadGrid.isOnLine(p.y)

    private fun axisAligned(a: Vec, c: Vec) = abs(a.x - c.x) < 0.5 || abs(a.y - c.y) < 0.5

    @Test
    fun `grid and lat-lng convert back and forth`() {
        val g = Vec(3 * b + 120.0, -5 * b - 40.0)
        val back = RoadGrid.toGrid(anchor, RoadGrid.fromGrid(anchor, g))
        assertEquals(g.x, back.x, 0.5)
        assertEquals(g.y, back.y, 0.5)
    }

    @Test
    fun `route from an intersection to an off-road spot stays on roads until the short driveway`() {
        val spot = Vec(2 * b + 130.0, -4 * b + 60.0)
        val points = RoadRouter.route(Vec(0.0, 0.0), listOf(spot))
        assertEquals(spot, points.last())
        // 모든 구간은 가로 · 세로로만 꺾이고, 마지막 진입로 앞까지는 모두 도로 위
        points.zipWithNext().forEach { (p, q) -> assertTrue("$p → $q", axisAligned(p, q)) }
        points.dropLast(1).forEach { assertTrue("$it should be on a road", onRoad(it)) }
        val driveway = (points.last() - points[points.size - 2]).length()
        assertTrue("driveway $driveway", driveway <= b / 2)
    }

    @Test
    fun `route through a waypoint to a destination on an intersection`() {
        val stop = Vec(-b - 100.0, -2 * b - 30.0)
        val destination = Vec(3 * b, -9 * b)
        val points = RoadRouter.route(Vec(0.0, 0.0), listOf(stop, destination))
        assertTrue(points.contains(stop))
        assertEquals(destination, points.last())
        points.zipWithNext().forEach { (p, q) -> assertTrue("$p → $q", axisAligned(p, q)) }
    }

    @Test
    fun `a route path walks the polyline with distance and heading`() {
        // 북쪽으로 2블록 → 동쪽으로 1블록 (격자 기준)
        val path = RoutePath(anchor, listOf(Vec(0.0, 0.0), Vec(0.0, -2 * b), Vec(b, -2 * b)))
        assertEquals(3 * b, path.length, 1e-6)

        val start = path.at(0.0)
        assertEquals(0.0, GeoUtils.distanceMeters(anchor, start.location), 1.0)
        // 격자가 화면에서 -14° 돌아가 있으므로 '격자 북쪽'은 화면에서 -14° (= 346°) 방향
        assertEquals(-14.0, start.headingDegrees, 0.5)

        val midway = path.at(1.5 * b)
        val expected = RoadGrid.fromGrid(anchor, Vec(0.0, -1.5 * b))
        assertEquals(0.0, GeoUtils.distanceMeters(expected, midway.location), 1.0)

        val turned = path.at(2.5 * b)
        assertEquals(76.0, turned.headingDegrees, 0.5) // 격자 동쪽 = 90° - 14°

        assertEquals(path.points.last(), path.at(10 * b).location) // 끝을 넘어가면 끝에 선다
        val passed = path.passed(2.5 * b)
        assertEquals(3, passed.size) // 출발 · 꺾은 점 · 지금 위치
        assertEquals(0.0, GeoUtils.distanceMeters(passed.last(), turned.location), 1.0)
    }
}
