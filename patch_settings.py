import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/SettingsScreen.kt', 'r') as f:
    content = f.read()

content = content.replace("val isPhoneModeEnabled by AppSettings.isPhoneModeEnabled.collectAsState()", "val isPhoneModeEnabled by AppSettings.isPhoneModeEnabled.collectAsState()\n    val isCallSubtitleEnabled by AppSettings.isCallSubtitleEnabled.collectAsState()")

phone_switch = """                SettingItemRow(
                    icon = Icons.Default.PhoneCallback,
                    iconBgColor = Color(0xFFE91E63),
                    title = "电话模式 (全双工对讲)",
                    subtitle = if (isPhoneModeEnabled) "已开启。在聊天框可见 📞 图标" else "开启后可像打电话一样与 AI 持续交流，支持语音随时打断",
                    trailing = {
                        Switch(
                            checked = isPhoneModeEnabled,
                            onCheckedChange = {
                                triggerHaptic()
                                AppSettings.setPhoneModeEnabled(it)
                            }
                        )
                    }
                )"""
subtitle_switch = phone_switch + """
                SettingRowDivider()
                SettingItemRow(
                    icon = Icons.Default.Subtitles,
                    iconBgColor = Color(0xFF673AB7),
                    title = "电话模式字幕",
                    subtitle = "在全屏电话界面显示对话文本",
                    trailing = {
                        Switch(
                            checked = isCallSubtitleEnabled,
                            onCheckedChange = {
                                triggerHaptic()
                                AppSettings.setCallSubtitleEnabled(it)
                            }
                        )
                    }
                )"""
content = content.replace(phone_switch, subtitle_switch)

if "import androidx.compose.material.icons.filled.Subtitles" not in content:
    content = content.replace("import androidx.compose.material.icons.filled.PhoneCallback", "import androidx.compose.material.icons.filled.PhoneCallback\nimport androidx.compose.material.icons.filled.Subtitles")

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/SettingsScreen.kt', 'w') as f:
    f.write(content)
print("Patched SettingsScreen")
