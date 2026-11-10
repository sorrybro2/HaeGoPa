package com.haegopa.app.sdk.mock

import com.haegopa.app.domain.model.GearPosition
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.VehicleGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 차량 Mock: 차속은 PT 데모 패널(정차 / 30 / 60 / 100 km/h)로, 주행 시간은 시계로 시뮬레이션한다.
 * [clock] 을 주입할 수 있어 JVM 단위 테스트가 가능하다.
 */
class MockVehicleManagerImpl(
    private val clock: () -> Long = { System.currentTimeMillis() },
    initialTripMinutes: Int = DEMO_INITIAL_TRIP_MINUTES,
) : VehicleGateway {

    private val startedAt = clock()

    @Volatile
    private var extraTripMinutes = initialTripMinutes

    @Volatile
    private var listener: ((Int) -> Unit)? = null

    private val _status = MutableStateFlow(
        SdkStatus("Mock Vehicle", SdkBackend.MOCK, "PT 데모 패널로 차속 입력"),
    )
    override val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private val _gear = MutableStateFlow(GearPosition.UNKNOWN)
    override val gear: StateFlow<GearPosition> = _gear.asStateFlow()

    private val _speedSource = MutableStateFlow("PT 데모 시뮬레이터")
    override val speedSource: StateFlow<String> = _speedSource.asStateFlow()

    private val _speedKmh = MutableStateFlow(0)
    override val speedKmh: StateFlow<Int> = _speedKmh.asStateFlow()

    override fun setSpeedChangeListener(listener: (speed: Int) -> Unit) {
        this.listener = listener
        listener(_speedKmh.value)
    }

    override fun getTripDurationMinutes(): Int =
        extraTripMinutes + ((clock() - startedAt) / 60_000L).toInt()

    override fun simulateSpeed(speedKmh: Int) {
        emit(speedKmh.coerceAtLeast(0), "PT 데모 시뮬레이터")
    }

    /** Pleos 게이트웨이가 다른 신호원(VHAL, 기어 연동)의 값을 같은 리스너로 흘려보낼 때 사용 */
    fun emit(speedKmh: Int, source: String) {
        _speedKmh.value = speedKmh
        _speedSource.value = source
        listener?.invoke(speedKmh)
    }

    fun updateGear(gear: GearPosition) {
        _gear.value = gear
    }

    override fun addSimulatedTripMinutes(minutes: Int) {
        extraTripMinutes += minutes
    }

    override fun refreshSensors() = Unit

    companion object {
        /** 출근길 정체 1시간 35분째라는 데모 시나리오 */
        const val DEMO_INITIAL_TRIP_MINUTES = 95
    }
}
