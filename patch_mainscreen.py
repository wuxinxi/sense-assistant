import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/MainScreen.kt', 'r') as f:
    content = f.read()

# Add isActiveCall state
if "val isActiveCall by viewModel.isActiveCall.collectAsState()" not in content:
    content = content.replace("val isPhoneModeEnabled by AppSettings.isPhoneModeEnabled.collectAsState()", "val isPhoneModeEnabled by AppSettings.isPhoneModeEnabled.collectAsState()\n    val isActiveCall by viewModel.isActiveCall.collectAsState()")

# Pass isActiveCall to DoubaoInputBar and change onPhoneClick logic
old_bar = """            DoubaoInputBar(
                inputText = inputText,
                onInputTextChange = { inputText = it },
                onCameraClick = {
                    Toast.makeText(context, "拍照功能暂未开放", Toast.LENGTH_SHORT).show()
                },
                onPhoneClick = if (isPhoneModeEnabled) {
                    { isCallScreenVisible = true; viewModel.startPhoneMode() }


                } else null,

                onPlusClick = {"""
new_bar = """            DoubaoInputBar(
                inputText = inputText,
                onInputTextChange = { inputText = it },
                onCameraClick = {
                    Toast.makeText(context, "拍照功能暂未开放", Toast.LENGTH_SHORT).show()
                },
                onPhoneClick = if (isPhoneModeEnabled) {
                    { 
                        if (isActiveCall) {
                            isCallScreenVisible = false
                            viewModel.stopPhoneMode()
                        } else {
                            isCallScreenVisible = true
                            viewModel.startPhoneMode() 
                        }
                    }
                } else null,
                isActiveCall = isActiveCall,
                onPlusClick = {"""
content = content.replace(old_bar, new_bar)

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/MainScreen.kt', 'w') as f:
    f.write(content)
print("Patched MainScreen")
