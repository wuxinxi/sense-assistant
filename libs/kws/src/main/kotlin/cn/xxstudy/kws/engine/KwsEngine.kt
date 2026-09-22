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
            "艾诗" to listOf(
                "ài sh ī @艾诗",
                "ài s ī @艾诗",                // 平翘舌容错 (SH -> S)
                "ài x ī @艾诗",                // 尖音/方言容错 (SH -> X)
                "ài sh i @艾诗",               // 轻声容错
                "n ǐ h ǎo ài sh ī @艾诗",       // 连调：“你好艾诗”
                "n ǐ h ǎo ài s ī @艾诗",        // 连调平翘舌容错
                "x iǎo ài sh ī @艾诗",         // “小艾诗”
                "ài sh ī ài sh ī @艾诗"         // “艾诗艾诗”双呼
            ),
            "你好艾诗" to listOf(
                "n ǐ h ǎo ài sh ī @你好艾诗",
                "n ǐ h ǎo ài s ī @你好艾诗",
                "ài sh ī @你好艾诗"
            ),
            "小乐助" to listOf(
                "x iǎo l è zh ù @小乐助",
                "x iǎo y uè zh ù @小乐助",
                "x iǎo l iú zh ù @小乐助",
                "x iǎo l ōu zh ù @小乐助",
                "x iǎo l è zh ū @小乐助",
                "x iǎo l è zh ú @小乐助",
                "x iǎo n è zh ù @小乐助", // N/L 不分
                "x iǎo l è z ù @小乐助",  // 平翘舌不分 (ZH -> Z)
                "x iǎo n è z ù @小乐助"   // N/L + 平翘舌都不分
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
            val modelLocation = locateModelFiles(keyword)
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
            val startMs = System.currentTimeMillis()
            var decoded = false
            st.acceptWaveform(samples, sampleRate)
            while (s.isReady(st)) {
                s.decode(st)
                decoded = true
            }
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed > 100) {
                Log.w(TAG, "⚠️ KWS decode 耗时过长: ${elapsed}ms (音频时长: 100ms)，可能导致丢帧！")
            } else if (decoded) {
                // normal decode
            } else {
                // isReady==false 是 Zipformer chunk 模型的正常行为，无需警告
            }
            
            val result = s.getResult(st)
            if (result.keyword.isNotBlank()) {
                val detected = result.keyword.trim().removePrefix("@").trim()
                Log.i(TAG, "🎉 [BINGO] 成功检测到唤醒词: $detected (置信度阈值: ${config.threshold})")
                // 不在这里 reset，由 handleWakeWordDetected → pause() 统一处理
                return detected
            }
        } catch (e: Exception) {
            Log.e(TAG, "KWS 推理匹配异常: ${e.message}", e)
        }
        return null
    }

    /**
     * 流级复位 (Stream-level Reset)
     * 因为 sherpa-onnx 的 spotter.reset(stream) 存在未清理 OnlineStream 缓冲区的 Bug (导致多次唤醒后聋了)，
     * 且核弹级复位 (销毁引擎) 会引起 CPU 飙升。
     * 最佳方案是：保留 KeywordSpotter 引擎(免去重新加载模型)，仅销毁并重新创建 Stream。
     */
    fun reset() {
        try {
            val s = spotter
            if (s != null) {
                stream?.release()
                stream = s.createStream() // 创建新 Stream，彻底重置缓冲区和隐状态，同时继承配置
                Log.d(TAG, "♻️ KWS Stream 级无感复位完成 (CPU 零波动)")
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

    private fun locateModelFiles(keyword: String): ModelLocation? {
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
                        keywordsFile = ensureKeywordsFile(dir, keyword, tokensFile).absolutePath
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
                    keywordsFile = ensureKeywordsFile(dir, keyword, tokensFile).absolutePath
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
                    val kwFile = ensureKeywordsFile(context.filesDir, keyword, null)
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

    private fun ensureKeywordsFile(targetDir: File, keyword: String, tokensFile: File? = null): File {
        val dirKwFile = File(targetDir, "keywords.txt")
        val internalKwFile = File(context.filesDir, "kws_keywords.txt")
        val generatedContent = buildKeywordsContent(keyword, tokensFile)

        var resolvedFile: File? = null
        try {
            // 每次启动都强制覆写 keywords.txt，以确保最新的容错音素表（如“小刘柱”口音补偿）能够生效
            dirKwFile.writeText(generatedContent)
            resolvedFile = dirKwFile
        } catch (e: Exception) {
            Log.w(TAG, "无法写入模型目录下的 keywords.txt (${e.message})，降级写入沙盒内部")
            try {
                internalKwFile.writeText(generatedContent)
                Log.i(TAG, "已回退写入私有目录 kws_keywords.txt: ${internalKwFile.absolutePath}")
                resolvedFile = internalKwFile
            } catch (ex: Exception) {
                Log.e(TAG, "彻底无法写入任何 keywords.txt: ${ex.message}")
            }
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

    private fun buildKeywordsContent(keyword: String, tokensFile: File? = null): String {
        val trimmed = keyword.trim()
        val lines = mutableListOf<String>()

        // 1. 如果请求的唤醒词在预设列表中，加入其所有拼音变体（含容错发音）
        if (PRESET_KEYWORDS.containsKey(trimmed)) {
            PRESET_KEYWORDS[trimmed]?.let { lines.addAll(it) }
        } else if (trimmed.contains("@") && trimmed.contains(" ")) {
            // 2. 如果是显式提供了声韵母音素切分的自定义规则，如 "x iǎo ài @小爱"
            lines.add(trimmed)
        } else {
            // 3. 未知唤醒词且未提供合法音素：严禁直接将未经分词的汉字写入 keywords.txt，防止 Sherpa-ONNX abort 崩溃！
            Log.w(TAG, "⚠️ 唤醒词 [$trimmed] 未在预设音素库中，且未提供音素切分。为防止底层 C++ abort 崩溃，自动安全降级至默认唤醒词 [艾诗]")
            PRESET_KEYWORDS["艾诗"]?.let { lines.addAll(it) }
                ?: PRESET_KEYWORDS["小乐助"]?.let { lines.addAll(it) }
                ?: lines.add("ài sh ī @艾诗")
        }

        // 4. 基于 tokens.txt 进行防呆合法性校验，剔除无法被模型分词的非法音素
        val validLines = if (tokensFile != null && tokensFile.exists() && tokensFile.canRead()) {
            val validTokens = try {
                tokensFile.readLines()
                    .map { it.split("\\s+".toRegex()).firstOrNull() ?: "" }
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (e: Exception) {
                Log.w(TAG, "读取 tokens.txt 失败: ${e.message}")
                emptySet()
            }

            if (validTokens.isNotEmpty()) {
                lines.filter { line ->
                    val phonemePart = line.substringBefore("@").trim()
                    val tokens = phonemePart.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    val allValid = tokens.all { validTokens.contains(it) }
                    if (!allValid) {
                        val invalid = tokens.filter { !validTokens.contains(it) }
                        Log.e(TAG, "❌ 唤醒规则 [$line] 包含 tokens.txt 不支持的音素: $invalid，已自动剔除")
                    }
                    allValid
                }
            } else {
                lines
            }
        } else {
            lines
        }

        val finalLines = if (validLines.isEmpty()) {
            Log.e(TAG, "❌ 所有唤醒规则均无效，紧急写入最小安全规则: ài sh ī @艾诗")
            listOf("ài sh ī @艾诗")
        } else {
            validLines
        }

        return finalLines.joinToString("\n") + "\n"
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
