package com.haegopa.app.sdk.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.haegopa.app.domain.model.GearPosition
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.VehicleGateway
import com.haegopa.app.sdk.car.CarApiAvailability
import com.haegopa.app.sdk.car.CarDrivingSignals
import com.haegopa.app.sdk.mock.MockVehicleManagerImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 차량 신호 어댑터 — 여러 신호원을 하나의 "차속 리스너"로 합친다 (마지막 값 우선).
 *
 * 1. AAOS VHAL      : `PERF_VEHICLE_SPEED`(차속) · `GEAR_SELECTION`(기어) — [CarDrivingSignals]
 * 2. Pleos Vehicle  : `Vehicle.getOdometer().registerTripCurrentDrivingTime()` → 연속 주행 시간(피로 알림)
 *                     (Pleos Vehicle SDK 2.3.2 는 기어 API 를 deprecated 하고 GEAR_SELECTION 사용을 권고)
 * 3. 기어 연동 가상 차속 : Virtual Console(v2.1.0) Motion 도메인은 기어만 바꿀 수 있어서,
 *                     **에뮬레이터에서만** "기어 D → 30km/h, P → 0km/h" 로 주행 상태를 시연할 수 있게 한다.
 * 4. PT 데모 패널    : 화면의 정차 / 30 / 60 / 100 버튼
 */
class PleosVehicleGateway(context: Context) : VehicleGateway {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val simulator = MockVehicleManagerImpl()
    private val gearLinkedSimulation = EmulatorDetector.isProbablyEmulator()

    private val _status = MutableStateFlow(SdkStatus("Pleos Vehicle", SdkBackend.CONNECTING, "연결 중"))
    override val status: StateFlow<SdkStatus> = _status.asStateFlow()

    override val gear: StateFlow<GearPosition> = simulator.gear
    override val speedSource: StateFlow<String> = simulator.speedSource
    override val speedKmh: StateFlow<Int> = simulator.speedKmh

    @Volatile
    private var vehicle: Vehicle? = null

    @Volatile
    private var pleosConnected = false

    @Volatile
    private var pleosTripMinutes: Int? = null

    @Volatile
    private var demoTripOffsetMinutes = 0

    @Volatile
    private var lastVhalSpeed: Int? = null

    @Volatile
    private var carSignals: CarDrivingSignals? = null

    private var registrationAttempts = 0
    private var retryScheduled = false

    private val tripListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(time: Int?) {
            onPleosTripTime(time)
        }

        override fun onFailed(e: Exception) {
            onRegistrationFailed(e)
        }
    }

    init {
        connectCarSignals()
        connectPleosVehicle()
        mainHandler.postDelayed({ updateStatus() }, STATUS_TIMEOUT_MS)
    }

    // ── MockVehicleManager ─────────────────────────────────────────────────────

    override fun setSpeedChangeListener(listener: (speed: Int) -> Unit) {
        simulator.setSpeedChangeListener(listener)
    }

    override fun getTripDurationMinutes(): Int =
        pleosTripMinutes?.let { it + demoTripOffsetMinutes } ?: simulator.getTripDurationMinutes()

    // ── VehicleGateway ─────────────────────────────────────────────────────────

    override fun simulateSpeed(speedKmh: Int) {
        simulator.simulateSpeed(speedKmh)
    }

    override fun addSimulatedTripMinutes(minutes: Int) {
        demoTripOffsetMinutes += minutes
        simulator.addSimulatedTripMinutes(minutes)
    }

    override fun refreshSensors() {
        mainHandler.post {
            connectCarSignals()
            if (!pleosConnected && vehicle != null && !retryScheduled) registerPleosListeners()
        }
    }

    // ── 1) AAOS VHAL 차속 · 기어 ────────────────────────────────────────────────

    private fun connectCarSignals() {
        if (!CarApiAvailability.isCarApiAvailable(appContext)) {
            updateStatus()
            return
        }
        val signals = carSignals ?: try {
            CarDrivingSignals.connect(
                context = appContext,
                onSpeedKmh = { kmh -> onVhalSpeed(kmh) },
                onGear = { gear -> onGearChanged(gear) },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Car API connection failed", t)
            null
        }
        carSignals = signals
        if (signals != null) {
            signals.subscribeGear()
            if (CarApiAvailability.hasSpeedPermission(appContext)) signals.subscribeSpeed()
        }
        updateStatus()
    }

    private fun onVhalSpeed(kmh: Int) {
        val previous = lastVhalSpeed
        lastVhalSpeed = kmh
        // PERF_VEHICLE_SPEED 는 CONTINUOUS 속성이라 같은 값이 주기적으로 반복된다.
        // 값이 바뀌었거나 실제로 움직일 때만 반영해, 데모/기어 연동 값을 0 으로 덮어쓰지 않게 한다.
        if (kmh > 0 || previous == null || previous != kmh) emitOnMain(kmh, "VHAL 차속")
    }

    private fun onGearChanged(gear: GearPosition) {
        mainHandler.post {
            simulator.updateGear(gear)
            if (!gearLinkedSimulation) return@post
            if ((lastVhalSpeed ?: 0) > 0) return@post // 실제 차속이 들어오면 실제 값 우선
            val simulated = when (gear) {
                GearPosition.DRIVE -> DEMO_CRUISE_KMH
                GearPosition.REVERSE -> DEMO_REVERSE_KMH
                GearPosition.PARK, GearPosition.NEUTRAL -> 0
                GearPosition.UNKNOWN -> return@post
            }
            simulator.emit(simulated, "Virtual Console 기어 ${gear.label} 연동")
        }
    }

    // ── 2) Pleos Vehicle SDK (주행 시간) ────────────────────────────────────────

    private fun connectPleosVehicle() {
        if (!PleosServiceProbe.isVehicleServiceAvailable(appContext)) {
            // Pleos Vehicle 서비스가 없는 기기: SDK 재연결 루프를 만들지 않고 VHAL/Mock 만 사용
            updateStatus()
            return
        }
        val created = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (t: Throwable) {
            Log.i(TAG, "Pleos Vehicle SDK unavailable", t)
            null
        }
        vehicle = created
        if (created != null) registerPleosListeners() else updateStatus()
    }

    private fun registerPleosListeners() {
        val v = vehicle ?: return
        retryScheduled = false
        registrationAttempts += 1
        try {
            val odometer = v.getOdometer()
            runCatching { odometer.unregisterTripCurrentDrivingTime(tripListener) }
            odometer.registerTripCurrentDrivingTime(tripListener)
            odometer.getTripCurrentDrivingTime(
                { minutes -> onPleosTripTime(minutes) },
                { error -> onRegistrationFailed(error) },
            )
        } catch (t: Throwable) {
            onRegistrationFailed(t)
        }
    }

    private fun onPleosTripTime(minutes: Int?) {
        // 단위: 분으로 가정 (Pleos 계기판 '주행 시간'과 동일). 실차 검증 시 문서로 재확인 필요.
        pleosTripMinutes = minutes?.coerceAtLeast(0)
        if (!pleosConnected) {
            pleosConnected = true
            mainHandler.post { updateStatus() }
        }
    }

    /** Vehicle.initialize() 는 비동기 바인딩이라 첫 등록이 실패할 수 있어 짧게 재시도한다 */
    private fun onRegistrationFailed(error: Throwable) {
        mainHandler.post {
            if (pleosConnected || retryScheduled) return@post
            if (registrationAttempts < MAX_REGISTRATION_ATTEMPTS) {
                retryScheduled = true
                mainHandler.postDelayed({ registerPleosListeners() }, RETRY_DELAY_MS * registrationAttempts)
            } else {
                Log.i(TAG, "Pleos Vehicle trip time not available", error)
                updateStatus()
            }
        }
    }

    // ── 공통 ──────────────────────────────────────────────────────────────────

    private fun emitOnMain(kmh: Int, source: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            simulator.emit(kmh, source)
        } else {
            mainHandler.post { simulator.emit(kmh, source) }
        }
    }

    private fun updateStatus() {
        val signals = carSignals
        val vhal = buildList {
            if (signals?.speedSubscribed == true) add("차속")
            if (signals?.gearSubscribed == true) add("기어")
        }
        val vhalText = if (vhal.isEmpty()) "" else "VHAL ${vhal.joinToString("·")}"
        _status.value = when {
            pleosConnected -> SdkStatus(
                "Pleos Vehicle",
                SdkBackend.PLEOS,
                listOf("주행시간", vhalText).filter { it.isNotEmpty() }.joinToString(" + "),
            )
            vhal.isNotEmpty() -> SdkStatus("AAOS Car API", SdkBackend.ANDROID, vhalText)
            else -> SdkStatus("Mock Vehicle", SdkBackend.MOCK, "PT 데모 패널로 차속 입력")
        }
    }

    private companion object {
        const val TAG = "PleosVehicle"
        const val DEMO_CRUISE_KMH = 30
        const val DEMO_REVERSE_KMH = 5
        const val MAX_REGISTRATION_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 800L
        const val STATUS_TIMEOUT_MS = 5_000L
    }
}
