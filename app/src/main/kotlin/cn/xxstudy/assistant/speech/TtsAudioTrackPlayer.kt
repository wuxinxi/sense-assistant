package cn.xxstudy.assistant.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TtsAudioTrackPlayer]
 * 针对离线 TTS 语音合成（Mono Float PCM）定制的极低延迟流式播放器。
 * 支持单声道 Float 数组直接入队、无锁队列缓冲与毫秒级即时打断（Barge-in）。
 */
class TtsAudioTrackPlayer(
    private var sampleRate: Int = 22050
) : AutoCloseable {

    companion object {
        private const val TAG = "TtsAudioPlayer"
    }

    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioQueue = Channel<FloatArray>(Channel.UNLIMITED)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val isInterrupted = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var currentPitch: Float = 1.0f

    init {
        initAudioTrack(sampleRate)
        startPlaybackLoop()
    }

    @Synchronized
    fun updatePitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        if (kotlin.math.abs(clamped - currentPitch) < 0.005f) {
            return
        }
        currentPitch = clamped
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    val params = track.playbackParams
                    params.pitch = clamped
                    track.playbackParams = params
                    Log.d(TAG, "AudioTrack pitch updated to $clamped")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update AudioTrack pitch: ${e.message}")
        }
    }

    @Synchronized
    fun updateSampleRateIfNeeded(newSampleRate: Int) {
        if (newSampleRate > 0 && newSampleRate != sampleRate) {
            Log.i(TAG, "Re-initializing AudioTrack for new sample rate: $newSampleRate (old: $sampleRate)")
            sampleRate = newSampleRate
            initAudioTrack(newSampleRate)
        }
    }

    @Synchronized
    private fun initAudioTrack(rate: Int) {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }

            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val minBufferSize = AudioTrack.getMinBufferSize(
                rate,
                channelConfig,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufferSize = maxOf(minBufferSize * 2, rate * 4) // ~1s buffer

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            try {
                val params = audioTrack?.playbackParams ?: PlaybackParams()
                params.pitch = currentPitch
                audioTrack?.playbackParams = params
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply initial pitch to AudioTrack: ${e.message}")
            }

            audioTrack?.play()
            Log.i(TAG, "AudioTrack initialized: sampleRate=$rate, bufferSize=$bufferSize, pitch=$currentPitch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    private fun startPlaybackLoop() {
        playbackJob = playerScope.launch {
            for (chunk in audioQueue) {
                if (isInterrupted.get()) {
                    continue
                }
                if (chunk.isEmpty()) continue

                val track = audioTrack ?: continue
                _isPlaying.value = true

                var written = 0
                val total = chunk.size

                while (written < total && !isInterrupted.get() && isActive) {
                    val count = track.write(
                        chunk,
                        written,
                        total - written,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (count > 0) {
                        written += count
                    } else {
                        Log.w(TAG, "AudioTrack write returned $count")
                        break
                    }
                }

                if (audioQueue.isEmpty) {
                    _isPlaying.value = false
                }
            }
        }
    }

    /**
     * 投递单声道 PCM Float 数组到播放队列
     */
    fun enqueueAudio(samples: FloatArray, sr: Int = sampleRate) {
        if (samples.isEmpty()) return
        updateSampleRateIfNeeded(sr)
        isInterrupted.set(false)
        audioQueue.trySend(samples)
    }

    /**
     * 毫秒级即时打断：清空未播放音频包，并冲刷 AudioTrack 内部硬件缓冲
     */
    fun interrupt() {
        isInterrupted.set(true)
        while (audioQueue.tryReceive().isSuccess) {
            // 清空队列
        }

        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack: ${e.message}", e)
        }

        _isPlaying.value = false
        Log.i(TAG, "Playback interrupted and AudioTrack flushed")
    }

    override fun close() {
        interrupt()
        playbackJob?.cancel()
        playerScope.cancel()
        audioQueue.close()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack: ${e.message}", e)
        }
        audioTrack = null
    }
}
