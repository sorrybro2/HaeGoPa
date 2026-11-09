package com.haegopa.app.data

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotTag
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
        assertFalse(items.any { it.name == "한정식 본가" }) // 우회 7분
        assertFalse(items.any { it.name == "수제버거 하우스" }) // 2.15km
    }

    @Test
    fun `every spot leads with a kind that fits its category`() = runTest {
        val allowed = mapOf(
            SpotCategory.EAT to setOf(SpotTag.RESTAURANT, SpotTag.CAFE, SpotTag.DESSERT),
            SpotCategory.PLAY to setOf(SpotTag.PARK, SpotTag.DEPARTMENT_STORE, SpotTag.THEME_PARK, SpotTag.ATTRACTION, SpotTag.LANDMARK),
            SpotCategory.REST to setOf(SpotTag.TRAIL, SpotTag.PARK, SpotTag.BATHHOUSE, SpotTag.SAUNA, SpotTag.MASSAGE),
        )
        SpotCategory.entries.forEach { category ->
            allSpots(category).forEach { spot ->
                assertTrue("${spot.name}: 첫 태그가 종류여야 함", spot.tags.first().isKind)
                assertTrue("${spot.name}: ${spot.kind}", spot.kind in allowed.getValue(category))
            }
        }
    }

    @Test
    fun `stretching is offered only on rest trails and parks`() = runTest {
        val rest = allSpots(SpotCategory.REST)
        assertTrue(rest.filter { it.kind == SpotTag.TRAIL || it.kind == SpotTag.PARK }.all { it.supportsStretching })
        assertTrue(rest.filter { it.kind == SpotTag.SAUNA || it.kind == SpotTag.MASSAGE || it.kind == SpotTag.BATHHOUSE }.none { it.supportsStretching })
        assertTrue(allSpots(SpotCategory.PLAY).none { it.supportsStretching }) // 놀고파 공원은 스트레칭 대상 아님
    }

    private suspend fun allSpots(category: SpotCategory) =
        repository.getNearbySpots(category, origin, 0, 100, radiusMeters = 20_000).items

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
    fun `docent is provided exactly for play attractions and landmarks`() = runTest {
        val play = allSpots(SpotCategory.PLAY)
        val sights = play.filter { it.kind == SpotTag.ATTRACTION || it.kind == SpotTag.LANDMARK }
        assertTrue(sights.isNotEmpty())
        assertTrue(sights.all { it.hasDocent })
        assertTrue((play - sights.toSet()).none { it.hasDocent })
        assertTrue(allSpots(SpotCategory.EAT).none { it.hasDocent })
        assertTrue(allSpots(SpotCategory.REST).none { it.hasDocent })
    }
}
