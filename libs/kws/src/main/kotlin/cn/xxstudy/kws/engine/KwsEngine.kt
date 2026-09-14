package cn.xxstudy.kws.engine

import android.content.Context
import android.util.Log
import cn.xxstudy.kws.KwsConfig
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * [KwsEngine]
 * 基于 Sherpa-ONNX 底层的 Zipformer/CTC 极致轻量唤醒模型驱动器。
 * 内存占用约 15MB ~ 25MB，单核 CPU < 2%，专为 2GB 内存低配设备设计。
 */
class KwsEngine(
    private val context: Context,
    private val config: KwsConfig = KwsConfig()
) {

    companion object {
        private const val TAG = "KwsEngine"

        /**
         * 内置预设唤醒词与 Sherpa-ONNX 拼音声母/带调韵母音素映射表
         * （基于 WenetSpeech Zipformer 3.3M 建模单元，tokens.txt 为音素表）
         */
        val PRESET_KEYWORDS = mapOf(
            "小乐助" to listOf(
                "x iǎo l è zh ù @小乐助",
                "x iǎo y uè zh ù @小乐助"
            ),
            "小爱同学" to listOf("x iǎo ài t óng x ué @小爱同学"),
            "你好问问" to listOf("n ǐ h ǎo w èn w èn @你好问问"),
            "小艺小艺" to listOf("x iǎo y ì x iǎo y ì @小艺小艺"),
            "小米小米" to listOf("x iǎo m ǐ x iǎo m ǐ @小米小米"),
            "你好你好" to listOf("n ǐ h ǎo n ǐ h ǎo @你好你好"),
            "你好小助" to listOf("n ǐ h ǎo x iǎo zh ù @你好小助"),
            "你好西西" to listOf("n ǐ h ǎo x ī x ī @你好西西"),
            "你好军哥" to listOf("n ǐ h ǎo j ūn g ē @你好军哥"),
            "蛋哥蛋哥" to listOf("d àn g ē d àn g ē @蛋哥蛋哥"),
            "林美丽" to listOf("l ín m ěi l ì @林美丽")
        )
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var isInitialized = false

    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * 初始化 KeywordSpotter 引擎
     */
    fun initEngine(keyword: String = config.defaultKeyword): Boolean {
        if (isInitialized && spotter != null) return true

        try {
            Log.d(TAG, "🔍 正在检索 Sherpa-ONNX KWS 离线模型...")
            val modelLocation = locateModelFiles()
            if (modelLocation == null) {
                Log.w(TAG, "⚠️ 未检测到可用的 KWS 唤醒模型，请确保将模型放入 models/kws 目录或 assets/kws")
                isReady = false
                return false
            }

            val spotterConfig = buildSpotterConfig(modelLocation, keyword)
            spotter = if (modelLocation.isFromAsset) {
                Log.i(TAG, "🚀 从 AssetManager 加载 KWS 唤醒引擎...")
                KeywordSpotter(context.assets, spotterConfig)
            } else {
                Log.i(TAG, "🚀 从文件系统加载 KWS 唤醒引擎: ${modelLocation.baseDir?.absolutePath}")
                KeywordSpotter(null, spotterConfig)
            }

            stream = spotter?.createStream()
            isInitialized = true
            isReady = true
            Log.i(TAG, "✅ Sherpa-ONNX 离线语音唤醒引擎初始化成功！默认唤醒词: [$keyword]")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 初始化 KWS 引擎失败: ${e.message}", e)
            release()
            isReady = false
            return false
        }
    }

    /**
     * 输入 PCM 音频波形切片并执行唤醒匹配
     * @param samples 归一化的 float 音频样本 [-1.0, 1.0]
     * @return 命中的唤醒词，若未命中返回 null
     */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int = config.sampleRate): String? {
        val s = spotter ?: return null
        val st = stream ?: return null

        try {
            st.acceptWaveform(samples, sampleRate)
            while (s.isReady(st)) {
                s.decode(st)
            }
            val result = s.getResult(st)
            if (result.keyword.isNotBlank()) {
                val detected = result.keyword.trim().removePrefix("@").trim()
                Log.i(TAG, "🎉 [BINGO] 成功检测到唤醒词: $detected (置信度阈值: ${config.threshold})")
                s.reset(st)
                return detected
            }
        } catch (e: Exception) {
            Log.e(TAG, "KWS 推理匹配异常: ${e.message}", e)
        }
        return null
    }

    /**
     * 复位当前音频流状态
     */
    fun reset() {
        try {
            val s = spotter
            val st = stream
            if (s != null && st != null) {
                s.reset(st)
            }
        } catch (e: Exception) {
            Log.w(TAG, "复位 KWS 流异常: ${e.message}")
        }
    }

    /**
     * 释放底层 ONNX 资源与 Stream
     */
    fun release() {
        try {
            stream?.release()
            stream = null
            spotter?.release()
            spotter = null
            isInitialized = false
            isReady = false
            Log.d(TAG, "KWS 引擎资源已彻底释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放 KWS 引擎失败: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------
    // 模型文件探测与配置生成
    // -------------------------------------------------------------

    private data class ModelLocation(
        val isFromAsset: Boolean,
        val baseDir: File? = null,
        val encoder: String = "",
        val decoder: String = "",
        val joiner: String = "",
        val singleModel: String = "",
        val tokens: String = "",
        val keywordsFile: String = ""
    )

    private fun locateModelFiles(): ModelLocation? {
        // 1. 优先探测外置存储与私有目录
        val pkg = context.packageName
        val candidates = mutableListOf<File>()
        config.customModelDir?.let { candidates.add(File(it)) }

        context.getExternalFilesDir(null)?.let {
            candidates.add(it.resolve("models/kws"))
            candidates.add(it.resolve("kws"))
            candidates.add(it.resolve("models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"))
            candidates.add(it.resolve("sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"))
        }
        candidates.add(context.filesDir.resolve("models/kws"))
        candidates.add(context.filesDir.resolve("kws"))
        candidates.add(context.filesDir.resolve("models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"))
        candidates.add(context.filesDir.resolve("sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"))
        candidates.add(File("/sdcard/Android/data/$pkg/files/models/kws"))
        candidates.add(File("/sdcard/Android/data/$pkg/files/models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"))
        candidates.add(File("/storage/emulated/0/Android/data/$pkg/files/models/kws"))
        candidates.add(File("/storage/emulated/0/Android/data/$pkg/files/models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"))

        for (dir in candidates.distinct()) {
            if (!dir.exists() || !dir.isDirectory) continue

            val tokensFile = File(dir, "tokens.txt")
            if (!tokensFile.exists() || !tokensFile.canRead()) continue

            // 检查 Transducer 架构三件套 (encoder, decoder, joiner)，优先选用 int8 量化权重减少内存占用
            val allFiles = dir.listFiles() ?: continue
            val encoder = allFiles.firstOrNull { it.name.contains("encoder") && it.name.contains("epoch-12") && it.name.endsWith(".int8.onnx") }
                ?: allFiles.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".int8.onnx") }
                ?: allFiles.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }

            if (encoder != null) {
                val isInt8 = encoder.name.endsWith(".int8.onnx")
                val epochPart = if (encoder.name.contains("epoch-12")) "epoch-12" else if (encoder.name.contains("epoch-99")) "epoch-99" else ""
                val decoder = allFiles.firstOrNull {
                    it.name.contains("decoder") && (epochPart.isEmpty() || it.name.contains(epochPart)) && (if (isInt8) it.name.endsWith(".int8.onnx") else true)
                } ?: allFiles.firstOrNull { it.name.contains("decoder") && (if (isInt8) it.name.endsWith(".int8.onnx") else true) }

                val joiner = allFiles.firstOrNull {
                    it.name.contains("joiner") && (epochPart.isEmpty() || it.name.contains(epochPart)) && (if (isInt8) it.name.endsWith(".int8.onnx") else true)
                } ?: allFiles.firstOrNull { it.name.contains("joiner") && (if (isInt8) it.name.endsWith(".int8.onnx") else true) }

                if (decoder != null && joiner != null) {
                    return ModelLocation(
                        isFromAsset = false,
                        baseDir = dir,
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                        tokens = tokensFile.absolutePath,
                        keywordsFile = ensureKeywordsFile(dir, config.defaultKeyword).absolutePath
                    )
                }
            }

            // 检查单个模型 (Zipformer2Ctc)
            val singleModel = allFiles.firstOrNull { (it.name == "model.onnx" || it.name.endsWith(".onnx")) && !it.name.contains("encoder") }
            if (singleModel != null) {
                return ModelLocation(
                    isFromAsset = false,
                    baseDir = dir,
                    singleModel = singleModel.absolutePath,
                    tokens = tokensFile.absolutePath,
                    keywordsFile = ensureKeywordsFile(dir, config.defaultKeyword).absolutePath
                )
            }
        }

        // 2. 检查 assets 目录
        try {
            val assetFiles = context.assets.list("kws") ?: emptyArray()
            if (assetFiles.contains("tokens.txt")) {
                val encoder = assetFiles.firstOrNull { it.contains("encoder") && it.endsWith(".onnx") }
                val decoder = assetFiles.firstOrNull { it.contains("decoder") && it.endsWith(".onnx") }
                val joiner = assetFiles.firstOrNull { it.contains("joiner") && it.endsWith(".onnx") }
                if (encoder != null && decoder != null && joiner != null) {
                    val kwFile = ensureKeywordsFile(context.filesDir, config.defaultKeyword)
                    return ModelLocation(
                        isFromAsset = true,
                        encoder = "kws/$encoder",
                        decoder = "kws/$decoder",
                        joiner = "kws/$joiner",
                        tokens = "kws/tokens.txt",
                        keywordsFile = kwFile.absolutePath
                    )
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun ensureKeywordsFile(targetDir: File, keyword: String): File {
        val dirKwFile = File(targetDir, "keywords.txt")
        val internalKwFile = File(context.filesDir, "kws_keywords.txt")
        val generatedContent = buildKeywordsContent(keyword)

        var resolvedFile: File? = null
        try {
            if (dirKwFile.exists() && dirKwFile.canRead()) {
                val currentText = dirKwFile.readText()
                if (currentText.contains(keyword) || (keyword == "小乐助" && currentText.contains("x iǎo l è zh ù"))) {
                    resolvedFile = dirKwFile
                }
            }
            if (resolvedFile == null) {
                dirKwFile.writeText(generatedContent)
                Log.i(TAG, "已为 KWS 模型目录更新 keywords.txt: ${dirKwFile.absolutePath}")
                resolvedFile = dirKwFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "写入外部模型目录 keywords.txt 失败(${e.message})，转为写入应用私有存储...")
        }

        if (resolvedFile != null && resolvedFile.exists() && resolvedFile.canRead()) {
            return resolvedFile
        }

        // 回退机制：写入应用私有目录 context.filesDir/kws_keywords.txt
        try {
            internalKwFile.writeText(generatedContent)
            Log.i(TAG, "已在私有目录生成 keywords.txt: ${internalKwFile.absolutePath}")
            return internalKwFile
        } catch (e: Exception) {
            Log.e(TAG, "写入私有 keywords.txt 失败: ${e.message}")
        }

        return dirKwFile
    }

    private fun buildKeywordsContent(keyword: String): String {
        val lines = mutableListOf<String>()

        // 1. 如果请求的唤醒词在预设列表中，优先加入
        PRESET_KEYWORDS[keyword]?.let { lines.addAll(it) }

        // 2. 确保默认唤醒词“小乐助”必定存在
        if (keyword != "小乐助") {
            PRESET_KEYWORDS["小乐助"]?.let { lines.addAll(it) }
        }

        // 3. 加入其他常用预设唤醒词
        PRESET_KEYWORDS.forEach { (name, ruleList) ->
            if (name != keyword && name != "小乐助") {
                lines.addAll(ruleList)
            }
        }

        // 4. 如果是自定义且尚未加入的唤醒词（比如已带音素或纯文本）
        if (!PRESET_KEYWORDS.containsKey(keyword)) {
            if (keyword.contains("@") || keyword.contains(" ")) {
                lines.add(0, keyword)
            } else {
                val tokensSpaced = keyword.toCharArray().joinToString(" ")
                lines.add(0, "$tokensSpaced @$keyword")
            }
        }

        return lines.distinct().joinToString("\n") + "\n"
    }

    private fun buildSpotterConfig(loc: ModelLocation, keyword: String): KeywordSpotterConfig {
        val featConfig = FeatureConfig(
            sampleRate = config.sampleRate,
            featureDim = 80,
            dither = 0.0f
        )

        val modelConfig = OnlineModelConfig().apply {
            tokens = loc.tokens
            numThreads = config.numThreads
            debug = false
            provider = "cpu"
            modelType = ""

            if (loc.encoder.isNotBlank() && loc.decoder.isNotBlank() && loc.joiner.isNotBlank()) {
                transducer.encoder = loc.encoder
                transducer.decoder = loc.decoder
                transducer.joiner = loc.joiner
            } else if (loc.singleModel.isNotBlank()) {
                zipformer2Ctc.model = loc.singleModel
            }
        }

        return KeywordSpotterConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            maxActivePaths = 4,
            keywordsFile = loc.keywordsFile,
            keywordsScore = config.score,
            keywordsThreshold = config.threshold,
            numTrailingBlanks = 1
        )
    }
}
