package com.haegopa.app.data

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.repository.SpotRepository
import com.haegopa.app.domain.util.GeoUtils
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MockDataRepositoryTest {

    private val repository = MockDataRepository(latencyMillis = 0)
    private val origin = GeoPoint.DEFAULT_MOCK_ORIGIN

    @Test
    fun `each category has at least 10 spots within 2km`() = runTest {
        SpotCategory.entries.forEach { category ->
            val page = repository.getNearbySpots(category, origin, page = 0, pageSize = 100)
            assertTrue("$category should have >= 10 spots", page.items.size >= 10)
            assertTrue(page.items.all { it.distanceMeters <= SpotRepository.DEFAULT_RADIUS_METERS })
        }
    }

    @Test
    fun `spots are sorted by distance ascending`() = runTest {
        SpotCategory.entries.forEach { category ->
            val distances = repository.getNearbySpots(category, origin, 0, 100).items.map { it.distanceMeters }
            assertEquals(distances.sorted(), distances)
        }
    }

    @Test
    fun `paging walks the whole radius without duplicates`() = runTest {
        val category = SpotCategory.EAT
        val collected = mutableListOf<String>()
        var page = 0
        var hasMore = true
        while (hasMore) {
            val result = repository.getNearbySpots(category, origin, page, pageSize = 5)
            collected += result.items.map { it.id }
            hasMore = result.hasMore
            page += 1
        }
        val all = repository.getNearbySpots(category, origin, 0, 100)
        assertEquals(all.totalInRadius, collected.size)
        assertEquals(collected.size, collected.toSet().size)
    }

    @Test
    fun `eat spots respect the 5 minute detour rule and radius`() = runTest {
        val items = repository.getNearbySpots(SpotCategory.EAT, origin, 0, 100).items
        assertTrue(items.all { it.detourMinutes <= MockDataRepository.MAX_EAT_DETOUR_MINUTES })
        assertFalse(items.any { it.name == "칼국수 본점" }) // 우회 7분
        assertFalse(items.any { it.name == "수제버거 하우스" }) // 2.15km
    }

    @Test
    fun `distance field matches generated coordinates`() = runTest {
        SpotCategory.entries.forEach { category ->
            repository.getNearbySpots(category, origin, 0, 100).items.forEach { spot ->
                val measured = GeoUtils.distanceMeters(origin, spot.location)
                assertTrue("${spot.name}: $measured vs ${spot.distanceMeters}", abs(measured - spot.distanceMeters) <= 1.0)
            }
        }
    }

    @Test
    fun `spots follow any origin such as the NaviHelper current location`() = runTest {
        val seoulCityHall = GeoPoint(37.5663, 126.9779)
        val items = repository.getNearbySpots(SpotCategory.REST, seoulCityHall, 0, 100).items
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { GeoUtils.distanceMeters(seoulCityHall, it.location) <= 2_001 })
    }

    @Test
    fun `play spots provide a TTS docent script`() = runTest {
        val items = repository.getNearbySpots(SpotCategory.PLAY, origin, 0, 100).items
        assertTrue(items.all { it.hasDocent })
    }
}
