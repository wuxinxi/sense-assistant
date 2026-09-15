import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/viewmodel/MainViewModel.kt', 'r') as f:
    content = f.read()

import_str = "import android.media.AudioManager\nimport android.content.Context\n"
if "import android.media.AudioManager" not in content:
    content = content.replace("import android.app.Application\n", "import android.app.Application\n" + import_str)

old_start = """    fun startPhoneMode() {
        _isActiveCall.value = true

        if (_isActiveCall.value) {
            startVoiceRecording(autoSend = true, interruptTts = false)
        }
    }"""
new_start = """    fun startPhoneMode() {
        _isActiveCall.value = true
        
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        if (_isActiveCall.value) {
            startVoiceRecording(autoSend = true, interruptTts = false)
        }
    }"""
content = content.replace(old_start, new_start)

old_stop = """    fun stopPhoneMode() {
        _isActiveCall.value = false

        cancelVoiceRecording()
        speechManager.stopSpeaking()
    }"""
new_stop = """    fun stopPhoneMode() {
        _isActiveCall.value = false
        
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        cancelVoiceRecording()
        speechManager.stopSpeaking()
    }"""
content = content.replace(old_stop, new_stop)

with open('app/src/main/kotlin/cn/xxstudy/assistant/viewmodel/MainViewModel.kt', 'w') as f:
    f.write(content)

print("Patched MainViewModel")
