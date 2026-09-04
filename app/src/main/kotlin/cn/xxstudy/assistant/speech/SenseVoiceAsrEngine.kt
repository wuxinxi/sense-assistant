package cn.xxstudy.assistant.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * [SenseVoiceAsrEngine]
 * 基于 sherpa-onnx 底层的 SenseVoice Small INT8 纯端侧离线语音识别引擎。
 * 彻底脱离系统自带 SpeechRecognizer，提供高性能、断网可用的端侧麦克风语音转文字能力。
 */
class SenseVoiceAsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "SenseVoiceAsrEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    // ASR 引擎状态
    private var recognizer: OfflineRecognizer? = null
    private var isEngineReady = false

    // 录音状态
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // 实时录音音量分贝 (供 UI 动效展示)
    private val _listeningRms = MutableStateFlow(0f)
    val listeningRms: StateFlow<Float> = _listeningRms.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val recordedSamples = ArrayList<Float>()

    private var onPartialCallback: ((String) -> Unit)? = null
    private var onFinalCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    init {
        initEngine()
    }

    /**
     * 检索模型文件存放路径并初始化 Sherpa-ONNX 离线识别器
     */
    fun initEngine(): Boolean {
        if (isEngineReady && recognizer != null) return true

        val extFiles = context.getExternalFilesDir(null)
        val intFiles = context.filesDir
        val pkg = context.packageName

        val candidates = mutableListOf<File>()
        if (extFiles != null) {
            candidates.add(extFiles.resolve("models/sense-voice-int8"))
            candidates.add(extFiles.resolve("sense-voice-int8"))
            candidates.add(extFiles)
        }
        candidates.add(intFiles.resolve("models/sense-voice-int8"))
        candidates.add(intFiles.resolve("sense-voice-int8"))
        candidates.add(intFiles)
        candidates.add(File("/sdcard/Android/data/$pkg/files/models/sense-voice-int8"))
        candidates.add(File("/sdcard/Android/data/$pkg/files/sense-voice-int8"))
        candidates.add(File("/storage/emulated/0/Android/data/$pkg/files/models/sense-voice-int8"))
        candidates.add(File("/storage/emulated/0/Android/data/$pkg/files/sense-voice-int8"))

        Log.d(TAG, "正在扫描 SenseVoice 离线模型目录...")
        var foundDir: File? = null
        for (dir in candidates.distinct()) {
            val modelFile = File(dir, "model.int8.onnx")
            val tokensFile = File(dir, "tokens.txt")
            val dirExists = dir.exists()
            val modelExists = modelFile.exists() && modelFile.canRead()
            val tokensExists = tokensFile.exists() && tokensFile.canRead()

            if (modelExists && tokensExists) {
                foundDir = dir
                Log.w(TAG, "🎯 成功定位到 SenseVoice 离线模型目录: ${dir.absolutePath}")
                break
            } else if (dirExists) {
                Log.d(TAG, "探测目录存在但缺少模型/不可读: ${dir.absolutePath} (model=$modelExists, tokens=$tokensExists)")
            }
        }

        if (foundDir == null) {
            Log.w(TAG, "⚠️ SenseVoice 模型未就绪。请运行: bash Script/push_models_to_phone.sh 推送模型文件。")
            isEngineReady = false
            return false
        }

        val modelPath = File(foundDir, "model.int8.onnx").absolutePath
        val tokensPath = File(foundDir, "tokens.txt").absolutePath

        try {
            val senseVoiceConfig = OfflineSenseVoiceModelConfig().apply {
                model = modelPath
                language = "auto"
                useInverseTextNormalization = true
            }

            val modelConfig = OfflineModelConfig().apply {
                senseVoice = senseVoiceConfig
                tokens = tokensPath
                numThreads = 2 // 移动端配置 2 线程兼顾推理速度与能耗
                debug = false
                provider = "cpu"
            }

            val recognizerConfig = OfflineRecognizerConfig().apply {
                featConfig.sampleRate = SAMPLE_RATE
                featConfig.featureDim = 80
                this.modelConfig = modelConfig
            }

            recognizer = OfflineRecognizer(assetManager = null, config = recognizerConfig)
            isEngineReady = true
            Log.w(TAG, "✅ SenseVoice 离线识别引擎加载成功！准备就绪。")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "❌ 初始化 SenseVoice 识别引擎失败", e)
            isEngineReady = false
            return false
        }
    }

    /**
     * 开始录音监听
     */
    private var recordingStartTime = 0L

    fun startListening(
        language: String = "zh-CN",
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        onPartialCallback = onPartial
        onFinalCallback = onFinal
        onErrorCallback = onError
        recordingStartTime = System.currentTimeMillis()

        if (!isEngineReady || recognizer == null) {
            val ok = initEngine()
            if (!ok || recognizer == null) {
                onError("SenseVoice 模型未就绪！\n请运行: bash Script/push_models_to_phone.sh 推送模型并授权。")
                return
            }
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("缺少麦克风录音权限，请在系统设置中授权。")
            return
        }

        if (_isListening.value) {
            stopListening()
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize <= 0) {
            onError("获取音频输入缓冲区失败")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("麦克风设备初始化失败，请检查是否被其他应用占用")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            _isListening.value = true
            recordedSamples.clear()

            recordJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(1600) // 100ms 音频帧
                while (isActive && _isListening.value) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        var sum = 0.0
                        for (i in 0 until readCount) {
                            val sample = buffer[i] / 32768.0f
                            recordedSamples.add(sample)
                            sum += (buffer[i] * buffer[i]).toDouble()
                        }
                        val rms = sqrt(sum / readCount)
                        val db = if (rms > 0) (20 * log10(rms)).toFloat() else 0f
                        _listeningRms.value = db.coerceIn(0f, 100f)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            _isListening.value = false
            _listeningRms.value = 0f
            onError("启动录音失败: ${e.message}")
        }
    }

    /**
     * 取消本次录音（上滑取消），丢弃已录音频，不进行解码推理
     */
    fun cancelListening() {
        if (!_isListening.value) return

        _isListening.value = false
        _listeningRms.value = 0f

        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "取消录音时释放 AudioRecord 异常: ${e.message}")
        } finally {
            audioRecord = null
        }

        recordedSamples.clear()
        onFinalCallback = null
        onErrorCallback = null
        Log.d(TAG, "本次语音录制已主动取消")
    }

    /**
     * 停止录音并触发离线解码识别
     */
    fun stopListening() {
        if (!_isListening.value) return

        _isListening.value = false
        _listeningRms.value = 0f

        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "停止 AudioRecord 发生异常: ${e.message}")
        } finally {
            audioRecord = null
        }

        val onFinal = onFinalCallback
        val onError = onErrorCallback
        onFinalCallback = null
        onErrorCallback = null

        val samples = recordedSamples.toFloatArray()
        recordedSamples.clear()

        val durationMs = System.currentTimeMillis() - recordingStartTime
        // 音频过短判断 (< 0.4 秒忽略，提示时间太短)
        if (durationMs < 400 || samples.size < SAMPLE_RATE * 0.4) {
            onError?.invoke("说话时间太短")
            return
        }

        // 在后台线程执行 SenseVoice 离线解码
        scope.launch(Dispatchers.Default) {
            val rec = recognizer
            if (rec == null) {
                withContext(Dispatchers.Main) {
                    onError?.invoke("识别引擎异常未就绪")
                }
                return@launch
            }

            try {
                val stream = rec.createStream()
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val result = rec.getResult(stream)
                stream.release()

                val cleanText = cleanSenseVoiceOutput(result.text)
                Log.i(TAG, "SenseVoice 识别结果: 原文='${result.text}', 清洗后='$cleanText'")

                withContext(Dispatchers.Main) {
                    if (cleanText.isNotBlank()) {
                        onFinal?.invoke(cleanText)
                    } else {
                        onError?.invoke("未识别到有效语音内容")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "SenseVoice 解码异常", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke("语音解码错误: ${e.message}")
                }
            }
        }
    }

    /**
     * 去除 SenseVoice 情绪、语言、事件标签，获取纯净文本
     */
    private fun cleanSenseVoiceOutput(raw: String): String {
        return raw.replace(Regex("<\\|.*?\\|>"), "").trim()
    }

    /**
     * 释放引擎资源
     */
    fun destroy() {
        stopListening()
        recognizer?.release()
        recognizer = null
        isEngineReady = false
    }
}
