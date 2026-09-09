package cn.xxstudy.assistant.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式")
}

enum class ColorTheme(val label: String, val primaryHex: Long) {
    PURPLE("极客紫", 0xFF6750A4),
    BLUE("科技蓝", 0xFF0061A4),
    GREEN("翡翠绿", 0xFF2E6B27),
    ORANGE("赛博橙", 0xFF9C4300)
}

enum class ModelFileStatus {
    READY,        // 完全匹配，已就绪可加载
    INCOMPLETE,   // 文件存在但字节不足（正在传输或残缺）
    MISSING       // 本地完全不存在
}

data class ModelFileCheck(
    val status: ModelFileStatus,
    val currentBytes: Long = 0L,
    val expectedBytes: Long = 0L,
    val file: java.io.File? = null
) {
    val progressPercent: Int
        get() = if (expectedBytes > 0) ((currentBytes.toDouble() / expectedBytes.toDouble()) * 100).toInt().coerceIn(0, 100) else 0
}

enum class ModelType(
    val id: String,
    val displayName: String,
    val fileName: String,
    val parameterSize: String,
    val memoryRequirement: String,
    val expectedSizeBytes: Long,
    val supportsThinking: Boolean,
    val description: String
) {
    MINICPM5_2B(
        id = "minicpm5_2b",
        displayName = "MiniCPM5-2B (深度思考)",
        fileName = "MiniCPM5-2B-Q4_K_M.gguf",
        parameterSize = "2.4B",
        memoryRequirement = "~1.8GB",
        expectedSizeBytes = 1561318368L,
        supportsThinking = true,
        description = "面壁智能端侧旗舰模型，2B-SOTA，支持逻辑推理思维链"
    ),
    QWEN_0_5B(
        id = "qwen2.5_0.5b",
        displayName = "Qwen2.5-0.5B-Instruct",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        parameterSize = "0.5B",
        memoryRequirement = "~570MB",
        expectedSizeBytes = 491400032L,
        supportsThinking = false,
        description = "极速轻量问答，内存开销极低"
    )
}

object AppSettings {
    private const val PREFS_NAME = "sense_assistant_settings"

    // Keys
    private const val KEY_THEME_MODE = "key_theme_mode"
    private const val KEY_COLOR_THEME = "key_color_theme"
    private const val KEY_CONTEXT_SIZE = "key_context_size"
    private const val KEY_SYSTEM_PROMPT = "key_system_prompt"
    private const val KEY_CURRENT_MODEL = "key_current_model"
    private const val KEY_ENABLE_THINKING = "key_enable_thinking"
    private const val KEY_ASR_LANGUAGE = "key_asr_language"
    private const val KEY_ASR_AUTO_SEND = "key_asr_auto_send"
    private const val KEY_TTS_AUTO_PLAY = "key_tts_auto_play"
    private const val KEY_TTS_SPEECH_RATE = "key_tts_speech_rate"
    private const val KEY_TTS_PITCH = "key_tts_pitch"
    private const val KEY_TTS_SPEAKER_ID = "key_tts_speaker_id"
    private const val KEY_HAPTIC_ENABLED = "key_haptic_enabled"
    private const val KEY_LOCAL_SERVER_ENABLED = "key_local_server_enabled"

    private lateinit var prefs: SharedPreferences

    // StateFlows
    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _colorTheme = MutableStateFlow(ColorTheme.PURPLE)
    val colorTheme: StateFlow<ColorTheme> = _colorTheme.asStateFlow()

    private val _contextSize = MutableStateFlow(2048)
    val contextSize: StateFlow<Int> = _contextSize.asStateFlow()

    private val _systemPrompt = MutableStateFlow("你是 TangRen 端侧智能助手。请用简明扼要的中文回答用户的问题。")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _currentModelType = MutableStateFlow(ModelType.MINICPM5_2B)
    val currentModelType: StateFlow<ModelType> = _currentModelType.asStateFlow()

    private val _enableThinking = MutableStateFlow(true)
    val enableThinking: StateFlow<Boolean> = _enableThinking.asStateFlow()

    private val _asrLanguage = MutableStateFlow("zh-CN")
    val asrLanguage: StateFlow<String> = _asrLanguage.asStateFlow()

    private val _asrAutoSend = MutableStateFlow(false)
    val asrAutoSend: StateFlow<Boolean> = _asrAutoSend.asStateFlow()

    private val _ttsAutoPlay = MutableStateFlow(true)
    val ttsAutoPlay: StateFlow<Boolean> = _ttsAutoPlay.asStateFlow()

    private val _ttsSpeechRate = MutableStateFlow(1.0f)
    val ttsSpeechRate: StateFlow<Float> = _ttsSpeechRate.asStateFlow()

    private val _ttsPitch = MutableStateFlow(1.0f)
    val ttsPitch: StateFlow<Float> = _ttsPitch.asStateFlow()

    private val _ttsSpeakerId = MutableStateFlow(0)
    val ttsSpeakerId: StateFlow<Int> = _ttsSpeakerId.asStateFlow()

    private val _hapticEnabled = MutableStateFlow(true)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    private val _localServerEnabled = MutableStateFlow(true)
    val localServerEnabled: StateFlow<Boolean> = _localServerEnabled.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedThemeMode = prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name)
        _themeMode.value = runCatching { ThemeMode.valueOf(savedThemeMode ?: ThemeMode.DARK.name) }.getOrDefault(ThemeMode.DARK)

        val savedColorTheme = prefs.getString(KEY_COLOR_THEME, ColorTheme.PURPLE.name)
        _colorTheme.value = runCatching { ColorTheme.valueOf(savedColorTheme ?: ColorTheme.PURPLE.name) }.getOrDefault(ColorTheme.PURPLE)

        _contextSize.value = prefs.getInt(KEY_CONTEXT_SIZE, 2048)
        _systemPrompt.value = prefs.getString(KEY_SYSTEM_PROMPT, "你是 TangRen 端侧智能助手。请用简明扼要的中文回答用户的问题。") ?: "你是 TangRen 端侧智能助手。请用简明扼要的中文回答用户的问题。"

        val savedModelType = prefs.getString(KEY_CURRENT_MODEL, ModelType.MINICPM5_2B.name)
        _currentModelType.value = runCatching { ModelType.valueOf(savedModelType ?: ModelType.MINICPM5_2B.name) }.getOrDefault(ModelType.MINICPM5_2B)

        _enableThinking.value = prefs.getBoolean(KEY_ENABLE_THINKING, true)

        _asrLanguage.value = prefs.getString(KEY_ASR_LANGUAGE, "zh-CN") ?: "zh-CN"
        _asrAutoSend.value = prefs.getBoolean(KEY_ASR_AUTO_SEND, false)

        _ttsAutoPlay.value = prefs.getBoolean(KEY_TTS_AUTO_PLAY, true)
        _ttsSpeechRate.value = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f)
        _ttsPitch.value = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
        _ttsSpeakerId.value = prefs.getInt(KEY_TTS_SPEAKER_ID, 0).coerceIn(0, 173)

        _hapticEnabled.value = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        _localServerEnabled.value = prefs.getBoolean(KEY_LOCAL_SERVER_ENABLED, true)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        if (::prefs.isInitialized) prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun setColorTheme(theme: ColorTheme) {
        _colorTheme.value = theme
        if (::prefs.isInitialized) prefs.edit().putString(KEY_COLOR_THEME, theme.name).apply()
    }

    fun setContextSize(size: Int) {
        _contextSize.value = size
        if (::prefs.isInitialized) prefs.edit().putInt(KEY_CONTEXT_SIZE, size).apply()
    }

    fun setSystemPrompt(prompt: String) {
        _systemPrompt.value = prompt
        if (::prefs.isInitialized) prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply()
    }

    fun setCurrentModelType(modelType: ModelType) {
        _currentModelType.value = modelType
        prefs.edit().putString(KEY_CURRENT_MODEL, modelType.name).apply()
    }

    fun setEnableThinking(enabled: Boolean) {
        _enableThinking.value = enabled
        prefs.edit().putBoolean(KEY_ENABLE_THINKING, enabled).apply()
    }

    fun setAsrLanguage(language: String) {
        _asrLanguage.value = language
        if (::prefs.isInitialized) prefs.edit().putString(KEY_ASR_LANGUAGE, language).apply()
    }

    fun setAsrAutoSend(autoSend: Boolean) {
        _asrAutoSend.value = autoSend
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_ASR_AUTO_SEND, autoSend).apply()
    }

    fun setTtsAutoPlay(autoPlay: Boolean) {
        _ttsAutoPlay.value = autoPlay
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_TTS_AUTO_PLAY, autoPlay).apply()
    }

    fun setTtsSpeechRate(rate: Float) {
        _ttsSpeechRate.value = rate
        if (::prefs.isInitialized) prefs.edit().putFloat(KEY_TTS_SPEECH_RATE, rate).apply()
    }

    fun setTtsPitch(pitch: Float) {
        _ttsPitch.value = pitch
        if (::prefs.isInitialized) prefs.edit().putFloat(KEY_TTS_PITCH, pitch).apply()
    }

    fun setTtsSpeakerId(speakerId: Int) {
        val clamped = speakerId.coerceIn(0, 173)
        _ttsSpeakerId.value = clamped
        if (::prefs.isInitialized) prefs.edit().putInt(KEY_TTS_SPEAKER_ID, clamped).apply()
    }

    fun setHapticEnabled(enabled: Boolean) {
        _hapticEnabled.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    fun setLocalServerEnabled(enabled: Boolean) {
        _localServerEnabled.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_LOCAL_SERVER_ENABLED, enabled).apply()
    }

    /**
     * 对指定大模型进行全方位物理文件健康度与大小精确检测
     */
    fun checkModelFile(context: Context, modelType: ModelType): ModelFileCheck {
        val candidates = listOf(
            java.io.File(context.getExternalFilesDir(null), modelType.fileName),
            java.io.File(context.filesDir, modelType.fileName),
            java.io.File("/sdcard/Android/data/${context.packageName}/files/${modelType.fileName}"),
            java.io.File("/sdcard/Android/data/${context.packageName}/files/models/${modelType.fileName}")
        )
        // 找到存在的候选文件（优先选取长度最大的）
        val existingFile = candidates.filter { it.exists() }.maxByOrNull { it.length() }
            ?: return ModelFileCheck(ModelFileStatus.MISSING, 0L, modelType.expectedSizeBytes, null)

        val currentLen = existingFile.length()
        // 判定准则：文件大小必须完整达到预期大小才判定已就绪
        return if (currentLen >= modelType.expectedSizeBytes) {
            ModelFileCheck(ModelFileStatus.READY, currentLen, modelType.expectedSizeBytes, existingFile)
        } else {
            ModelFileCheck(ModelFileStatus.INCOMPLETE, currentLen, modelType.expectedSizeBytes, existingFile)
        }
    }

    /**
     * 智能定位指定大模型在设备上的物理文件路径
     * 严格安全机制：只有健康度为 READY (已完全写入) 的文件才会返回，彻底防止未传输完毕导致崩溃
     */
    fun resolveModelFile(context: Context, modelType: ModelType): java.io.File? {
        val check = checkModelFile(context, modelType)
        return if (check.status == ModelFileStatus.READY) check.file else null
    }

    /**
     * 判断指定大模型在当前设备是否已经完全就绪
     */
    fun isModelAvailable(context: Context, modelType: ModelType): Boolean {
        return checkModelFile(context, modelType).status == ModelFileStatus.READY
    }
}
