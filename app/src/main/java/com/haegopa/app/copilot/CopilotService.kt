package com.haegopa.app.copilot

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.haegopa.app.HaegopaApplication

/**
 * 순정 내비가 앞에 있는 동안 해고파 프로세스를 살려 두는 포그라운드 서비스.
 * Android 14 는 화면이 없는 백그라운드 앱을 얼려(cached app freezer) 타이머 · 음성 콜백이 멈추므로,
 * 코파일럿이 켜져 있으면 이 서비스가 "음성 코파일럿 켜짐" 알림과 함께 떠 있는다.
 * 실제 판단은 앱 단일 인스턴스인 [CopilotEngine] 이 한다.
 */
class CopilotService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as HaegopaApplication).container
        val notification = container.copilotNotifications.statusNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(CopilotNotifications.STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(CopilotNotifications.STATUS_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
            stopSelf()
        }
        // 시스템이 서비스를 정리하면 다음에 해고파 화면이 열릴 때 다시 시작한다
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "CopilotService"

        /** 해고파 화면이 앞에 있을 때 부른다 (Android 12+ 는 백그라운드에서 포그라운드 서비스를 시작할 수 없다) */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CopilotService::class.java)) }
                .onFailure { Log.w(TAG, "could not start co-pilot service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CopilotService::class.java))
        }
    }
}

/** 헤드업 알림 버튼 → 코파일럿 엔진 */
class CopilotActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as HaegopaApplication).container
        val id = intent.getLongExtra(CopilotNotifications.EXTRA_SUGGESTION_ID, -1L)
        when (intent.action) {
            CopilotNotifications.ACTION_ADD -> container.copilot.answer(id, CopilotAnswer.ADD, AnswerSource.BUTTON)
            CopilotNotifications.ACTION_DECLINE -> container.copilot.answer(id, CopilotAnswer.DECLINE, AnswerSource.BUTTON)
            CopilotNotifications.ACTION_DISABLE -> container.setCopilotEnabled(false)
        }
    }
}

/** 켜짐/꺼짐 (기본 켜짐) */
class PrefsCopilotSettings(context: Context) : CopilotSettings {
    private val prefs = context.getSharedPreferences("copilot", Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
