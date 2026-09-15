import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/data/AppSettings.kt', 'r') as f:
    content = f.read()

# 1. Add variable declaration
var_decl = """    private val _isPhoneModeEnabled = MutableStateFlow(false)
    val isPhoneModeEnabled: StateFlow<Boolean> = _isPhoneModeEnabled.asStateFlow()

    private val _isCallSubtitleEnabled = MutableStateFlow(true)
    val isCallSubtitleEnabled: StateFlow<Boolean> = _isCallSubtitleEnabled.asStateFlow()"""
content = content.replace("""    private val _isPhoneModeEnabled = MutableStateFlow(false)
    val isPhoneModeEnabled: StateFlow<Boolean> = _isPhoneModeEnabled.asStateFlow()""", var_decl)

# 2. Add to init block
init_decl = """        _isPhoneModeEnabled.value = prefs.getBoolean(KEY_PHONE_MODE_ENABLED, false)
        _isCallSubtitleEnabled.value = prefs.getBoolean(KEY_CALL_SUBTITLE_ENABLED, true)"""
content = content.replace("        _isPhoneModeEnabled.value = prefs.getBoolean(KEY_PHONE_MODE_ENABLED, false)", init_decl)

# 3. Add setter method
setter_decl = """    fun setPhoneModeEnabled(enabled: Boolean) {
        _isPhoneModeEnabled.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_PHONE_MODE_ENABLED, enabled).apply()
    }

    fun setCallSubtitleEnabled(enabled: Boolean) {
        _isCallSubtitleEnabled.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_CALL_SUBTITLE_ENABLED, enabled).apply()
    }"""
content = content.replace("""    fun setPhoneModeEnabled(enabled: Boolean) {
        _isPhoneModeEnabled.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_PHONE_MODE_ENABLED, enabled).apply()
    }""", setter_decl)

# 4. Add key constants
keys_decl = """        private const val KEY_PHONE_MODE_ENABLED = "phone_mode_enabled"
        private const val KEY_CALL_SUBTITLE_ENABLED = "call_subtitle_enabled\""""
content = content.replace("""        private const val KEY_PHONE_MODE_ENABLED = "phone_mode_enabled\"""", keys_decl)

with open('app/src/main/kotlin/cn/xxstudy/assistant/data/AppSettings.kt', 'w') as f:
    f.write(content)
print("Patched AppSettings")
