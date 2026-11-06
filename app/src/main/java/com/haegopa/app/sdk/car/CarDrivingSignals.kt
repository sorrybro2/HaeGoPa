package com.haegopa.app.sdk.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.haegopa.app.domain.model.GearPosition
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * android.car 클래스를 전혀 참조하지 않는 가드.
 * 일반 폰 AVD 에는 android.car 구현이 없으므로, 이 검사를 통과한 경우에만 [CarDrivingSignals] 를 로드한다.
 */
object CarApiAvailability {
    const val CAR_SPEED_PERMISSION = "android.car.permission.CAR_SPEED"

    fun isCarApiAvailable(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false
        return try {
            Class.forName("android.car.Car")
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun hasSpeedPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED
}

/**
 * AAOS 표준 VHAL 주행 신호 구독기.
 * - `PERF_VEHICLE_SPEED` (m/s → km/h, CONTINUOUS)  : android.car.permission.CAR_SPEED (런타임 권한)
 * - `GEAR_SELECTION`     (P/R/N/D, ON_CHANGE)      : android.car.permission.CAR_POWERTRAIN
 *   ※ Pleos Vehicle SDK 2.3.2 는 자체 기어 API 를 deprecated 하고 "Google GEAR_SELECTION property" 사용을 권고한다.
 *
 * Pleos Connect 에뮬레이터의 Virtual Console(Motion > Gear) 조작, 실차 VHAL,
 * `adb shell cmd car_service inject-vhal-event` 주입값에 모두 반응한다.
 */
internal class CarDrivingSignals private constructor(
    private val car: Car,
    private val propertyManager: CarPropertyManager,
    private val onSpeedKmh: (Int) -> Unit,
    private val onGear: (GearPosition) -> Unit,
) {
    @Volatile
    var speedSubscribed = false
        private set

    @Volatile
    var gearSubscribed = false
        private set

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val metersPerSecond = (value.value as? Float) ?: return
            onSpeedKmh((abs(metersPerSecond) * MS_TO_KMH).roundToInt())
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) = Unit
    }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = (value.value as? Int) ?: return
            onGear(gearFromVhal(raw))
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) = Unit
    }

    @Suppress("DEPRECATION")
    fun subscribeSpeed(): Boolean {
        if (!speedSubscribed) {
            speedSubscribed = runCatching {
                propertyManager.registerCallback(
                    speedCallback,
                    VehiclePropertyIds.PERF_VEHICLE_SPEED,
                    CarPropertyManager.SENSOR_RATE_NORMAL,
                )
            }.getOrDefault(false)
        }
        return speedSubscribed
    }

    @Suppress("DEPRECATION")
    fun subscribeGear(): Boolean {
        if (!gearSubscribed) {
            gearSubscribed = runCatching {
                propertyManager.registerCallback(
                    gearCallback,
                    VehiclePropertyIds.GEAR_SELECTION,
                    CarPropertyManager.SENSOR_RATE_ONCHANGE,
                )
            }.getOrDefault(false)
        }
        return gearSubscribed
    }

    @Suppress("DEPRECATION")
    fun release() {
        runCatching { propertyManager.unregisterCallback(speedCallback) }
        runCatching { propertyManager.unregisterCallback(gearCallback) }
        runCatching { car.disconnect() }
        speedSubscribed = false
        gearSubscribed = false
    }

    companion object {
        private const val MS_TO_KMH = 3.6f

        fun connect(
            context: Context,
            onSpeedKmh: (Int) -> Unit,
            onGear: (GearPosition) -> Unit,
        ): CarDrivingSignals? {
            val car = Car.createCar(context) ?: return null
            val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager == null) {
                runCatching { car.disconnect() }
                return null
            }
            return CarDrivingSignals(car, manager, onSpeedKmh, onGear)
        }

        /** VHAL VehicleGear 비트값 → 앱 기어 표현 */
        fun gearFromVhal(raw: Int): GearPosition = when (raw) {
            0x0000 -> GearPosition.UNKNOWN
            0x0001 -> GearPosition.NEUTRAL
            0x0002 -> GearPosition.REVERSE
            0x0004 -> GearPosition.PARK
            else -> GearPosition.DRIVE // GEAR_DRIVE(0x0008), GEAR_1(0x0010) ~ GEAR_9
        }
    }
}
