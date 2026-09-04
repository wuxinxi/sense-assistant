package cn.xxstudy.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * 辅助手势 Modifier：精确区分单击与长按拖拽
 * - 300ms 内松开手指：判定为单击 (onTap)
 * - 超过 300ms 未松手：判定为长按 (onVoiceDown)，并随手指位移持续回调 (onVoiceMove)，松手时触发 (onVoiceUp)
 */
fun Modifier.tapOrHoldToTalk(
    enabled: Boolean = true,
    onTap: () -> Unit,
    onVoiceDown: () -> Unit,
    onVoiceMove: (deltaY: Float) -> Unit,
    onVoiceUp: () -> Unit
): Modifier = if (!enabled) this else this.pointerInput(enabled) {
    val longPressTimeout = 300L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startY = down.position.y
        var fingerLifted = false

        // 等待 300ms 内是否有抬手事件
        withTimeoutOrNull(longPressTimeout) {
            while (true) {
                val e = awaitPointerEvent()
                val change = e.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    fingerLifted = true
                    break
                }
            }
        }

        if (fingerLifted) {
            // 300ms 内抬手 -> 单击
            onTap()
            return@awaitEachGesture
        }

        // 超过 300ms 手指仍在屏幕上 -> 长按说话开始！
        onVoiceDown()

        // 持续跟踪拖拽位移与最终松手
        while (true) {
            val e = awaitPointerEvent()
            val change = e.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                onVoiceUp()
                break
            }
            val deltaY = change.position.y - startY
            onVoiceMove(deltaY)
            change.consume()
        }
    }
}

/**
 * 豆包精修版声波图标: (·))
 * 外圆 26dp，圆圈内部包含小圆点及两条向右扩散的同心圆弧，紧致精致
 */
@Composable
fun DoubaoVoiceIcon(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    isPressed: Boolean = false
) {
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voiceIconScale"
    )

    Canvas(
        modifier = modifier.size(26.dp)
    ) {
        val strokeWidth = 1.8.dp.toPx()
        val radius = (size.minDimension / 2f) * scale - strokeWidth / 2f
        val center = this.center

        // 外圆环
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        // 中心偏左的原点
        val dotCenter = Offset(center.x - 2.8.dp.toPx(), center.y)
        drawCircle(
            color = color,
            radius = 1.6.dp.toPx(),
            center = dotCenter
        )

        // 第一道声波弧线
        val arcR1 = 4.8.dp.toPx()
        drawArc(
            color = color,
            startAngle = -48f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(dotCenter.x - arcR1, dotCenter.y - arcR1),
            size = Size(arcR1 * 2, arcR1 * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // 第二道声波弧线
        val arcR2 = 8.0.dp.toPx()
        drawArc(
            color = color,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(dotCenter.x - arcR2, dotCenter.y - arcR2),
            size = Size(arcR2 * 2, arcR2 * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

/**
 * 豆包精修版加号图标: (+)
 * 外圆 26dp，线宽 1.8dp，与声波图标严格对称统一
 */
@Composable
fun DoubaoPlusIcon(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(
        modifier = modifier.size(26.dp)
    ) {
        val strokeWidth = 1.8.dp.toPx()
        val radius = size.minDimension / 2f - strokeWidth / 2f
        val center = this.center

        // 外圆环
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        // 加号十字线 (长度 9.6dp)
        val lineLen = 4.8.dp.toPx()
        // 横线
        drawLine(
            color = color,
            start = Offset(center.x - lineLen, center.y),
            end = Offset(center.x + lineLen, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        // 竖线
        drawLine(
            color = color,
            start = Offset(center.x, center.y - lineLen),
            end = Offset(center.x, center.y + lineLen),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 豆包横向多胶囊动态声浪波形阵列
 * 横向排布 36 根小胶囊微柱，随 RMS 实时律动，两端平滑微光渐隐
 */
@Composable
fun DoubaoWaveformBar(
    rms: Float,
    isCancel: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 36
    val midIndex = barCount / 2f

    val animatedRms by animateFloatAsState(
        targetValue = rms.coerceIn(0f, 100f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "waveformRms"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val distFromCenter = abs(i - midIndex) / midIndex
            val centerMultiplier = (1.0f - distFromCenter * 0.75f).coerceIn(0.2f, 1.0f)
            val sineWave = (sin(i * 0.8f) * 0.25f + 1f).toFloat()

            val dynamicHeight = if (isCancel) {
                5.dp
            } else {
                val amplitude = (animatedRms / 100f) * 22f * centerMultiplier * sineWave
                (5f + amplitude).coerceIn(5f, 28f).dp
            }

            val alpha = (1.0f - distFromCenter.pow(3.5f) * 0.85f).coerceIn(0.18f, 1.0f)

            Box(
                modifier = Modifier
                    .padding(horizontal = 1.5.dp)
                    .width(3.2.dp)
                    .height(dynamicHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = alpha))
            )
        }
    }
}

/**
 * 豆包按住录音展开的沉浸式声浪面板
 */
@Composable
fun DoubaoVoicePanel(
    visible: Boolean,
    isCancel: Boolean,
    rms: Float,
    partialText: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        val gradientColors = if (isCancel) {
            listOf(
                Color.Transparent,
                Color(0x33D32F2F),
                Color(0xBBE53935),
                Color(0xFFC62828)
            )
        } else {
            listOf(
                Color.Transparent,
                Color(0x33007AFF),
                Color(0xBB0066EE),
                Color(0xFF0052CC)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .background(Brush.verticalGradient(gradientColors))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 顶部：转写预览半透明气泡
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.18f),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, Color.White.copy(alpha = 0.35f)),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = if (!partialText.isNullOrBlank()) partialText else "正在聆听语音...",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                // 中部：指引文案
                Text(
                    text = if (isCancel) "松开手指，取消发送" else "松手发送，上移取消",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = if (isCancel) FontWeight.Bold else FontWeight.Medium
                )

                // 底部：动感声浪柱状条
                DoubaoWaveformBar(
                    rms = rms,
                    isCancel = isCancel,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
            }
        }
    }
}

/**
 * 豆包风格底部一体化白色胶囊输入栏 (精修尺寸比例版)
 * - 左侧相机 23dp
 * - 中间无框文本框
 * - 右侧语音图标 26dp、加号图标 26dp，触控热区 40dp
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoubaoInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onCameraClick: () -> Unit,
    onPlusClick: () -> Unit,
    onSendClick: () -> Unit,
    isPressingVoice: Boolean,
    onVoiceDown: () -> Unit,
    onVoiceMove: (deltaY: Float) -> Unit,
    onVoiceUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val isImeVisible = WindowInsets.isImeVisible

    // 显式打字模式标志
    var isExplicitKeyboardMode by remember { mutableStateOf(false) }
    val isKeyboardActive = isExplicitKeyboardMode || isImeVisible

    // 当软键盘由于外部动作收起时，自动复位为语音待命状态
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible && isExplicitKeyboardMode) {
            isExplicitKeyboardMode = false
            focusManager.clearFocus()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：拍照图标 (23dp 视觉，40dp 点击热区)
            IconButton(
                onClick = onCameraClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "拍照",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(23.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 中间：输入文本框 (无边框一体化)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .then(
                        if (!isKeyboardActive) {
                            // 键盘未唤起时：单击弹键盘，长按录音说话
                            Modifier.tapOrHoldToTalk(
                                enabled = true,
                                onTap = {
                                    isExplicitKeyboardMode = true
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                },
                                onVoiceDown = onVoiceDown,
                                onVoiceMove = onVoiceMove,
                                onVoiceUp = onVoiceUp
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (isKeyboardActive) "输入消息..." else "发消息或按住说话...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        fontSize = 15.sp
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    readOnly = !isKeyboardActive
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 右侧按钮区
            if (inputText.isNotBlank()) {
                // 有输入文字时显示发送按钮 (34dp 胶囊圆形按钮)
                FilledIconButton(
                    onClick = onSendClick,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(17.dp)
                    )
                }
            } else {
                // 无输入文字时
                if (isKeyboardActive) {
                    // 键盘模式：单击键盘图标变回语音模式
                    IconButton(
                        onClick = {
                            isExplicitKeyboardMode = false
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "切换为语音",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    // 语音模式：精修 26dp 声波图标，40dp 手势交互热区
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .tapOrHoldToTalk(
                                enabled = true,
                                onTap = {
                                    isExplicitKeyboardMode = true
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                },
                                onVoiceDown = onVoiceDown,
                                onVoiceMove = onVoiceMove,
                                onVoiceUp = onVoiceUp
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        DoubaoVoiceIcon(
                            color = if (isPressingVoice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            isPressed = isPressingVoice
                        )
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                // 加号更多按钮 (精修 26dp 对齐加号，40dp 点击热区)
                IconButton(
                    onClick = onPlusClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    DoubaoPlusIcon(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
