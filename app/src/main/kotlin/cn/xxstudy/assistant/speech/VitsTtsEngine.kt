package cn.xxstudy.assistant.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * [VitsTtsEngine]
 * 基于 sherpa-onnx 底层的 VITS AISHELL-3 INT8 (38MB) 极速端侧离线语音合成引擎。
 * 纯 C++ 底层驱动，毫秒级合成（30~50ms），字字清晰，彻底杜绝自回归幻觉与鬼话。
 */
class VitsTtsEngine(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "VitsTtsEngine"
    }

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val currentGeneration = AtomicLong(0)
    private var tts: OfflineTts? = null
    private var sampleRate: Int = 22050

    val currentSampleRate: Int
        get() = sampleRate

    init {
        engineScope.launch {
            initEngine(cn.xxstudy.assistant.data.AppSettings.ttsModelId.value)
        }
    }

    private val _isBilingual = MutableStateFlow(false)
    val isBilingual: StateFlow<Boolean> = _isBilingual.asStateFlow()

    private val _numSpeakers = MutableStateFlow(1)
    val numSpeakers: StateFlow<Int> = _numSpeakers.asStateFlow()

    suspend fun switchModel(modelId: String) {
        cancelCurrent()
        tts?.release()
        tts = null
        _isReady.value = false
        initEngine(modelId)
    }

    suspend fun initEngine(modelId: String = "vits-melo-tts-zh_en"): Boolean = withContext(Dispatchers.IO) {
        if (_isReady.value && tts != null) return@withContext true

        val extFiles = context.getExternalFilesDir(null)
        val intFiles = context.filesDir
        val pkg = context.packageName

        val modelSubdirs = listOf(
            "models/$modelId",
            modelId
        )

        val candidates = mutableListOf<File>()
        if (extFiles != null) {
            modelSubdirs.forEach { candidates.add(extFiles.resolve(it)) }
            candidates.add(extFiles)
        }
        modelSubdirs.forEach { candidates.add(intFiles.resolve(it)) }
        candidates.add(intFiles)
        modelSubdirs.forEach {
            candidates.add(File("/sdcard/Android/data/$pkg/files/$it"))
            candidates.add(File("/storage/emulated/0/Android/data/$pkg/files/$it"))
        }

        var foundDir: File? = null
        for (dir in candidates.distinct()) {
            if (dir.exists() && dir.isDirectory && File(dir, "tokens.txt").exists()) {
                foundDir = dir
                Log.i(TAG, "🎯 定位到 TTS 离线模型目录: ${dir.absolutePath}")
                break
            }
        }

        if (foundDir == null) {
            Log.w(TAG, "⚠️ TTS 模型未找到: $modelId，请先运行 push_models_to_phone.sh 推送")
            _isReady.value = false
            return@withContext false
        }

        try {
            val tStart = System.currentTimeMillis()
            val isMelo = modelId.contains("melo", ignoreCase = true)
            _isBilingual.value = isMelo

            var modelConfig = OfflineTtsModelConfig(numThreads = 2, debug = false, provider = "cpu")

            if (modelId.contains("matcha", ignoreCase = true)) {
                val acousticFile = File(foundDir, "model-steps-3.onnx").takeIf { it.exists() }
                    ?: File(foundDir, "model.onnx")
                val vocoderFile = File(foundDir, "hifigan_v2.onnx").takeIf { it.exists() }
                    ?: File(foundDir, "vocos.onnx")
                
                modelConfig = modelConfig.copy(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = acousticFile.absolutePath,
                        vocoder = vocoderFile.absolutePath,
                        lexicon = File(foundDir, "lexicon.txt").absolutePath,
                        tokens = File(foundDir, "tokens.txt").absolutePath,
                        dictDir = File(foundDir, "dict").absolutePath,
                        noiseScale = 1.0f,
                        lengthScale = 1.0f,
                        dataDir = ""
                    )
                )
            } else if (modelId.contains("kokoro", ignoreCase = true)) {
                val kokoroLexiconUs = File(foundDir, "lexicon-us-en.txt")
                val kokoroLexiconZh = File(foundDir, "lexicon-zh.txt")
                val kokoroLexicons = mutableListOf<String>()
                if (kokoroLexiconUs.exists()) kokoroLexicons.add(kokoroLexiconUs.absolutePath)
                if (kokoroLexiconZh.exists()) kokoroLexicons.add(kokoroLexiconZh.absolutePath)
                val kokoroLexiconStr = if (kokoroLexicons.isNotEmpty()) {
                    kokoroLexicons.joinToString(",")
                } else {
                    File(foundDir, "lexicon.txt").absolutePath
                }

                modelConfig = modelConfig.copy(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(foundDir, "kokoro-multi-lang-v1_1.onnx").takeIf { it.exists() }?.absolutePath
                            ?: File(foundDir, "model.onnx").absolutePath,
                        voices = File(foundDir, "voices.bin").absolutePath,
                        tokens = File(foundDir, "tokens.txt").absolutePath,
                        dataDir = File(foundDir, "espeak-ng-data").absolutePath,
                        dictDir = "",
                        lexicon = kokoroLexiconStr,
                        lengthScale = 1.0f
                    )
                )
            } else {
                // VITS
                val modelFile = File(foundDir, "model.int8.onnx").takeIf { it.exists() }
                    ?: File(foundDir, "model.onnx")
                val dictDir = File(foundDir, "dict")
                val dictDirPath = if (dictDir.exists() && dictDir.isDirectory) dictDir.absolutePath else ""

                modelConfig = modelConfig.copy(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        lexicon = File(foundDir, "lexicon.txt").absolutePath,
                        tokens = File(foundDir, "tokens.txt").absolutePath,
                        dataDir = "",
                        dictDir = dictDirPath,
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    )
                )
            }

            val ruleFstNames = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")
            val ruleFsts = ruleFstNames.map { File(foundDir, it) }
                .filter { it.exists() }
                .joinToString(",") { it.absolutePath }

            val ttsConfig = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = ruleFsts,
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f
            )

            val offlineTts = OfflineTts(assetManager = null, config = ttsConfig)
            tts = offlineTts
            sampleRate = offlineTts.sampleRate()
            val totalSpeakers = offlineTts.numSpeakers()
            _numSpeakers.value = totalSpeakers
            _isReady.value = true

            val elapsed = System.currentTimeMillis() - tStart
            Log.i(TAG, "✅ TTS 引擎初始化成功: modelId=$modelId, isMelo=$isMelo, sampleRate=$sampleRate, speakers=$totalSpeakers, 耗时=${elapsed}ms")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 初始化 VITS TTS 失败: ${e.message}", e)
            _isReady.value = false
            false
        }
    }

    fun cancelCurrent() {
        currentGeneration.incrementAndGet()
        _isGenerating.value = false
    }

    suspend fun synthesize(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        onAudioDecoded: (FloatArray, Int) -> Unit
    ) = withContext(Dispatchers.Default) {
        val engine = tts
        if (!_isReady.value || engine == null) {
            Log.w(TAG, "VITS TTS not ready")
            return@withContext
        }

        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withContext

        // 分配本轮合成的唯一代际 ID
        val thisGen = currentGeneration.incrementAndGet()
        _isGenerating.value = true
        val tStart = System.currentTimeMillis()
        Log.d(TAG, "🎙️ 开始合成 [gen=$thisGen]: \"$cleanText\"")

        try {
            val numSpk = engine.numSpeakers()
            val effectiveSid = if (numSpk <= 1) 0 else sid.coerceIn(0, numSpk - 1)
            val audio = engine.generate(cleanText, effectiveSid, speed)
            val elapsed = System.currentTimeMillis() - tStart
            val samples = audio.samples
            val sr = audio.sampleRate

            // 严格校验：若期间发生了取消或被更新的合成取代，立即静默丢弃
            if (currentGeneration.get() != thisGen) {
                Log.d(TAG, "⏭️ 合成被作废丢弃: \"$cleanText\" (gen=$thisGen, latest=${currentGeneration.get()})")
                return@withContext
            }

            if (samples.isNotEmpty()) {
                val durationSec = samples.size.toFloat() / sr
                val rtf = (elapsed / 1000f) / durationSec
                Log.i(TAG, "⚡ 合成成功: \"$cleanText\" | 耗时: ${elapsed}ms | 采样率: ${sr}Hz | 时长: ${String.format("%.2f", durationSec)}s | RTF: ${String.format("%.3f", rtf)}")
                onAudioDecoded(samples, sr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "合成异常: ${e.message}", e)
        } finally {
            if (currentGeneration.get() == thisGen) {
                _isGenerating.value = false
            }
        }
    }

    override fun close() {
        cancelCurrent()
        tts?.release()
        tts = null
        _isReady.value = false
        engineScope.cancel()
    }
}
