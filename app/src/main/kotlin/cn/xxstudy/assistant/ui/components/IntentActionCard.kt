package cn.xxstudy.assistant.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.xxstudy.assistant.intent.ActionParser
import cn.xxstudy.assistant.intent.ActionRegistry
import cn.xxstudy.assistant.intent.ActionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 兼容层别名定义与门面对象，保证既有调用代码零感知平滑迁移
 */
typealias ParsedAction = ActionRequest

object IntentParser {
    fun parse(text: String): List<ActionRequest>? = ActionParser.parse(text)
    fun formatForSpeech(actions: List<ActionRequest>): String = ActionRegistry.formatSpeech(actions)
}

object ActionExecutor {
    fun execute(context: Context, item: ActionRequest, overrideState: String? = null): String {
        var msg = "已提交执行"
        CoroutineScope(Dispatchers.Main).launch {
            val result = ActionRegistry.execute(context, item, overrideState)
            msg = result.message
        }
        return msg
    }
}

/**
 * 通用意图指令卡片 (Dumb Component)
 *
 * 职责彻底纯化为纯 UI 展示与用户交互，所有具体能力的分发、调度、文案生成均交由 [ActionRegistry]。
 */
@Composable
fun IntentActionCard(
    actions: List<ActionRequest>,
    rawText: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var showRawJson by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 顶部标题栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "端侧意图识别中枢",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                actions.forEach { ActionRegistry.execute(context, it) }
                                Toast.makeText(context, "已重新执行 ${actions.size} 项指令", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "已自动执行",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.8.dp
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 动态指令条目渲染列表
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { index, item ->
                    ActionItemRow(index = index + 1, item = item)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 底部折叠查看原始报文
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showRawJson = !showRawJson }
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            ) {
                Icon(
                    imageVector = if (showRawJson) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (showRawJson) "收起原始 JSON" else "查看原始 JSON 报文",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(visible = showRawJson) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Text(
                        text = rawText.trim(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionItemRow(index: Int, item: ActionRequest) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var activeState by remember(item) {
        mutableStateOf(item.optString("state") ?: "on")
    }
    var executionFeedback by remember { mutableStateOf<String?>(null) }

    // 从注册中心动态获取当前 Action 的 UI 描述符，完全无须在此类编写具体业务判断
    val descriptor = remember(item, activeState) {
        ActionRegistry.getUiDescriptor(item, activeState)
    }

    val isOn = descriptor.isOn

    fun doExecute() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val nextState = if (descriptor.isToggleable) {
                if (isOn) descriptor.toggleOffState else descriptor.toggleOnState
            } else null

            val result = ActionRegistry.execute(context, item, overrideState = nextState)
            if (nextState != null) {
                activeState = nextState
            }
            executionFeedback = result.message
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isOn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        border = if (isOn) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                 else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { doExecute() }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isOn) descriptor.iconTint.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = descriptor.icon,
                    contentDescription = null,
                    tint = if (isOn) descriptor.iconTint else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = descriptor.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = descriptor.summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                if (executionFeedback != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "✓ $executionFeedback",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (descriptor.isToggleable) {
                FilledTonalButton(
                    onClick = { doExecute() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isOn) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                         else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = if (isOn) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isOn) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (isOn) "关闭" else "打开",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = { doExecute() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("再次执行", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
