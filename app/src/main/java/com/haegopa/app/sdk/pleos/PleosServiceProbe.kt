package com.haegopa.app.sdk.pleos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Pleos 시스템 서비스가 이 기기에 있는지 미리 확인한다.
 * 없는 기기(일반 AVD)에서 SDK 를 초기화하면 SDK 가 서비스 바인딩을 계속 재시도하므로,
 * 서비스가 없을 때는 SDK 초기화를 건너뛰고 곧바로 Mock 으로 동작한다.
 *
 * 패키지명·액션은 각 SDK 의 바인딩 대상(AndroidManifest <queries>, 바인딩 로그)과 동일하다.
 * 패키지 확인과 인텐트 조회 중 하나라도 성공하면 "있음"으로 본다 (오판으로 실제 SDK 를 끄지 않도록).
 */
internal object PleosServiceProbe {

    private val NAVI_PACKAGES = listOf("ai.pleos.playground.service.navi")
    private const val NAVI_ACTION = "ai.pleos.playground.intent.action.NAVI_START"

    private val VEHICLE_PACKAGES = listOf("ai.pleos.playground.service.vehicle")
    private const val VEHICLE_ACTION = "ai.pleos.playground.vehicle.VEHICLE_START"

    private val SPEECH_PACKAGES = listOf("ai.pleos.playground.caas", "ai.pleos.playground.service.caas")
    private const val SPEECH_ACTION = "ai.pleos.playground.caas.intent.action.SPEECH_START"

    fun isNaviServiceAvailable(context: Context): Boolean = available(context, NAVI_PACKAGES, NAVI_ACTION)

    fun isVehicleServiceAvailable(context: Context): Boolean = available(context, VEHICLE_PACKAGES, VEHICLE_ACTION)

    fun isSpeechServiceAvailable(context: Context): Boolean = available(context, SPEECH_PACKAGES, SPEECH_ACTION)

    @Suppress("DEPRECATION")
    private fun available(context: Context, packages: List<String>, action: String): Boolean {
        val pm = context.packageManager
        val installed = packages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
        if (installed) return true
        return runCatching { pm.queryIntentServices(Intent(action), 0).isNotEmpty() }.getOrDefault(false)
    }
}
