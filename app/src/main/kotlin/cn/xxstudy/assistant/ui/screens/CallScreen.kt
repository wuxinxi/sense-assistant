package cn.xxstudy.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.xxstudy.assistant.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val listeningRms by viewModel.listeningRms.collectAsState()
    val isSpeaking by viewModel.speechManager.isSpeaking.collectAsState()

    // 呼吸动画
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_scale"
    )

    // 基于录音音量的动态大小
    val targetRmsScale = 1f + (listeningRms * 3f).coerceIn(0f, 1f)
    val rmsScale by animateFloatAsState(
        targetValue = targetRmsScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "rms_scale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF1E1E1E) // 深色磨砂质感背景
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(100.dp))

            // 中心动效区
            Box(
                modifier = Modifier
                    .size(200.dp),
                contentAlignment = Alignment.Center
            ) {
                // 外圈涟漪
                val primaryColor = if (isSpeaking) Color(0xFF00BCD4) else Color(0xFF4CAF50)
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scale = if (isSpeaking) breathingScale else rmsScale
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.2f),
                        radius = size.minDimension / 2 * scale * 1.2f,
                        center = center
                    )
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.4f),
                        radius = size.minDimension / 2 * scale * 0.9f,
                        center = center
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = size.minDimension / 2 * 0.6f,
                        center = center
                    )
                }
            }

            // 状态文本
            Text(
                text = if (isSpeaking) "正在说话..." else "倾听中，请说话...",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 40.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // 挂断按钮
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .padding(bottom = 80.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935))
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "挂断",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}
