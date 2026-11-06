package com.haegopa.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.haegopa.app.ui.theme.HaegopaColors
import kotlinx.coroutines.delay

private data class StretchStep(val emoji: String, val title: String, val guide: String)

private val STEPS = listOf(
    StretchStep("🙆", "목 옆으로 늘리기", "귀를 어깨 쪽으로 천천히 기울이고 좌우로 5초씩 버텨요."),
    StretchStep("🤷", "어깨 으쓱 툭", "어깨를 귀까지 끌어올렸다가 숨을 내쉬며 툭 떨어뜨려요."),
    StretchStep("🙌", "팔 위로 쭉", "깍지를 끼고 손바닥이 하늘을 향하게 쭉 밀어 올려요."),
    StretchStep("🔄", "허리 비틀기", "등받이를 잡고 상체를 좌우로 천천히 돌려요."),
    StretchStep("✊", "손목·발목 돌리기", "손목과 발목을 바깥쪽, 안쪽으로 다섯 번씩 돌려요."),
    StretchStep("🌬️", "4-6 호흡", "코로 4초 들이마시고, 입으로 6초 길게 내쉬어요."),
)
private const val STEP_SECONDS = 10
private val TOTAL_SECONDS = STEPS.size * STEP_SECONDS

/**
 * 쉬고파 · 정차 전용 "1분 힐링 스트레칭" 가이드 (10초 × 6동작, 단계별 TTS 안내).
 * 프로토타입에서는 영상 대신 애니메이션 가이드로 대체했다.
 */
@Composable
fun StretchingGuideDialog(
    placeName: String?,
    onSpeak: (String) -> Unit,
    onClose: () -> Unit,
) {
    val speak by rememberUpdatedState(onSpeak)
    var runId by remember { mutableIntStateOf(0) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(STEP_SECONDS) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(runId) {
        finished = false
        for (i in STEPS.indices) {
            stepIndex = i
            val step = STEPS[i]
            val intro = if (i == 0) "1분 힐링 스트레칭을 시작할게요. " else ""
            speak("$intro${i + 1}단계, ${step.title}. ${step.guide}")
            for (s in STEP_SECONDS downTo 1) {
                secondsLeft = s
                delay(1_000)
            }
        }
        finished = true
        speak("스트레칭 끝! 몸이 한결 가벼워졌죠? 오늘도 안전 운전하세요.")
    }

    val elapsed = if (finished) TOTAL_SECONDS else stepIndex * STEP_SECONDS + (STEP_SECONDS - secondsLeft)
    val progress = elapsed.toFloat() / TOTAL_SECONDS
    val step = STEPS[stepIndex]

    val transition = rememberInfiniteTransition(label = "stretch")
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = HaegopaColors.Surface,
            border = BorderStroke(2.dp, HaegopaColors.Rest.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 880.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "🎬 1분 힐링 스트레칭" + (placeName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleLarge,
                    color = HaegopaColors.Rest,
                )
                Text(
                    text = "정차 중에만 열리는 가이드예요 · 영상 가이드 프로토타입(애니메이션 대체)",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextTertiary,
                )
                Spacer(Modifier.height(18.dp))

                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                    Canvas(Modifier.size(200.dp)) {
                        val stroke = 12.dp.toPx()
                        drawArc(
                            color = HaegopaColors.SurfaceHigher,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(stroke),
                        )
                        drawArc(
                            color = HaegopaColors.Rest,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                    Text(
                        text = if (finished) "🎉" else step.emoji,
                        fontSize = 76.sp,
                        modifier = Modifier.scale(if (finished) 1f else breathe),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (finished) "스트레칭 완료!" else "${stepIndex + 1}/6 · ${step.title}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = HaegopaColors.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (finished) "몸이 한결 가벼워졌죠? 물 한 모금 마시고 출발해요." else step.guide,
                    style = MaterialTheme.typography.bodyLarge,
                    color = HaegopaColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                if (!finished) {
                    Text(
                        text = "이 동작 ${secondsLeft}초 · 전체 남은 시간 0:%02d".format(TOTAL_SECONDS - elapsed),
                        style = MaterialTheme.typography.labelLarge,
                        color = HaegopaColors.Rest,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STEPS.indices.forEach { i ->
                        Box(
                            Modifier
                                .size(if (i == stepIndex && !finished) 14.dp else 10.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        finished || i < stepIndex -> HaegopaColors.Rest
                                        i == stepIndex -> HaegopaColors.Booster
                                        else -> HaegopaColors.SurfaceHigher
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (finished) {
                        Button(
                            onClick = { runId += 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = HaegopaColors.Rest, contentColor = HaegopaColors.Background),
                        ) { Text("🔁 한 번 더") }
                        Spacer(Modifier.width(4.dp))
                    }
                    OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, HaegopaColors.Outline)) {
                        Text("닫기", color = HaegopaColors.TextPrimary)
                    }
                }
            }
        }
    }
}
