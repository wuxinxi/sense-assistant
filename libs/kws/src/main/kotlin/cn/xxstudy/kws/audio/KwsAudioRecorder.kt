package cn.xxstudy.kws.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [KwsAudioRecorder]
 * 极致轻量的音频采集器：
 * 1. 专为 2GB 内存与低功耗常驻设计：内存零 GC 抖动（固定复用 100ms 采样缓冲数组）；
 * 2. 独占硬件互斥管理：当命中唤醒词时，原子化释放 AudioRecord 底层通道，平滑移交给 SenseVoice ASR。
 */
class KwsAudioRecorder(
    private val sampleRate: Int = 16000,
    private val onAudioChunk: (samples: FloatArray) -> Unit
) {

    companion object {
        private const val TAG = "KwsAudioRecorder"
        private const val CHUNK_SIZE = 1600 // 100ms (16000 * 0.1)
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val lock = Any()

    // 零 GC 复用缓冲区
    private val shortBuffer = ShortArray(CHUNK_SIZE)
    private val floatBuffer = FloatArray(CHUNK_SIZE)

    /**
     * 启动音频录制监听
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        synchronized(lock) {
            if (isRecording.get()) return true

            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, CHUNK_SIZE * 4)

            try {
                // 优先使用 VOICE_RECOGNITION 声源，硬件回声消除与自动增益
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "VOICE_RECOGNITION 初始化未就绪，尝试降级到 MIC...")
                    audioRecord?.release()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "❌ AudioRecord 初始化彻底失败！可能麦克风被占用或缺少录音权限。")
                    audioRecord?.release()
                    audioRecord = null
                    return false
                }

                audioRecord?.startRecording()
                isRecording.set(true)

                recordThread = Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    runRecordLoop()
                }, "KwsAudioRecordThread").apply { start() }

                Log.d(TAG, "🎙️ [KWS] 麦克风录音流启动成功 (采样率: $sampleRate)")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "启动 AudioRecord 异常: ${e.message}", e)
                stop()
                return false
            }
        }
    }

    private fun runRecordLoop() {
        while (isRecording.get()) {
            val record = audioRecord ?: break
            val readCount = record.read(shortBuffer, 0, CHUNK_SIZE)
            if (readCount > 0) {
                // 原地归一化到 floatBuffer [-1.0, 1.0]，避免任何对象分配引发 GC
                for (i in 0 until readCount) {
                    floatBuffer[i] = shortBuffer[i] / 32768.0f
                }
                // 正常情况 readCount == CHUNK_SIZE，直接传引用（零 GC）；
                // 仅在录音启停边界 readCount < CHUNK_SIZE 时才分配新数组
                val chunk = if (readCount == CHUNK_SIZE) floatBuffer else floatBuffer.copyOf(readCount)
                onAudioChunk(chunk)
            } else if (readCount < 0) {
                Log.w(TAG, "AudioRecord 读取异常代码: $readCount")
                break
            }
        }
    }

    /**
     * 停止并彻底释放麦克风资源（供 ASR 无缝接管）
     */
    fun stop() {
        synchronized(lock) {
            isRecording.set(false)
            try {
                // 等待录音线程自然退出（最多 200ms，即 2 个 chunk 周期）
                // 注意：如果是在录音线程内部调用 stop，则不能 join 否则会阻塞自己
                if (Thread.currentThread() != recordThread) {
                    recordThread?.join(200)
                }
                recordThread = null

                audioRecord?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                    it.release()
                }
                audioRecord = null
                Log.d(TAG, "⏹️ [KWS] 麦克风已彻底释放并退避，可安全移交给 ASR")
            } catch (e: Exception) {
                Log.e(TAG, "释放 AudioRecord 异常: ${e.message}", e)
            }
        }
    }

    val isCapturing: Boolean
        get() = isRecording.get()
}
