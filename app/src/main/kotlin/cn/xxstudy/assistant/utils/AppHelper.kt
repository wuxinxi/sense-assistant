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

    // 常用核心国民级应用包名黄金锚点（双保险：精准优先，永不调偏至输入法或克隆插件）
    private val GOLDEN_PACKAGE_ANCHORS = mapOf(
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "美团" to "com.sankuai.meituan",
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "抖音" to "com.ss.android.ugc.aweme",
        "小红书" to "com.xingin.xhs",
        "快手" to "com.smile.gifmaker",
        "哔哩哔哩" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "网易云音乐" to "com.netease.cloudmusic",
        "网易云" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
        "微博" to "com.sina.weibo"
    )

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

        // 1. 核心国民级应用黄金锚点最高优先级命中（彻底杜绝将“微信”识别为“微信输入法”）
        val anchorPkg = GOLDEN_PACKAGE_ANCHORS[cleanQuery]
        if (anchorPkg != null) {
            installedList.firstOrNull { it.packageName.equals(anchorPkg, ignoreCase = true) }?.let {
                Log.i(TAG, "黄金锚点直达命中: ${it.label} (${it.packageName})")
                return it
            }
        }

        // 2. 中文桌面名称严格完全匹配
        installedList.firstOrNull { it.label.trim().equals(cleanQuery, ignoreCase = true) }?.let {
            Log.i(TAG, "完全相等命中应用: ${it.label} (${it.packageName})")
            return it
        }

        // 3. 过滤辅助插件/输入法干扰：如果用户没有明确提到“输入法”，则排除输入法类应用
        val isAskingIme = cleanQuery.contains("输入法") || cleanQuery.contains("键盘") || cleanQuery.contains("ime")
        val candidatePool = if (!isAskingIme) {
            installedList.filter {
                !it.label.contains("输入法") &&
                !it.packageName.contains("input") &&
                !it.packageName.contains("wetype") &&
                !it.packageName.contains("ime")
            }
        } else {
            installedList
        }

        // 4. 别名反查匹配（例如输入 "wechat" 对应到 "微信"）
        val candidateAliases = COMMON_ALIASES[cleanQuery] ?: emptyList()
        for (alias in candidateAliases) {
            candidatePool.firstOrNull { it.label.trim().equals(alias, ignoreCase = true) }?.let {
                Log.i(TAG, "别名完全匹配命中: query=$cleanQuery -> alias=$alias -> ${it.label}")
                return it
            }
            candidatePool.filter { it.label.contains(alias, ignoreCase = true) }
                .minByOrNull { kotlin.math.abs(it.label.length - alias.length) }?.let {
                    Log.i(TAG, "别名模糊匹配命中: query=$cleanQuery -> alias=$alias -> ${it.label}")
                    return it
                }
        }

        // 5. 包含/子串匹配（按名称长度差异升序排序，最短差异最优先，避免被长名称插件抢占）
        val subMatch = candidatePool.filter {
            val l = it.label.lowercase()
            l.contains(cleanQuery) || cleanQuery.contains(l)
        }.minByOrNull { kotlin.math.abs(it.label.length - cleanQuery.length) }

        if (subMatch != null) {
            Log.i(TAG, "子串最佳距离匹配命中应用: ${subMatch.label} (${subMatch.packageName})")
            return subMatch
        }

        // 6. 包名子串匹配（作为极偏门英文代号兜底）
        candidatePool.firstOrNull { it.packageName.lowercase().contains(cleanQuery) }?.let {
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
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
