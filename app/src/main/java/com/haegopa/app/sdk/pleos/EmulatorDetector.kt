package com.haegopa.app.sdk.pleos

import android.os.Build

/** 에뮬레이터 여부 추정 — 실차에서는 "기어 연동 가상 차속"을 절대 쓰지 않기 위한 안전장치 */
internal object EmulatorDetector {
    fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val model = Build.MODEL.orEmpty()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            fingerprint.contains("emu64") ||
            hardware.contains("ranchu") ||
            hardware.contains("goldfish") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for")
    }
}
