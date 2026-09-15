import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/MainScreen.kt', 'r') as f:
    content = f.read()

old_call = """            CallScreen(
                viewModel = viewModel,
                onClose = {
                    isCallScreenVisible = false
                    viewModel.stopPhoneMode()
                }
            )"""
new_call = """            CallScreen(
                viewModel = viewModel,
                onClose = {
                    isCallScreenVisible = false
                    viewModel.stopPhoneMode()
                },
                onMinimize = {
                    isCallScreenVisible = false
                }
            )"""
content = content.replace(old_call, new_call)

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/MainScreen.kt', 'w') as f:
    f.write(content)
print("Patched MainScreen call")
