package com.haegopa.app.copilot

import com.haegopa.app.data.MockDataRepository
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotPolicyTest {

    private val config = CopilotConfig()
    private val lunch = 12 * 60
    private val afternoon = 15 * 60

    private fun input(
        minute: Int = afternoon,
        trip: Int = 30,
        driving: Boolean = true,
        route: Boolean = true,
        stops: Int = 0,
        visible: Boolean = false,
        now: Long = 10_000_000L,
    ) = TriggerInput(
        nowMillis = now,
        minuteOfDay = minute,
        dayKey = "2026-11-10",
        tripMinutes = trip,
        driving = driving,
        routeActive = route,
        waypointCount = stops,
        appVisible = visible,
    )

    @Test
    fun `lunch time suggests 먹고파 once per meal`() {
        val (decision, memory) = CopilotPolicy.decide(input(minute = lunch), TriggerMemory(), config)!!
        assertEquals(CopilotReason.MEAL, decision.reason)
        assertEquals(SpotCategory.EAT, decision.category)
        assertEquals("점심", decision.mealLabel)
        // 쿨다운이 지나도 같은 점심에는 다시 권하지 않는다
        val later = input(minute = lunch + 30, now = 10_000_000L + config.cooldownMillis * 2)
        assertNull(CopilotPolicy.decide(later, memory.copy(lastSuggestionAt = 10_000_000L), config))
    }

    @Test
    fun `two hours of driving suggests a rest before a meal`() {
        val (decision, _) = CopilotPolicy.decide(input(minute = lunch, trip = 125), TriggerMemory(), config)!!
        assertEquals(CopilotReason.FATIGUE, decision.reason)
        assertEquals(SpotCategory.REST, decision.category)
    }

    @Test
    fun `stays quiet while HAEGOPA is on screen, when parked without a route, or when waypoints are full`() {
        val memory = TriggerMemory()
        assertNull(CopilotPolicy.decide(input(minute = lunch, visible = true), memory, config))
        assertNull(CopilotPolicy.decide(input(minute = lunch, driving = false, route = false), memory, config))
        assertNull(CopilotPolicy.decide(input(minute = lunch, stops = 2), memory, config))
        // 정차 중이어도 길안내 중이면 말을 걸 수 있다 (신호 대기 · 에뮬레이터)
        assertNotNull(CopilotPolicy.decide(input(minute = lunch, driving = false, route = true), memory, config))
    }

    @Test
    fun `cooldown grows after each decline`() {
        val last = 10_000_000L
        val declinedTwice = TriggerMemory(lastSuggestionAt = last, consecutiveDeclines = 2)
        val afterBaseCooldown = input(trip = 130, now = last + config.cooldownMillis + 1)
        assertNull(CopilotPolicy.decide(afterBaseCooldown, declinedTwice, config))
        val afterTripleCooldown = input(trip = 130, now = last + config.cooldownMillis * 3 + 1)
        assertNotNull(CopilotPolicy.decide(afterTripleCooldown, declinedTwice, config))
    }

    @Test
    fun `picks the least detour restaurant at mealtime and skips declined ones`() = runTest {
        val spots = MockDataRepository(latencyMillis = 0)
            .getNearbySpots(SpotCategory.EAT, GeoPoint.DEFAULT_MOCK_ORIGIN, 0, 30).items
        val first = CopilotPolicy.pickSpot(SpotCategory.EAT, spots, emptySet(), CopilotReason.MEAL)!!
        assertEquals(SpotTag.RESTAURANT, first.kind)
        assertEquals(spots.minOf { it.detourMinutes }, first.detourMinutes)
        val second = CopilotPolicy.pickSpot(SpotCategory.EAT, spots, setOf(first.name), CopilotReason.MEAL)!!
        assertNotEquals(first.name, second.name)
        assertEquals(SpotTag.RESTAURANT, second.kind)
    }

    @Test
    fun `rest suggestions prefer a walk or a park where stretching works`() = runTest {
        val spots = MockDataRepository(latencyMillis = 0)
            .getNearbySpots(SpotCategory.REST, GeoPoint.DEFAULT_MOCK_ORIGIN, 0, 30).items
        val pick = CopilotPolicy.pickSpot(SpotCategory.REST, spots, emptySet(), CopilotReason.FATIGUE)!!
        assertTrue(pick.supportsStretching)
    }
}
