package com.haegopa.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haegopa.app.domain.model.SingAlongSong
import com.haegopa.app.presentation.QuizPhase
import com.haegopa.app.presentation.QuizUiState
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.Pretendard
import com.haegopa.app.ui.theme.ProvideUiScale
import com.haegopa.app.ui.theme.color
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** 노래방 무대처럼 주야간 모드와 상관없이 늘 어두운 가사 패널 색 */
private object Stage {
    val Background = Color(0xF20E1116)
    val Text = Color(0xFFF5F7FA)
    val SubText = Color(0xFFA9B2BF)
    val NextLine = Color(0xFF7D8796)
}

/**
 * [졸음 방지 모드] 지도 위 싱얼롱 패널.
 * 보컬 제거 MR 이 흐르는 동안 현재 가사를 크게, 노래방 자막처럼 색이 차오르게 롤링한다.
 */
@Composable
fun SingAlongModal(
    song: SingAlongSong,
    positionMs: StateFlow<Long>,
    silent: Boolean,
    driving: Boolean,
    onNextSong: () -> Unit,
    onStartQuiz: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position by positionMs.collectAsStateWithLifecycle()
    val lineIndex = song.lineIndexAt(position)
    val lineProgress = song.lineProgressAt(position)
    val countIn = song.countInAt(position)

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Stage.Background,
        border = BorderStroke(2.dp, HaegopaColors.Booster.copy(alpha = 0.75f)),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EqualizerBars()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (silent) "졸음 방지 모드 · 오디오 출력 없음 (가사만 재생)" else "졸음 방지 모드 · 보컬 제거 MR 재생 중",
                        style = MaterialTheme.typography.labelLarge,
                        color = HaegopaColors.Booster,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${song.title} · ${song.credit}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Stage.SubText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!driving) {
                    PillButton(HaegopaIcons.SkipNext, "다음 곡", Stage.Text, onNextSong)
                    Spacer(Modifier.width(6.dp))
                }
                PillButton(HaegopaIcons.Quiz, "1분 퀴즈", HaegopaColors.Booster, onStartQuiz)
                Spacer(Modifier.width(6.dp))
                PillButton(HaegopaIcons.Stop, "끄기", HaegopaColors.Danger, onTurnOff)
            }
            Spacer(Modifier.height(10.dp))

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val available = maxWidth
                if (countIn != null) {
                    Text(
                        text = "♪ 전주 중… $countIn",
                        style = TextStyle(fontFamily = Pretendard, fontSize = lyricFontSize(available, 10), fontWeight = FontWeight.ExtraBold),
                        color = Stage.SubText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        AnimatedContent(
                            targetState = lineIndex,
                            transitionSpec = {
                                (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                            },
                            label = "lyric",
                        ) { index ->
                            val text = song.lines[index].text
                            KaraokeLine(
                                text = text,
                                progress = if (index == lineIndex) lineProgress else 1f,
                                fontSize = lyricFontSize(available, text.length),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val next = song.lines.getOrNull(lineIndex + 1)?.text ?: song.lines.first().text
                        Text(
                            text = next,
                            style = TextStyle(
                                fontFamily = Pretendard,
                                fontSize = (lyricFontSize(available, next.length).value * 0.55f).sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = Stage.NextLine,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 노래방 자막처럼 진행률만큼 노란색이 차오르는 가사 한 줄 */
@Composable
private fun KaraokeLine(text: String, progress: Float, fontSize: TextUnit) {
    val style = TextStyle(fontFamily = Pretendard, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, lineHeight = fontSize * 1.25f)
    Box {
        Text(text = text, style = style, color = Stage.Text, maxLines = 1, softWrap = false)
        Text(
            text = text,
            style = style,
            color = HaegopaColors.Booster,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.drawWithContent {
                clipRect(right = size.width * progress) { this@drawWithContent.drawContent() }
            },
        )
    }
}

private fun lyricFontSize(available: Dp, chars: Int): TextUnit =
    (available.value / (chars.coerceAtLeast(8) * 0.98f)).coerceIn(18f, 46f).sp

@Composable
private fun EqualizerBars() {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "equalizerT",
    )
    Canvas(Modifier.size(width = 34.dp, height = 28.dp)) {
        val barWidth = size.width / 9f
        repeat(5) { i ->
            val phase = (t + i * 0.17f) % 1f
            val fraction = 0.25f + 0.75f * abs(sin(phase * PI.toFloat()))
            val barHeight = size.height * fraction
            drawRoundRect(
                color = HaegopaColors.Booster,
                topLeft = Offset(i * 2 * barWidth, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

@Composable
private fun PillButton(icon: Int, label: String, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            HIcon(icon, tint = color, size = 20.dp)
            Spacer(Modifier.width(5.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/**
 * "졸음을 깨우는 1분 상식 퀴즈!" — TTS 로 문제를 읽고, STT(또는 음성 응답 시뮬레이션 버튼)로 답한다.
 */
@Composable
fun QuizDialog(
    quiz: QuizUiState,
    sttStatus: SdkStatus,
    onAnswerByVoice: () -> Unit,
    onSimulateAnswer: (Int) -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ProvideUiScale {
            QuizContent(quiz, sttStatus, onAnswerByVoice, onSimulateAnswer, onRestart, onClose)
        }
    }
}

@Composable
private fun QuizContent(
    quiz: QuizUiState,
    sttStatus: SdkStatus,
    onAnswerByVoice: () -> Unit,
    onSimulateAnswer: (Int) -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = HaegopaColors.Surface,
        border = BorderStroke(2.dp, HaegopaColors.Booster.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 960.dp),
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIcon(HaegopaIcons.Quiz, tint = HaegopaColors.BoosterInk, size = 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "졸음을 깨우는 1분 상식 퀴즈!",
                    style = MaterialTheme.typography.titleLarge,
                    color = HaegopaColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                HIcon(
                    HaegopaIcons.Timer,
                    tint = if (quiz.remainingSeconds <= 10) HaegopaColors.DangerInk else HaegopaColors.TextSecondary,
                    size = 22.dp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "0:%02d".format(quiz.remainingSeconds.coerceAtLeast(0)),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (quiz.remainingSeconds <= 10) HaegopaColors.DangerInk else HaegopaColors.TextPrimary,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "점수 ${quiz.score}/${quiz.answered}",
                    style = MaterialTheme.typography.labelLarge,
                    color = HaegopaColors.TextSecondary,
                )
                IconButton(onClick = onClose) {
                    HIcon(HaegopaIcons.Close, tint = HaegopaColors.TextSecondary, contentDescription = "퀴즈 닫기")
                }
            }
            Spacer(Modifier.height(12.dp))

            if (quiz.phase == QuizPhase.FINISHED) {
                QuizSummary(quiz = quiz, onRestart = onRestart, onClose = onClose)
                return@Column
            }

            val question = quiz.current
            Text(
                text = "Q${quiz.index + 1}. ${question.question}",
                style = MaterialTheme.typography.headlineSmall,
                color = HaegopaColors.TextPrimary,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                question.choices.forEachIndexed { index, choice ->
                    val answered = quiz.phase == QuizPhase.ANSWERED
                    val highlight = when {
                        answered && index == question.answerIndex -> HaegopaColors.Success
                        answered && index == quiz.selectedIndex -> HaegopaColors.Danger
                        else -> null
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = HaegopaColors.SurfaceHigh,
                        border = BorderStroke(if (highlight == null) 1.dp else 3.dp, highlight ?: HaegopaColors.Outline),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 84.dp),
                    ) {
                        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${index + 1}번", style = MaterialTheme.typography.labelLarge, color = HaegopaColors.BoosterInk)
                            Text(
                                text = choice,
                                style = MaterialTheme.typography.titleMedium,
                                color = HaegopaColors.TextPrimary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onAnswerByVoice,
                    enabled = quiz.phase == QuizPhase.ASKING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HaegopaColors.Booster,
                        contentColor = HaegopaColors.OnAccent,
                    ),
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    HIcon(HaegopaIcons.Mic, tint = HaegopaColors.OnAccent, size = 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (quiz.phase == QuizPhase.LISTENING) "듣는 중…" else "말로 답하기",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "음성인식: ${sttStatus.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = HaegopaColors.TextSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(sttStatus.backend.color()),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "음성 응답 시뮬레이션 (STT 인식 결과를 그대로 주입)",
                style = MaterialTheme.typography.labelMedium,
                color = HaegopaColors.TextTertiary,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(question.choices.size) { index ->
                    OutlinedButton(
                        onClick = { onSimulateAnswer(index) },
                        enabled = quiz.phase != QuizPhase.ANSWERED,
                        border = BorderStroke(1.dp, HaegopaColors.Outline),
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) {
                        Text("“${index + 1}번!”", color = HaegopaColors.TextPrimary)
                    }
                }
            }

            quiz.heard?.let { heard ->
                Spacer(Modifier.height(12.dp))
                Text("들은 말: \"$heard\"", style = MaterialTheme.typography.bodyMedium, color = HaegopaColors.TextSecondary)
            }
            if (quiz.phase == QuizPhase.ANSWERED) {
                val correct = quiz.lastCorrect == true
                val tone = if (correct) HaegopaColors.Success else HaegopaColors.Danger
                val ink = if (correct) HaegopaColors.SuccessInk else HaegopaColors.DangerInk
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = tone.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = if (correct) "딩동댕! 정답이에요" else "아쉬워요! 정답은 ${question.answerIndex + 1}번 ${question.choices[question.answerIndex]}",
                            style = MaterialTheme.typography.titleMedium,
                            color = ink,
                        )
                        Text(question.explanation, style = MaterialTheme.typography.bodyMedium, color = HaegopaColors.TextSecondary)
                        Text("잠시 후 다음 문제로 넘어가요…", style = MaterialTheme.typography.labelMedium, color = HaegopaColors.TextTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizSummary(quiz: QuizUiState, onRestart: () -> Unit, onClose: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("1분 끝!", style = MaterialTheme.typography.headlineMedium, color = HaegopaColors.BoosterInk)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${quiz.answered}문제 중 ${quiz.score}문제 정답",
            style = MaterialTheme.typography.titleLarge,
            color = HaegopaColors.TextPrimary,
        )
        Text("잠이 좀 깨셨나요? 졸리면 가까운 쉼터에서 쉬어 가세요", color = HaegopaColors.TextSecondary)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRestart,
                colors = ButtonDefaults.buttonColors(containerColor = HaegopaColors.Booster, contentColor = HaegopaColors.OnAccent),
            ) {
                HIcon(HaegopaIcons.Refresh, tint = HaegopaColors.OnAccent, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                Text("다시 하기")
            }
            OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, HaegopaColors.Outline)) {
                Text("닫기", color = HaegopaColors.TextPrimary)
            }
        }
    }
}
