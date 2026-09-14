package cn.xxstudy.kws

/**
 * [KwsConfig]
 * 离线语音唤醒核心配置
 *
 * @param defaultKeyword 默认唤醒词（默认“小乐助”）
 * @param threshold 唤醒判定置信度阈值（0.0 ~ 1.0，推荐 0.25 ~ 0.40）
 * @param score 触发基础分（默认 1.2f）
 * @param numThreads 推理线程数（2G 内存设备推荐单线程 1，避免多线程 CPU 争抢）
 * @param sampleRate 音频采样率（固定 16000Hz）
 * @param enableDingSound 是否在检测到唤醒词时瞬间通过 SoundPool 播放“滴”提示音
 */
data class KwsConfig(
    val defaultKeyword: String = "小乐助",
    val threshold: Float = 0.30f,
    val score: Float = 1.2f,
    val numThreads: Int = 1,
    val sampleRate: Int = 16000,
    val enableDingSound: Boolean = true,
    val customModelDir: String? = null
)
