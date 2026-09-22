package cn.xxstudy.assistant.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.string.RichTextStringStyle

@Composable
fun TestRt() {
    val a = RichTextStyle(
        codeBlockStyle = CodeBlockStyle(modifier = Modifier, textStyle = androidx.compose.ui.text.TextStyle()),
        stringStyle = RichTextStringStyle(codeStyle = SpanStyle(background = Color.Red))
    )
}
