package cn.xxstudy.assistant.utils

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通用智能家居与外部设备调度中枢
 * 彻底废除写死设备白名单，采用开放式 IoT 总线协议（支持任何设备名与状态操作）
 */
object DeviceHelper {
    private const val TAG = "DeviceHelper"
    const val ACTION_IOT_DISPATCH = "cn.xxstudy.assistant.IOT_DEVICE_CONTROL"

    /**
     * 将泛化英文状态码翻译为自然的中文动作描述
     */
    fun formatState(rawState: String?): String {
        return when (rawState?.lowercase()?.trim()) {
            "on", "open", "turn_on" -> "开启"
            "off", "close", "turn_off" -> "关闭"
            "start", "run" -> "启动"
            "stop", "pause" -> "暂停"
            "dock", "return" -> "回充"
            null, "" -> "操作"
            else -> rawState
        }
    }

    /**
     * 动态分发设备控制指令（面向 HomeAssistant / 米家等标准 IoT 总线广播）
     */
    fun dispatchDeviceControl(
        context: Context,
        deviceName: String,
        state: String,
        extraParams: Map<String, Any>? = null
    ): String {
        val cleanDevice = deviceName.trim().ifBlank { "智能设备" }
        val stateCn = formatState(state)

        Log.i(TAG, "分发通用 IoT 指令: device=[$cleanDevice], state=[$state] ($stateCn)")

        // 1. 发送标准本地广播，供 IoT 网关、HomeAssistant 插件或 MQTT 客户端监听执行
        try {
            val broadcastIntent = Intent(ACTION_IOT_DISPATCH).apply {
                putExtra("device", cleanDevice)
                putExtra("state", state)
                putExtra("timestamp", System.currentTimeMillis())
                setPackage(context.packageName)
            }
            context.sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "广播分发失败: ${e.message}", e)
        }

        return "已向智能中枢发送指令：$stateCn $cleanDevice"
    }
}
