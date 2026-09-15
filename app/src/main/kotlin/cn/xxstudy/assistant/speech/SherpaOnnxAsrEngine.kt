package cn.xxstudy.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
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

@SuppressLint("MissingPermission")
class SherpaOnnxAsrEngine(private val context: Context) {

    private val TAG = "SherpaOnnxAsrEngine"
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var isInitialized = false

    private val engineScope = CoroutineScope(Dispatchers.IO + Job())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _listeningRms = MutableStateFlow(0f)
    val listeningRms: StateFlow<Float> = _listeningRms.asStateFlow()

    fun initEngine() {
        if (isInitialized) return

        engineScope.launch(Dispatchers.IO) {
            val modelDir = File(context.getExternalFilesDir(null), "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23")
            Log.d(TAG, "正在扫描 Sherpa-ONNX 流式模型目录...")

            if (!modelDir.exists() || !modelDir.isDirectory) {
                Log.e(TAG, "未找到流式识别模型目录，请确保已下载并推送！")
                return@launch
            }

            try {
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(modelDir, "encoder-epoch-99-avg-1.onnx").absolutePath,
                            decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                            joiner = File(modelDir, "joiner-epoch-99-avg-1.onnx").absolutePath
                        ),
                        tokens = File(modelDir, "tokens.txt").absolutePath,
                        numThreads = 1,
                        debug = false
                    ),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 0.0f, 0.0f),
                        rule2 = EndpointRule(false, 0.0f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 0.0f)
                    ),
                    enableEndpoint = false,
                    decodingMethod = "greedy_search",
                    maxActivePaths = 4
                )

                recognizer = OnlineRecognizer(assetManager = null, config = config)
                isInitialized = true
                Log.w(TAG, "✅ Sherpa-ONNX 流式识别引擎加载成功！准备就绪。")
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa-ONNX 引擎初始化异常: ${e.message}", e)
            }
        }
    }

    suspend fun startListening(
        autoStop: Boolean = false,
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "Sherpa-ONNX 尚未初始化成功")
            onFinal("")
            return@withContext
        }

        if (_isListening.value) {
            Log.w(TAG, "已经在录音中了...")
            return@withContext
        }
        _isListening.value = true
        _listeningRms.value = 0f

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        var audioRecord: AudioRecord? = null
        stream = recognizer?.createStream()
        var extractedFinalText: String? = null

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize.coerceAtLeast(sampleRate * 2)
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                stopListening()
                onFinal("")
                return@withContext
            }

            audioRecord.startRecording()
            Log.i(TAG, "... 启动流式麦克风，录音中 (VAD=${autoStop}) ...")

            val buffer = ShortArray(1600) // 100ms
            val floatBuffer = FloatArray(1600)
            
            var hasSpoken = false
            var silenceStartMs = 0L
            var dynamicVadThreshold = 42f
            var frameCount = 0
            var ambientDbSum = 0f
            val VAD_TIMEOUT_MS = 1500L
            val MAX_RECORD_MS = 20000L
            val recordingStartTime = System.currentTimeMillis()
            var lastResult = ""

            while (isActive && _isListening.value) {
                val readCount = audioRecord.read(buffer, 0, buffer.size)
                if (readCount > 0) {
                    var sum = 0.0
                    for (i in 0 until readCount) {
                        floatBuffer[i] = buffer[i] / 32768.0f
                        sum += (buffer[i] * buffer[i]).toDouble()
                    }
                    
                    val st = stream
                    if (st != null) {
                        st.acceptWaveform(floatBuffer.sliceArray(0 until readCount), sampleRate)
                        while (recognizer?.isReady(st) == true) {
                            recognizer?.decode(st)
                        }
                        val text = recognizer?.getResult(st)?.text ?: ""
                        if (text.isNotBlank() && text != lastResult) {
                            lastResult = text
                            withContext(Dispatchers.Main) { onPartial(text) }
                        }
                    }

                    val rms = sqrt(sum / readCount)
                    val db = if (rms > 0) (20 * log10(rms)).toFloat() else 0f
                    
                    if (frameCount < 10) {
                        frameCount++
                        if (frameCount > 5) {
                            ambientDbSum += db
                        }
                        if (frameCount == 10) {
                            dynamicVadThreshold = ((ambientDbSum / 5f) + 12f).coerceIn(40f, 65f)
                            Log.i(TAG, "动态 VAD 阈值已计算: $dynamicVadThreshold dB")
                        }
                    }
                    _listeningRms.value = db.coerceIn(0f, 100f)

                    if (autoStop) {
                        val now = System.currentTimeMillis()
                        if (now - recordingStartTime > MAX_RECORD_MS) {
                            Log.i(TAG, "达到最大录音时长，自动停止")
                            stopListening()
                            break
                        }
                        if (db > dynamicVadThreshold) {
                            hasSpoken = true
                            silenceStartMs = 0L
                        } else if (hasSpoken) {
                            if (silenceStartMs == 0L) silenceStartMs = now
                            else if (now - silenceStartMs > VAD_TIMEOUT_MS) {
                                Log.i(TAG, "检测到语音结束，自动停止流式录音")
                                stopListening()
                                break
                            }
                        }
                    }
                }
            }

            _isListening.value = false
            _listeningRms.value = 0f

            val st = stream
            if (st != null) {
                st.inputFinished()
                while (recognizer?.isReady(st) == true) {
                    recognizer?.decode(st)
                }
                extractedFinalText = recognizer?.getResult(st)?.text ?: ""
                Log.i(TAG, "流式识别结束，最终结果='${extractedFinalText}'")
                st.release()
            }
            stream = null
            
        } catch (e: Exception) {
            Log.e(TAG, "录音异常: ${e.message}", e)
        } finally {
            _isListening.value = false
            _listeningRms.value = 0f
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放 AudioRecord 失败: ${e.message}")
            }
        }

        // 确保麦克风已经被完全释放后，再回调 onFinal（此回调会触发 KWS 重启）
        withContext(Dispatchers.Main) { 
            onFinal(extractedFinalText ?: "") 
        }
    }

    fun stopListening() {
        _isListening.value = false
    }

    fun destroy() {
        recognizer?.release()
        recognizer = null
        isInitialized = false
    }
}
