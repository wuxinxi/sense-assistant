import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/speech/SenseVoiceAsrEngine.kt', 'r') as f:
    content = f.read()

# Replace imports
content = content.replace("import io.codeconcept.realtimecutvadlibrary.VADCallback\nimport io.codeconcept.realtimecutvadlibrary.VADWrapper", """import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig""")

# Replace vadWrapper declaration
content = content.replace("private var vadWrapper: VADWrapper? = null", "private var vad: Vad? = null")

# Replace initEngine VAD initialization
old_init = """        if (vadWrapper == null) {
            vadWrapper = VADWrapper(context).apply {
                setVADModel(VADWrapper.SileroModelVersion.V5)
                setVADSampleRate(VADWrapper.SampleRate.SAMPLERATE_16)
            }
        }"""
new_init = """        if (vad == null) {
            val sileroConfig = SileroVadModelConfig(
                "silero_vad_v5.onnx", // model
                0.5f,  // threshold
                0.8f,  // minSilenceDuration (缩短一点让打断更灵敏)
                0.1f,  // minSpeechDuration
                512,   // windowSize
                20.0f  // maxSpeechDuration
            )
            val vadConfig = VadModelConfig(
                sileroConfig,
                TenVadModelConfig(), // empty tenVad
                16000, // sampleRate
                1,     // numThreads
                "cpu", // provider
                false  // debug
            )
            vad = Vad(context.assets, vadConfig)
        }"""
content = content.replace(old_init, new_init)

# Remove VAD callback registration
vad_cb = """            // 配置 VAD 回调
            vadWrapper?.setVADCallback(object : VADCallback {
                override fun onVoiceStart() {
                    onVoiceStart?.invoke()

                    Log.d(TAG, "VAD: 检测到人声开始说话")
                }
                override fun onVoiceEnd(wavData: ByteArray?) {
                    Log.d(TAG, "VAD: 检测到人声结束 (静音)，触发断句")
                    if (autoStop) {
                        stopListening()
                    }
                }
                override fun onVoiceDidContinue(pcmFloatData: ByteArray?) {}
            })"""
content = content.replace(vad_cb, """
            vad?.clear()
            vad?.reset()
            var hasTriggeredVoiceStart = false
""")

# Replace VAD processing inside the loop
old_process = """                            // 喂给 Silero VAD 进行精准断句检测
                            vadWrapper?.processAudio(floatArr)"""
new_process = """                            // 喂给 Sherpa-ONNX 自带的 Silero VAD 进行检测
                            vad?.acceptWaveform(floatArr)
                            if (vad?.isSpeechDetected() == true && !hasTriggeredVoiceStart) {
                                hasTriggeredVoiceStart = true
                                onVoiceStart?.invoke()
                                Log.d(TAG, "VAD: 检测到人声开始说话")
                            }
                            if (vad?.empty() == false) {
                                vad?.pop()
                                Log.d(TAG, "VAD: 检测到人声结束 (静音)，触发断句")
                                stopListening()
                                break
                            }"""
content = content.replace(old_process, new_process)

with open('app/src/main/kotlin/cn/xxstudy/assistant/speech/SenseVoiceAsrEngine.kt', 'w') as f:
    f.write(content)

print("Patched Engine")
