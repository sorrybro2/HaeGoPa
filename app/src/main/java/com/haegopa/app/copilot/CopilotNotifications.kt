package com.haegopa.app.copilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.haegopa.app.MainActivity
import com.haegopa.app.R
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.util.GeoUtils

/**
 * 코파일럿 알림.
 * - 상태(조용함): 포그라운드 서비스용 "음성 코파일럿 켜짐" + [끄기]
 * - 제안(중요도 높음): "먹고파 · 할매 순두부" + [경유지 추가] · [괜찮아요]. 소리는 끈다 — 같은 내용을 TTS 가 읽는다.
 *   일반 AAOS 는 헤드업으로 띄우지만, Pleos Connect 에뮬레이터 v2.1.0 SystemUI 는 서드파티 알림을
 *   UNKNOWN 유형으로 보고 팝업 없이 알림 센터에만 넣는다 (2026-11-10 확인) → 음성이 기본, 알림은 보조.
 */
class CopilotNotifications(context: Context) : CopilotPresenter {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "음성 코파일럿 상태", NotificationManager.IMPORTANCE_LOW).apply {
                description = "코파일럿이 켜져 있는 동안 표시돼요"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SUGGESTION, "음성 코파일럿 제안", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "순정 내비 화면 위에 쉴 곳 · 맛집 제안과 [경유지 추가] 버튼을 띄워요"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    fun statusNotification(): Notification =
        NotificationCompat.Builder(appContext, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("해고파 음성 코파일럿 켜짐")
            .setContentText("내비 화면을 띄운 채로도 쉴 곳 · 맛집을 말로 권해 드려요")
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, "끄기", actionIntent(ACTION_DISABLE, 0L))
            .build()

    override fun show(suggestion: CopilotSuggestion, phase: CopilotPhase, heard: String?) {
        if (!manager.areNotificationsEnabled()) return
        val spot = suggestion.spot
        val detail = buildString {
            append(GeoUtils.formatDistance(spot.distanceMeters))
            if (suggestion.asWaypoint && spot.category == SpotCategory.EAT && spot.detourMinutes > 0) {
                append(" · 경로 +${spot.detourMinutes}분")
            }
        }
        val status = when (phase) {
            CopilotPhase.LISTENING -> "🎙 듣고 있어요 — \"응\" · \"아니\""
            CopilotPhase.WAITING_TAP -> heard?.let { "\"$it\" — 버튼으로 골라 주세요" } ?: "버튼으로 골라 주세요"
            CopilotPhase.SPEAKING, CopilotPhase.IDLE -> suggestion.script
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_SUGGESTION)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("${spot.category.label} · ${spot.name}")
            // 차량 알림은 BigTextStyle 을 지원하지 않는다 (CarNotificationAdapter) → 한 줄 본문
            .setContentText("$detail · $status")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(SUGGESTION_TIMEOUT_MS)
            .setContentIntent(openAppIntent())
            .addAction(0, if (suggestion.asWaypoint) "경유지 추가" else "안내 시작", actionIntent(ACTION_ADD, suggestion.id))
            .addAction(0, "괜찮아요", actionIntent(ACTION_DECLINE, suggestion.id))
            .build()
        try {
            manager.notify(SUGGESTION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing", e)
        }
    }

    override fun dismiss(suggestionId: Long) {
        manager.cancel(SUGGESTION_ID)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, suggestionId: Long): PendingIntent {
        val intent = Intent(appContext, CopilotActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_SUGGESTION_ID, suggestionId)
        val requestCode = action.hashCode() * 31 + suggestionId.toInt()
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "CopilotNotifications"
        const val CHANNEL_STATUS = "copilot_status"
        const val CHANNEL_SUGGESTION = "copilot_suggestion"
        const val STATUS_ID = 2001
        const val SUGGESTION_ID = 2002
        private const val SUGGESTION_TIMEOUT_MS = 65_000L

        const val ACTION_ADD = "com.haegopa.app.copilot.ADD"
        const val ACTION_DECLINE = "com.haegopa.app.copilot.DECLINE"
        const val ACTION_DISABLE = "com.haegopa.app.copilot.DISABLE"
        const val EXTRA_SUGGESTION_ID = "suggestionId"
    }
}
