package com.haegopa.app.sdk

import com.haegopa.app.sdk.mock.MockNaviHelperImpl
import com.haegopa.app.sdk.mock.MockVehicleManagerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockSdkTest {

    @Test
    fun `navi helper accepts two waypoints like Pleos WaypointIndex FIRST and SECOND`() {
        val navi = MockNaviHelperImpl()
        assertTrue(navi.addWaypoint("골목 손칼국수", 37.39, 127.11))
        assertEquals(WaypointResult.AlreadyAdded, navi.addWaypointDetailed("골목 손칼국수", 37.39, 127.11))
        assertTrue(navi.addWaypoint("옛 철길 숲길", 37.40, 127.11))
        assertFalse(navi.addWaypoint("노을 전망 언덕", 37.41, 127.10))
        assertEquals(listOf(1, 2), navi.waypoints.value.map { it.order })
    }

    @Test
    fun `clearing waypoints keeps the destination`() {
        val navi = MockNaviHelperImpl()
        navi.recordDestination("강변 잔디공원", 37.39, 127.12)
        navi.addWaypoint("골목 손칼국수", 37.39, 127.11)
        navi.clearWaypoints()
        assertEquals(listOf(WaypointKind.DESTINATION), navi.waypoints.value.map { it.kind })
    }

    @Test
    fun `setting a destination replaces the whole route and blocks duplicates`() {
        val navi = MockNaviHelperImpl()
        navi.addWaypoint("골목 손칼국수", 37.39, 127.11)
        assertEquals(
            WaypointResult.SetAsDestination(viaPleos = false),
            navi.setDestinationDetailed("온기 베이커리", 37.40, 127.10),
        )
        assertEquals(listOf("온기 베이커리"), navi.waypoints.value.map { it.name })
        assertEquals(listOf(WaypointKind.DESTINATION), navi.waypoints.value.map { it.kind })
        assertEquals(WaypointResult.AlreadyDestination, navi.setDestinationDetailed("온기 베이커리", 37.40, 127.10))
    }

    @Test
    fun `waypoints can be added on top of a chosen destination`() {
        val navi = MockNaviHelperImpl()
        navi.setDestinationDetailed("강변 잔디공원", 37.39, 127.12)
        assertEquals(WaypointResult.AddedAsWaypoint(order = 1, viaPleos = false), navi.addWaypointDetailed("골목 손칼국수", 37.39, 127.11))
        assertEquals(WaypointResult.AlreadyAdded, navi.addWaypointDetailed("강변 잔디공원", 37.39, 127.12))
        assertEquals(
            listOf(WaypointKind.DESTINATION, WaypointKind.WAYPOINT),
            navi.waypoints.value.map { it.kind },
        )
    }

    @Test
    fun `vehicle mock delivers speed to the listener and tracks trip time`() {
        var now = 0L
        val vehicle = MockVehicleManagerImpl(clock = { now }, initialTripMinutes = 95)
        val speeds = mutableListOf<Int>()
        vehicle.setSpeedChangeListener { speeds += it }
        vehicle.simulateSpeed(60)
        vehicle.simulateSpeed(-5)
        assertEquals(listOf(0, 60, 0), speeds)

        now += 10 * 60_000L
        assertEquals(105, vehicle.getTripDurationMinutes())
        vehicle.addSimulatedTripMinutes(30)
        assertEquals(135, vehicle.getTripDurationMinutes())
    }
}
