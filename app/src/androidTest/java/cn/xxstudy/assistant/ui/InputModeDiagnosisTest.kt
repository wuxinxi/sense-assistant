package cn.xxstudy.assistant.ui

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import cn.xxstudy.assistant.ui.components.DoubaoInputBar
import org.junit.Rule
import org.junit.Test

/** Isolated input bar; never launches MainViewModel or reads/writes conversation/knowledge databases. */
class InputModeDiagnosisTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun realKeyboardCanOpenAndCloseRepeatedly() {
        lateinit var inputView: View
        var imeVisible = false
        compose.setContent {
            inputView = LocalView.current
            imeVisible = WindowInsets.isImeVisible
            MaterialTheme {
                DoubaoInputBar(inputText = "", onInputTextChange = {}, onCameraClick = {},
                    onPlusClick = {}, onSendClick = {}, isPressingVoice = false,
                    onVoiceDown = {}, onVoiceMove = {}, onVoiceUp = {})
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { inputView.hasWindowFocus() }
        repeat(5) {
            compose.onNodeWithText("发消息或按住说话...").performTouchInput { click() }
            compose.waitUntil(timeoutMillis = 8_000) { imeVisible }
            compose.onNodeWithContentDescription("切换为语音").performClick()
            compose.waitUntil(timeoutMillis = 8_000) { !imeVisible }
            compose.waitForIdle()
        }
    }
}
