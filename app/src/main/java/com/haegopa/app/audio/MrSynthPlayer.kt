package com.haegopa.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.haegopa.app.domain.model.SingAlongSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [졸음 방지 모드] 반주 플레이어.
 * - AudioTrack(MODE_STREAM)으로 [MrSynth] 가 만든 PCM 을 재생 (보컬 제거 MR 스트리밍 모드를 흉내)
 * - 차량 오디오 정책(AAOS CarAudioService)에 맞춰 USAGE_MEDIA 오디오 포커스를 요청
 * - [positionMs] 로 가사 싱크를 맞춘다 (playbackHeadPosition 기준)
 */
class MrSynthPlayer(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 오디오 출력을 열 수 없을 때(출력 장치 없음 등) 가사만 시계 기준으로 흘려보내는 무음 모드 */
    private val _silentMode = MutableStateFlow(false)
    val silentMode: StateFlow<Boolean> = _silentMode.asStateFlow()

    private var playJob: Job? = null
    private var positionJob: Job? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var userDucked = false

    @Volatile
    private var focusDucked = false

    private var focusRequest: AudioFocusRequest? = null

    @Synchronized
    fun start(song: SingAlongSong) {
        stop()
        val track = createTrack()
        if (track == null) {
            startSilentClock()
            return
        }
        requestFocus()
        activeTrack = track
        applyVolume()
        val played = runCatching { track.play() }
            .onFailure { Log.w(TAG, "AudioTrack.play failed", it) }
            .isSuccess
        if (!played) {
            track.release()
            activeTrack = null
            abandonFocus()
            startSilentClock()
            return
        }
        _positionMs.value = 0L
        _isPlaying.value = true

        val job = scope.launch {
            try {
                val synth = MrSynth(song, SAMPLE_RATE)
                var beat = 0L
                while (isActive) {
                    val pcm = synth.renderBeat(beat++)
                    if (!writeFully(track, pcm)) break
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
        playJob = job
        positionJob = scope.launch {
            while (isActive && job.isActive) {
                val frames = runCatching { track.playbackHeadPosition }.getOrDefault(0)
                _positionMs.value = frames.toLong() * 1000L / SAMPLE_RATE
                delay(POSITION_TICK_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        positionJob?.cancel()
        positionJob = null
        val track = activeTrack
        activeTrack = null
        playJob?.cancel()
        playJob = null
        // stop() 은 블로킹 중인 write() 를 깨운다. release 는 재생 코루틴의 finally 에서 수행.
        track?.let { runCatching { it.pause(); it.flush(); it.stop() } }
        _isPlaying.value = false
        _silentMode.value = false
        _positionMs.value = 0L
        abandonFocus()
    }

    private fun startSilentClock() {
        Log.w(TAG, "Audio output unavailable → lyrics-only silent mode")
        _silentMode.value = true
        _isPlaying.value = true
        _positionMs.value = 0L
        val startedAt = SystemClock.elapsedRealtime()
        positionJob = scope.launch {
            while (isActive) {
                _positionMs.value = SystemClock.elapsedRealtime() - startedAt
                delay(POSITION_TICK_MS)
            }
        }
    }

    /** 퀴즈·안내 음성이 나올 때 반주 볼륨을 낮춘다 */
    fun setDucked(ducked: Boolean) {
        userDucked = ducked
        applyVolume()
    }

    private fun applyVolume() {
        val volume = when {
            focusDucked -> 0.15f
            userDucked -> 0.25f
            else -> 0.9f
        }
        activeTrack?.let { runCatching { it.setVolume(volume) } }
    }

    private fun writeFully(track: AudioTrack, pcm: ShortArray): Boolean {
        var offset = 0
        while (offset < pcm.size) {
            val written = track.write(pcm, offset, pcm.size - offset)
            if (written <= 0) return false
            offset += written
        }
        return true
    }

    private fun createTrack(): AudioTrack? = try {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 버퍼는 반드시 프레임(16bit mono = 2바이트) 단위여야 한다. 약 250ms 분량.
        val target = (SAMPLE_RATE / 4) * BYTES_PER_FRAME
        val bufferSize = if (minBuffer > 0) maxOf(minBuffer, target) else target
        AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (t: Throwable) {
        Log.w(TAG, "AudioTrack unavailable", t)
        null
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener { change ->
                focusDucked = change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                applyVolume()
            }
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        focusDucked = false
    }

    private companion object {
        const val TAG = "MrSynthPlayer"
        const val SAMPLE_RATE = 22_050
        const val BYTES_PER_FRAME = 2
        const val POSITION_TICK_MS = 40L
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
