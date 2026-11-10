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
    fun `anchored spots stay put while the car moves, and distances are measured from the car`() = runTest {
        val anchored = MockDataRepository(latencyMillis = 0).apply { anchorAt(origin) }
        val atStart = anchored.getNearbySpots(SpotCategory.EAT, origin, 0, 100, radiusMeters = 20_000).items
        val driven = GeoUtils.offset(origin, 1_500.0, 0.0) // 북쪽으로 1.5km 달린 뒤
        val later = anchored.getNearbySpots(SpotCategory.EAT, driven, 0, 100, radiusMeters = 20_000).items

        // 같은 스팟은 같은 자리에 있다 (차를 따라오지 않는다)
        val before = atStart.associateBy { it.id }
        later.forEach { spot -> assertEquals(before.getValue(spot.id).location, spot.location) }
        // 거리 · 정렬은 차 위치 기준
        later.forEach { spot ->
            assertEquals(GeoUtils.distanceMeters(driven, spot.location), spot.distanceMeters.toDouble(), 1.0)
        }
        assertEquals(later.sortedBy { it.distanceMeters }.map { it.id }, later.map { it.id })
        assertTrue(later.first().address.startsWith("현재 위치 "))
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
