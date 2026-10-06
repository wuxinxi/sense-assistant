package cn.xxstudy.assistant.utils

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.Process
import android.os.SystemClock
import android.os.Debug

data class DeviceMetrics(
    val cpuPercent: Int = 0,
    val appRamMb: Int = 0,
    val availRamMb: Int = 0,
    val totalRamMb: Int = 0
)

object PerformanceMonitor {
    private val _metrics = MutableStateFlow(DeviceMetrics())
    val metrics: StateFlow<DeviceMetrics> = _metrics.asStateFlow()

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastCpuTime = 0L
    private var lastWallTime = 0L

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        lastCpuTime = Process.getElapsedCpuTime()
        lastWallTime = SystemClock.elapsedRealtime()
        job = scope.launch {
            while (isActive) {
                val cpu = getCpuUsage()
                val (appRam, availRam, totalRam) = getMemoryStatus(appContext)
                _metrics.value = DeviceMetrics(cpu, appRam, availRam, totalRam)
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun getCpuUsage(): Int {
        // 公共 Android API 可读取本进程累计 CPU 时间，不依赖受限的 /proc/stat。
        // 以整机逻辑核容量为 100%，例如 8 核设备使用 4 个满载核约为 50%。
        val cpuTime = Process.getElapsedCpuTime()
        val wallTime = SystemClock.elapsedRealtime()
        val cpuDelta = (cpuTime - lastCpuTime).coerceAtLeast(0L)
        val wallDelta = wallTime - lastWallTime
        lastCpuTime = cpuTime
        lastWallTime = wallTime
        if (wallDelta <= 0L) return 0
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return (100.0 * cpuDelta / wallDelta / cores).toInt().coerceIn(0, 100)
    }

    private fun getMemoryStatus(context: Context): Triple<Int, Int, Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        
        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val availMb = (memInfo.availMem / (1024 * 1024)).toInt()

        // ActivityManager throttles repeated memory-info requests on recent
        // Android versions and may keep returning the pre-model-load sample.
        val appUsedMb = (Debug.getPss() / 1024).toInt()
        
        return Triple(appUsedMb, availMb, totalMb)
    }
}
