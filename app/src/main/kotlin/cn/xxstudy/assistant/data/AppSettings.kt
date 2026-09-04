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

object AppSettings {
    private const val PREFS_NAME = "sense_assistant_settings"

    // Keys
    private const val KEY_THEME_MODE = "key_theme_mode"
    private const val KEY_COLOR_THEME = "key_color_theme"
    private const val KEY_ASR_LANGUAGE = "key_asr_language"
    private const val KEY_ASR_AUTO_SEND = "key_asr_auto_send"
    private const val KEY_TTS_AUTO_PLAY = "key_tts_auto_play"
    private const val KEY_TTS_SPEECH_RATE = "key_tts_speech_rate"
    private const val KEY_TTS_PITCH = "key_tts_pitch"

    private lateinit var prefs: SharedPreferences

    // StateFlows
    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _colorTheme = MutableStateFlow(ColorTheme.PURPLE)
    val colorTheme: StateFlow<ColorTheme> = _colorTheme.asStateFlow()

    private val _asrLanguage = MutableStateFlow("zh-CN")
    val asrLanguage: StateFlow<String> = _asrLanguage.asStateFlow()

    private val _asrAutoSend = MutableStateFlow(false)
    val asrAutoSend: StateFlow<Boolean> = _asrAutoSend.asStateFlow()

    private val _ttsAutoPlay = MutableStateFlow(false)
    val ttsAutoPlay: StateFlow<Boolean> = _ttsAutoPlay.asStateFlow()

    private val _ttsSpeechRate = MutableStateFlow(1.0f)
    val ttsSpeechRate: StateFlow<Float> = _ttsSpeechRate.asStateFlow()

    private val _ttsPitch = MutableStateFlow(1.0f)
    val ttsPitch: StateFlow<Float> = _ttsPitch.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedThemeMode = prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name)
        _themeMode.value = runCatching { ThemeMode.valueOf(savedThemeMode ?: ThemeMode.DARK.name) }.getOrDefault(ThemeMode.DARK)

        val savedColorTheme = prefs.getString(KEY_COLOR_THEME, ColorTheme.PURPLE.name)
        _colorTheme.value = runCatching { ColorTheme.valueOf(savedColorTheme ?: ColorTheme.PURPLE.name) }.getOrDefault(ColorTheme.PURPLE)

        _asrLanguage.value = prefs.getString(KEY_ASR_LANGUAGE, "zh-CN") ?: "zh-CN"
        _asrAutoSend.value = prefs.getBoolean(KEY_ASR_AUTO_SEND, false)

        _ttsAutoPlay.value = prefs.getBoolean(KEY_TTS_AUTO_PLAY, false)
        _ttsSpeechRate.value = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f)
        _ttsPitch.value = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun setColorTheme(theme: ColorTheme) {
        _colorTheme.value = theme
        prefs.edit().putString(KEY_COLOR_THEME, theme.name).apply()
    }

    fun setAsrLanguage(language: String) {
        _asrLanguage.value = language
        prefs.edit().putString(KEY_ASR_LANGUAGE, language).apply()
    }

    fun setAsrAutoSend(autoSend: Boolean) {
        _asrAutoSend.value = autoSend
        prefs.edit().putBoolean(KEY_ASR_AUTO_SEND, autoSend).apply()
    }

    fun setTtsAutoPlay(autoPlay: Boolean) {
        _ttsAutoPlay.value = autoPlay
        prefs.edit().putBoolean(KEY_TTS_AUTO_PLAY, autoPlay).apply()
    }

    fun setTtsSpeechRate(rate: Float) {
        _ttsSpeechRate.value = rate
        prefs.edit().putFloat(KEY_TTS_SPEECH_RATE, rate).apply()
    }

    fun setTtsPitch(pitch: Float) {
        _ttsPitch.value = pitch
        prefs.edit().putFloat(KEY_TTS_PITCH, pitch).apply()
    }
}
