import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/components/DoubaoVoiceComponents.kt', 'r') as f:
    content = f.read()

# Add isActiveCall parameter
old_sig = """fun DoubaoInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onCameraClick: () -> Unit,
    onPhoneClick: (() -> Unit)? = null,
    onPlusClick: () -> Unit,
    onSendClick: () -> Unit,
    isPressingVoice: Boolean,
    onVoiceDown: () -> Unit,
    onVoiceMove: (Offset) -> Unit,
    onVoiceUp: () -> Unit
) {"""
new_sig = """fun DoubaoInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onCameraClick: () -> Unit,
    onPhoneClick: (() -> Unit)? = null,
    isActiveCall: Boolean = false,
    onPlusClick: () -> Unit,
    onSendClick: () -> Unit,
    isPressingVoice: Boolean,
    onVoiceDown: () -> Unit,
    onVoiceMove: (Offset) -> Unit,
    onVoiceUp: () -> Unit
) {"""
content = content.replace(old_sig, new_sig)

# Change icon and color
old_icon = """                if (onPhoneClick != null) {
                    IconButton(
                        onClick = onPhoneClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneCallback,
                            contentDescription = "电话模式",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                }"""
new_icon = """                if (onPhoneClick != null) {
                    IconButton(
                        onClick = onPhoneClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isActiveCall) androidx.compose.material.icons.Icons.Default.CallEnd else androidx.compose.material.icons.Icons.Default.PhoneCallback,
                            contentDescription = if (isActiveCall) "挂断" else "电话模式",
                            tint = if (isActiveCall) androidx.compose.ui.graphics.Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                }"""
content = content.replace(old_icon, new_icon)

if "import androidx.compose.material.icons.filled.CallEnd" not in content:
    content = content.replace("import androidx.compose.material.icons.filled.PhoneCallback", "import androidx.compose.material.icons.filled.PhoneCallback\nimport androidx.compose.material.icons.filled.CallEnd")

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/components/DoubaoVoiceComponents.kt', 'w') as f:
    f.write(content)
print("Patched DoubaoVoiceComponents")
