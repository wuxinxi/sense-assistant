package cn.xxstudy.kws

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.xxstudy.kws.audio.KwsAudioRecorder
import cn.xxstudy.kws.engine.KwsEngine
import cn.xxstudy.kws.sound.SoundPoolHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [KwsManager]
 * 离线语音唤醒门面入口 (Facade)：
 * 1. 极致极简调用：支持【一行代码】开启离线监听；
 * 2. 默认内置唤醒词：“小乐助”；
 * 3. 毫秒级反馈：命中瞬间通过底层 SoundPool 播放短促“滴”声；
 * 4. 麦克风平滑移交：命中瞬间原子化释放麦克风，杜绝与 SenseVoice ASR 冲突。
 */
object KwsManager {

    private const val TAG = "KwsManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: KwsEngine? = null
    private var recorder: KwsAudioRecorder? = null
    private var soundPool: SoundPoolHelper? = null

    private var wakeCallback: ((keyword: String) -> Unit)? = null
    private val isListeningState = AtomicBoolean(false)

    private var currentKeyword = "艾诗"
    private var currentConfig = KwsConfig()

    /**
     * 【一行代码开启监听】
     * @param context 上下文对象
     * @param keyword 唤醒词（默认“艾诗”）
     * @param config 细粒度参数配置
     * @param onWake 命中唤醒词时的回调（此时麦克风已释放、滴声已响起）
     */
    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        keyword: String = "艾诗",
        config: KwsConfig = KwsConfig(defaultKeyword = keyword),
        onWake: (keyword: String) -> Unit
    ): Boolean {
        currentKeyword = keyword
        currentConfig = config
        wakeCallback = onWake

        val appContext = context.applicationContext

        // 1. 初始化毫秒级音效播放器
        if (soundPool == null && config.enableDingSound) {
            soundPool = SoundPoolHelper(appContext)
        }

        // 2. 初始化 Sherpa-ONNX 离线轻量唤醒引擎
        if (engine == null) {
            engine = KwsEngine(appContext, config)
        }

        val engineReady = engine?.initEngine(keyword) ?: false
        if (!engineReady) {
            Log.w(TAG, "⚠️ KWS 模型尚未准备就绪，跳过麦克风开启")
            return false
        }

        // 3. 初始化低功耗音频流采集器
        if (recorder == null) {
            recorder = KwsAudioRecorder(config.sampleRate) { samples ->
                val detected = engine?.acceptWaveform(samples, config.sampleRate)
                if (!detected.isNullOrBlank()) {
                    handleWakeWordDetected(detected)
                }
            }
        }

        val started = recorder?.start() ?: false
        isListeningState.set(started)
        if (started) {
            Log.i(TAG, "🚀 [KWS] 一行代码唤醒监听已就绪！默认唤醒词: [$keyword]")
        }
        return started
    }

    /**
     * 暂停监听并彻底释放麦克风（供 SenseVoice ASR 接管）
     */
    @JvmStatic
    fun pause() {
        if (!isListeningState.get()) return
        Log.d(TAG, "⏸️ [KWS] 挂起唤醒监听并释放麦克风...")
        recorder?.stop()
        // 不在这里 reset stream，由 resume() 统一重建，避免 C++ 层 stream 泄漏
        isListeningState.set(false)
    }

    /**
     * 恢复待机监听（当 ASR 或 TTS 执行结束后调用）
     */
    @JvmStatic
    fun resume(): Boolean {
        if (isListeningState.get()) return true
        val eng = engine
        if (eng == null || (!eng.isReady && currentKeyword.isBlank())) {
            Log.d(TAG, "KWS 引擎未就绪，无法恢复监听")
            return false
        }

        Log.d(TAG, "▶️ [KWS] 恢复待机监听麦克风 (异步)...")
        // 标记为正在监听，防止重复调用
        isListeningState.set(true)
        
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            try {
                eng.reset() // 核弹级复位，涉及磁盘 IO 和 ONNX 加载
                val started = recorder?.start() ?: false
                if (!started) {
                    isListeningState.set(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "异步恢复 KWS 失败: ${e.message}")
                isListeningState.set(false)
            }
        }, "KwsResumeThread").start()

        return true
    }

    /**
     * 彻底停止并销毁全部底层资源
     */
    @JvmStatic
    fun stop() {
        Log.d(TAG, "🛑 [KWS] 彻底停止唤醒引擎")
        isListeningState.set(false)
        recorder?.stop()
        recorder = null
        engine?.release()
        engine = null
        soundPool?.release()
        soundPool = null
        wakeCallback = null
    }

    /**
     * 唤醒命中内部协调逻辑
     */
    private fun handleWakeWordDetected(keyword: String) {
        Log.i(TAG, "🎯 [KWS] 触发唤醒事件: $keyword")

        // 1. 瞬间播放短促“滴”提示音 (延迟 < 10ms)
        if (currentConfig.enableDingSound) {
            soundPool?.playDing(1.0f)
        }

        // 2. 立即停止并释放麦克风，确保 ASR 接管时绝不冲突
        pause()

        // 3. 切到主线程向外派发回调
        val callback = wakeCallback
        if (callback != null) {
            mainHandler.post {
                callback.invoke(keyword)
            }
        }
    }

    /**
     * 当前是否正在监听环境音
     */
    val isListening: Boolean
        get() = isListeningState.get()

    /**
     * 唤醒引擎模型是否已准备就绪
     */
    val isReady: Boolean
        get() = engine?.isReady == true
}
