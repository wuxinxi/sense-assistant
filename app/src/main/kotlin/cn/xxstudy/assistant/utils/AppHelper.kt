package cn.xxstudy.assistant.utils

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 手机本地应用动态反射与启动工具类
 * 彻底废除硬编码白名单，通过 PackageManager 动态枚举当前手机真实安装的全部可启动应用
 */
object AppHelper {
    private const val TAG = "AppHelper"

    data class InstalledApp(
        val label: String,
        val packageName: String,
        val activityName: String
    )

    // 常用自然语言中英/别名泛化对照（仅作为口语别名辅助，绝不写死或限制包名）
    private val COMMON_ALIASES = mapOf(
        "wechat" to listOf("微信"),
        "alipay" to listOf("支付宝"),
        "douyin" to listOf("抖音"),
        "tiktok" to listOf("抖音"),
        "kuaishou" to listOf("快手"),
        "redbook" to listOf("小红书"),
        "xiaohongshu" to listOf("小红书"),
        "bilibili" to listOf("哔哩哔哩", "b站"),
        "amap" to listOf("高德地图"),
        "baidumap" to listOf("百度地图"),
        "meituan" to listOf("美团"),
        "taobao" to listOf("淘宝"),
        "jd" to listOf("京东"),
        "pinduoduo" to listOf("拼多多"),
        "neteasemusic" to listOf("网易云音乐", "网易云"),
        "qqmusic" to listOf("qq音乐"),
        "weibo" to listOf("微博"),
        "dingtalk" to listOf("钉钉"),
        "feishu" to listOf("飞书", "lark"),
        "zhihu" to listOf("知乎"),
        "camera" to listOf("相机", "系统相机"),
        "gallery" to listOf("相册", "图库"),
        "settings" to listOf("设置", "系统设置"),
        "browser" to listOf("浏览器"),
        "calculator" to listOf("计算器"),
        "notes" to listOf("便签", "备忘录", "笔记")
    )

    /**
     * 实时动态获取当前设备中所有可在桌面启动的应用列表
     */
    fun getInstalledLauncherApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(intent, 0)
        return resolveList.mapNotNull { resolveInfo ->
            val act = resolveInfo.activityInfo ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(pm).toString()
            InstalledApp(
                label = label,
                packageName = act.packageName,
                activityName = act.name
            )
        }
    }

    /**
     * 根据用户说出的名称动态模糊匹配已安装应用
     */
    fun findApp(context: Context, query: String): InstalledApp? {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return null

        val installedList = getInstalledLauncherApps(context)
        if (installedList.isEmpty()) {
            Log.w(TAG, "未获取到任何已安装应用")
            return null
        }

        // 1. 中文桌面名称完全匹配（不区分大小写）
        installedList.firstOrNull { it.label.equals(cleanQuery, ignoreCase = true) }?.let {
            Log.i(TAG, "完全匹配命中应用: ${it.label} (${it.packageName})")
            return it
        }

        // 2. 别名反查匹配（例如输入 "wechat" 对应到 "微信"）
        val candidateAliases = COMMON_ALIASES[cleanQuery] ?: emptyList()
        for (alias in candidateAliases) {
            installedList.firstOrNull { it.label.contains(alias, ignoreCase = true) }?.let {
                Log.i(TAG, "别名匹配命中应用: query=$cleanQuery -> alias=$alias -> ${it.label}")
                return it
            }
        }

        // 3. 包含/子串匹配（例如用户说“网易云”，匹配“网易云音乐”；或者用户说“剪映专业版”，匹配“剪映”）
        installedList.firstOrNull {
            val l = it.label.lowercase()
            l.contains(cleanQuery) || cleanQuery.contains(l)
        }?.let {
            Log.i(TAG, "子串匹配命中应用: ${it.label} (${it.packageName})")
            return it
        }

        // 4. 包名子串匹配（作为极偏门英文代号兜底）
        installedList.firstOrNull { it.packageName.lowercase().contains(cleanQuery) }?.let {
            Log.i(TAG, "包名匹配命中应用: ${it.label} (${it.packageName})")
            return it
        }

        Log.w(TAG, "手机上未找到匹配“$query”的应用")
        return null
    }

    /**
     * 执行启动应用
     */
    fun launchApp(context: Context, app: InstalledApp): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(app.packageName, app.activityName)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return try {
            context.startActivity(intent)
            Log.i(TAG, "已成功拉起应用: ${app.label} (${app.packageName})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "拉起应用失败: ${e.message}", e)
            false
        }
    }
}
