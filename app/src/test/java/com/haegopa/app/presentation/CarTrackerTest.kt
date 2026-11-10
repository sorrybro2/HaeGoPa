package com.haegopa.app.presentation

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.route.RoadGrid
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarTrackerTest {

    private val start = GeoPoint(37.5682, 126.9977)
    private val b = RoadGrid.BLOCK_METERS

    private fun distance(a: GeoPoint, c: GeoPoint) = GeoUtils.distanceMeters(a, c)

    @Test
    fun `without a destination it drives the virtual route and keeps going past its end`() {
        val tracker = CarTracker(start).apply { reset(start) }
        assertTrue(tracker.virtualRoute)
        assertEquals(start, tracker.car.value.location)

        tracker.advance(b) // 북쪽으로 1블록 (지도 투영의 평면 근사 ↔ 하버사인 차이 1% 이내)
        assertEquals(b, distance(start, tracker.car.value.location), b * 0.01)
        assertEquals(b, tracker.car.value.traveledMeters, 1e-6)

        val firstLength = tracker.path.value.length
        tracker.advance(firstLength) // 끝까지 → 다음 가상 구간을 이어 붙인다
        tracker.advance(b)
        assertTrue(tracker.car.value.traveledMeters in 1.0..b + 1.0)
    }

    @Test
    fun `the demo loop stays around the start, where the spots are, and comes back`() {
        val tracker = CarTracker(start).apply { reset(start) }
        val lap = tracker.path.value.length
        var farthest = 0.0
        var driven = 0.0
        while (driven < lap - 1) {
            tracker.advance(50.0)
            driven += 50.0
            farthest = maxOf(farthest, distance(start, tracker.car.value.location))
        }
        assertTrue("farthest $farthest m", farthest < 2_500.0)
        assertEquals(0.0, distance(start, tracker.car.value.location), 2.0)
    }

    @Test
    fun `with a destination it stops there and a new waypoint re-routes from where the car is`() {
        val tracker = CarTracker(start).apply { reset(start) }
        val destination = RoadGrid.fromGrid(start, com.haegopa.app.domain.route.Vec(2 * b, -3 * b))
        tracker.setWaypoints(listOf(Waypoint("공원", destination, WaypointKind.DESTINATION, 0)))
        assertFalse(tracker.virtualRoute)

        tracker.advance(2 * b)
        val here = tracker.car.value.location
        val stop = RoadGrid.fromGrid(start, com.haegopa.app.domain.route.Vec(2 * b + 80.0, -2 * b))
        tracker.setWaypoints(
            listOf(
                Waypoint("국밥", stop, WaypointKind.WAYPOINT, 1),
                Waypoint("공원", destination, WaypointKind.DESTINATION, 0),
            ),
        )
        // 새 경로는 지금 차 위치에서 시작하고, 차는 그 자리에 있다
        assertEquals(0.0, distance(here, tracker.path.value.points.first()), 1.0)
        assertEquals(here, tracker.car.value.location)
        assertEquals(0.0, tracker.car.value.traveledMeters, 1e-6)

        tracker.advance(100 * b)
        assertEquals(0.0, distance(destination, tracker.car.value.location), 1.0) // 목적지에 도착해 선다
        tracker.advance(b)
        assertEquals(0.0, distance(destination, tracker.car.value.location), 1.0)
    }

    @Test
    fun `real GPS movement moves the car and pauses the time-lapse drive`() {
        val tracker = CarTracker(start).apply { reset(start) }
        assertFalse("first fix is the baseline", tracker.onRealLocation(start, 1_000))
        assertFalse("jitter is ignored", tracker.onRealLocation(GeoUtils.offset(start, 5.0, 0.0), 2_000))
        assertTrue(tracker.shouldSimulate(2_000))

        val moved = GeoUtils.offset(start, 60.0, 90.0) // 동쪽으로 60m
        assertTrue(tracker.onRealLocation(moved, 3_000))
        assertEquals(moved, tracker.car.value.location)
        // 직전 실제 위치(5m 북쪽)에서 본 방향 → 거의 동쪽
        assertEquals(90.0, tracker.car.value.headingDegrees, 6.0)
        assertFalse(tracker.shouldSimulate(3_000 + CarTracker.REAL_HOLD_MILLIS - 1))
        assertTrue(tracker.shouldSimulate(3_000 + CarTracker.REAL_HOLD_MILLIS + 1))
    }
}
