import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/CallScreen.kt', 'r') as f:
    content = f.read()

# Add imports
if "cn.xxstudy.assistant.data.AppSettings" not in content:
    content = content.replace("import cn.xxstudy.assistant.viewmodel.MainViewModel", "import cn.xxstudy.assistant.viewmodel.MainViewModel\nimport cn.xxstudy.assistant.data.AppSettings\nimport androidx.compose.animation.AnimatedVisibility\nimport androidx.compose.animation.fadeIn\nimport androidx.compose.animation.fadeOut")

# Replace variables
old_vars = """    val listeningRms by viewModel.listeningRms.collectAsState()
    val isSpeaking by viewModel.speechManager.isSpeaking.collectAsState()"""
new_vars = """    val listeningRms by viewModel.listeningRms.collectAsState()
    val isSpeaking by viewModel.speechManager.isSpeaking.collectAsState()
    val isCallSubtitleEnabled by AppSettings.isCallSubtitleEnabled.collectAsState()
    val voicePartialText by viewModel.voicePartialText.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isListening by viewModel.isListening.collectAsState()"""
content = content.replace(old_vars, new_vars)

# Replace animation
old_anim = """    // 基于录音音量的动态大小
    val targetRmsScale = 1f + (listeningRms * 3f).coerceIn(0f, 1f)
    val rmsScale by animateFloatAsState(
        targetValue = targetRmsScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "rms_scale"
    )"""
new_anim = """    // 基于录音音量的动态大小 (平滑物理阻尼)
    val normalizedRms = ((listeningRms - 35f) / 45f).coerceIn(0f, 1f)
    val targetRmsScale = 1f + (normalizedRms * 0.8f)
    val rmsScale by animateFloatAsState(
        targetValue = targetRmsScale,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "rms_scale"
    )"""
content = content.replace(old_anim, new_anim)

# Insert subtitle box after state Text
old_text = """            // 状态文本
            Text(
                text = if (isSpeaking) "正在说话..." else "倾听中，请说话...",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 40.dp)
            )"""
new_text = old_text + """

            if (isCallSubtitleEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (isListening && !voicePartialText.isNullOrEmpty()) {
                        Text(
                            text = voicePartialText!! + "...",
                            color = Color.White,
                            fontSize = 18.sp,
                            lineHeight = 28.sp
                        )
                    } else {
                        val lastAiMsg = chatMessages.lastOrNull { !it.isUser }
                        if (lastAiMsg != null) {
                            Text(
                                text = lastAiMsg.text,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 18.sp,
                                lineHeight = 28.sp
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }"""
content = content.replace(old_text, new_text)

# Remove the old Spacer
content = content.replace("            Spacer(modifier = Modifier.weight(1f))\n\n            // 挂断按钮", "            // 挂断按钮")

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/CallScreen.kt', 'w') as f:
    f.write(content)

print("Patched CallScreen")
