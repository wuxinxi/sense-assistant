package cn.xxstudy.assistant.utils

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.FileReader

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
    private var lastTotalTime = 0L
    private var lastIdleTime = 0L

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
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
        try {
            val statFile = java.io.File("/proc/stat")
            if (!statFile.canRead()) return 0
            val reader = BufferedReader(FileReader(statFile))
            val line = reader.readLine()
            reader.close()
            
            if (line != null) {
                val toks = line.split("\\s+".toRegex()).drop(1).filter { it.isNotEmpty() }
                if (toks.size >= 4) {
                    val idle = toks[3].toLong()
                    var total = 0L
                    for (i in 0 until toks.size) {
                        total += toks[i].toLong()
                    }
                    
                    if (lastTotalTime != 0L) {
                        val totalDiff = total - lastTotalTime
                        val idleDiff = idle - lastIdleTime
                        if (totalDiff > 0) {
                            val usage = (totalDiff - idleDiff).toFloat() / totalDiff * 100f
                            lastTotalTime = total
                            lastIdleTime = idle
                            return usage.toInt().coerceIn(0, 100)
                        }
                    }
                    lastTotalTime = total
                    lastIdleTime = idle
                }
            }
        } catch (ignored: Exception) {
            // Android 8.0+ (API 26+) 限制普通应用访问 /proc/stat，静默捕获避免日志刷屏
        }
        return 0
    }

    private fun getMemoryStatus(context: Context): Triple<Int, Int, Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        
        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val availMb = (memInfo.availMem / (1024 * 1024)).toInt()

        // Get app PSS memory
        val pids = intArrayOf(android.os.Process.myPid())
        val processMemoryInfo = am.getProcessMemoryInfo(pids)
        val appUsedMb = if (processMemoryInfo.isNotEmpty()) {
            processMemoryInfo[0].totalPss / 1024
        } else {
            0
        }
        
        return Triple(appUsedMb, availMb, totalMb)
    }
}
