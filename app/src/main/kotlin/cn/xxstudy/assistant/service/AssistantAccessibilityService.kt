package cn.xxstudy.assistant.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * 语音助手系统级无障碍辅助服务
 * 用于执行系统级全局截屏等原生快捷操作
 */
class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AssistantAccService"
        private var instanceRef: WeakReference<AssistantAccessibilityService>? = null

        val isRunning: Boolean
            get() = instanceRef?.get() != null

        /**
         * 执行系统级屏幕截屏（Android 9.0+ 原生支持）
         * 触发后系统将自动截取全屏、展示截屏缩略动画并保存至系统相册
         */
        fun takeScreenshot(): Boolean {
            val service = instanceRef?.get()
            if (service == null) {
                Log.w(TAG, "AccessibilityService 未运行，无法执行截屏")
                return false
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val success = service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                Log.i(TAG, "performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) result: $success")
                success
            } else {
                Log.w(TAG, "当前系统版本低于 Android 9.0，不支持系统级全局截屏")
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        Log.i(TAG, "AssistantAccessibilityService 已连接并就绪")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅用于系统级动作分发，无需拦截 UI 事件
    }

    override fun onInterrupt() {
        Log.w(TAG, "AssistantAccessibilityService 中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instanceRef?.get() == this) {
            instanceRef = null
        }
        Log.i(TAG, "AssistantAccessibilityService 已销毁")
    }
}
