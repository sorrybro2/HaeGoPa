package com.haegopa.app.presentation

import com.haegopa.app.data.MockDataRepository
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SpotCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainUiStateTest {

    @Test
    fun `selected spot opens the detail panel only while parked`() = runTest {
        val spot = MockDataRepository(latencyMillis = 0)
            .getNearbySpots(SpotCategory.PLAY, GeoPoint.DEFAULT_MOCK_ORIGIN, 0, 1).items.first()
        val parked = MainUiState(selectedSpot = spot, speedKmh = 0)
        assertEquals(spot, parked.detailSpot)
        // 주행 중에는 지도만 그 스팟으로 옮기고, 상세 패널은 띄우지 않는다 (Driver Distraction)
        val driving = parked.copy(speedKmh = 30)
        assertEquals(spot, driving.selectedSpot)
        assertNull(driving.detailSpot)
    }
}
