package cn.xxstudy.assistant.intent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 泛化意图指令请求模型
 *
 * 彻底废弃以往写死 17 个属性的硬编码做法，采用参数字典 (params: Map<String, Any?>)，
 * 支持大模型输出任意自定义属性，实现模型与端侧彻底解耦。
 */
data class ActionRequest(
    val action: String,
    val params: Map<String, Any?> = emptyMap(),
    val rawJson: String = ""
) {
    /**
     * 安全提取字符串参数（支持别名降级查找）
     */
    fun optString(vararg keys: String): String? {
        for (key in keys) {
            val v = params[key]?.toString()?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    /**
     * 安全提取整型参数
     */
    fun optInt(vararg keys: String): Int? {
        for (key in keys) {
            val v = params[key]
            if (v is Number) return v.toInt()
            val parsed = v?.toString()?.trim()?.toIntOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * 安全提取布尔参数
     */
    fun optBoolean(vararg keys: String, default: Boolean = false): Boolean {
        for (key in keys) {
            val v = params[key]
            if (v is Boolean) return v
            val parsed = v?.toString()?.trim()?.toBooleanStrictOrNull()
            if (parsed != null) return parsed
        }
        return default
    }
}

/**
 * 意图卡片通用 UI 描述符
 *
 * 任何 ActionHandler 都只需向 UI 提供该描述符，UI 组件只管美观渲染，不掺杂任何业务和硬件调度逻辑。
 */
data class ActionUiDescriptor(
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val iconTint: Color = Color(0xFF6366F1),
    val isToggleable: Boolean = false,
    val isOn: Boolean = true,
    val toggleOnState: String = "on",
    val toggleOffState: String = "off",
    val actionButtonText: String? = null,
    val actionButtonIcon: ImageVector? = null,
    val actionOverrideState: String? = null
)

/**
 * 指令执行结果封装
 */
data class ExecutionResult(
    val isSuccess: Boolean,
    val message: String,
    val data: Any? = null
)
