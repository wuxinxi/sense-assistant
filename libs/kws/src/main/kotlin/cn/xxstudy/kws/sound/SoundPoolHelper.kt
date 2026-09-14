package cn.xxstudy.kws.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import cn.xxstudy.kws.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [SoundPoolHelper]
 * 毫秒级低延迟音效调度器：
 * 唤醒词命中瞬间通过硬件底层直接混音输出短促“滴”声（< 10ms 延迟），
 * 彻底告别 MediaPlayer 的卡顿与高延迟。
 */
class SoundPoolHelper(private val context: Context) {

    companion object {
        private const val TAG = "KwsSoundPool"
    }

    private var soundPool: SoundPool? = null
    private var soundId: Int = 0
    private val isLoaded = AtomicBoolean(false)

    init {
        initSoundPool()
    }

    private fun initSoundPool() {
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attributes)
                .build()

            soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0 && sampleId == soundId) {
                    isLoaded.set(true)
                    Log.d(TAG, "⚡ 唤醒毫秒级反馈音加载就绪 (soundId=$soundId)")
                } else {
                    Log.w(TAG, "⚠️ 唤醒音效加载失败 status=$status, sampleId=$sampleId")
                }
            }

            soundId = soundPool?.load(context, R.raw.kws_ding, 1) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "初始化 SoundPool 失败: ${e.message}", e)
        }
    }

    /**
     * 瞬间播放短促“滴”声
     * @param volume 音量 (0.0f ~ 1.0f)
     */
    fun playDing(volume: Float = 1.0f) {
        val sp = soundPool ?: return
        if (!isLoaded.get() || soundId == 0) {
            Log.w(TAG, "音效尚未加载完毕，跳过播放")
            return
        }
        try {
            sp.play(soundId, volume, volume, 1, 0, 1.0f)
            Log.d(TAG, "🔔 [SoundPool] 毫秒级触发唤醒反馈音！")
        } catch (e: Exception) {
            Log.e(TAG, "播放唤醒音失败: ${e.message}", e)
        }
    }

    /**
     * 释放 SoundPool 音频资源
     */
    fun release() {
        try {
            isLoaded.set(false)
            soundPool?.release()
            soundPool = null
            soundId = 0
            Log.d(TAG, "SoundPool 已安全释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放 SoundPool 异常: ${e.message}", e)
        }
    }
}
