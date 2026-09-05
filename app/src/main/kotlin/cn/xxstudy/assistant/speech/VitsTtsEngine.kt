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
            initEngine()
        }
    }

    private val _isBilingual = MutableStateFlow(false)
    val isBilingual: StateFlow<Boolean> = _isBilingual.asStateFlow()

    private val _numSpeakers = MutableStateFlow(1)
    val numSpeakers: StateFlow<Int> = _numSpeakers.asStateFlow()

    suspend fun initEngine(): Boolean = withContext(Dispatchers.IO) {
        if (_isReady.value && tts != null) return@withContext true

        val extFiles = context.getExternalFilesDir(null)
        val intFiles = context.filesDir
        val pkg = context.packageName

        val modelSubdirs = listOf(
            "models/vits-melo-tts-zh_en",
            "vits-melo-tts-zh_en",
            "models/vits-zh-aishell3",
            "vits-zh-aishell3"
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
            val modelFile = when {
                File(dir, "model.onnx").exists() -> File(dir, "model.onnx")
                File(dir, "model.int8.onnx").exists() -> File(dir, "model.int8.onnx")
                File(dir, "vits-aishell3.int8.onnx").exists() -> File(dir, "vits-aishell3.int8.onnx")
                File(dir, "vits-aishell3.onnx").exists() -> File(dir, "vits-aishell3.onnx")
                else -> null
            }
            val lexiconFile = File(dir, "lexicon.txt")
            val tokensFile = File(dir, "tokens.txt")

            if (modelFile != null && modelFile.canRead() && lexiconFile.exists() && tokensFile.exists()) {
                foundDir = dir
                Log.i(TAG, "🎯 定位到 VITS 离线模型: ${modelFile.absolutePath}")
                break
            }
        }

        if (foundDir == null) {
            Log.w(TAG, "⚠️ VITS 模型未找到，请先运行 push_models_to_phone.sh 推送")
            _isReady.value = false
            return@withContext false
        }

        try {
            val tStart = System.currentTimeMillis()
            val isMelo = foundDir.name.contains("melo", ignoreCase = true) || foundDir.parentFile?.name?.contains("melo", ignoreCase = true) == true
            _isBilingual.value = isMelo

            val modelFile = when {
                File(foundDir, "model.onnx").exists() -> File(foundDir, "model.onnx")
                File(foundDir, "model.int8.onnx").exists() -> File(foundDir, "model.int8.onnx")
                File(foundDir, "vits-aishell3.int8.onnx").exists() -> File(foundDir, "vits-aishell3.int8.onnx")
                File(foundDir, "vits-aishell3.onnx").exists() -> File(foundDir, "vits-aishell3.onnx")
                else -> File(foundDir, "model.onnx")
            }
            val lexiconFile = File(foundDir, "lexicon.txt")
            val tokensFile = File(foundDir, "tokens.txt")
            val dictDir = File(foundDir, "dict")
            val dictDirPath = if (dictDir.exists() && dictDir.isDirectory) dictDir.absolutePath else ""

            val ruleFstNames = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")
            val ruleFsts = ruleFstNames.map { File(foundDir, it) }
                .filter { it.exists() }
                .joinToString(",") { it.absolutePath }

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                lexicon = lexiconFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = "",
                dictDir = dictDirPath,
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )

            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )

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
            Log.i(TAG, "✅ VITS TTS 引擎初始化成功: isMelo=$isMelo, model=${modelFile.name}, sampleRate=$sampleRate, speakers=$totalSpeakers, dictDir=$dictDirPath, 耗时=${elapsed}ms")
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
