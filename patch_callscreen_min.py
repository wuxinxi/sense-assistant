import re

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/CallScreen.kt', 'r') as f:
    content = f.read()

# Update signature
old_sig = """fun CallScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {"""
new_sig = """fun CallScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onMinimize: () -> Unit
) {"""
content = content.replace(old_sig, new_sig)

# Add minimize button
old_column = """        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {"""
new_column = """        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onMinimize) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                        contentDescription = "最小化",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }"""
content = content.replace(old_column, new_column)

if "import androidx.compose.material.icons.filled.KeyboardArrowDown" not in content:
    content = content.replace("import androidx.compose.material.icons.filled.CallEnd", "import androidx.compose.material.icons.filled.CallEnd\nimport androidx.compose.material.icons.filled.KeyboardArrowDown")

# Remove top Spacer
content = content.replace("            Spacer(modifier = Modifier.height(100.dp))", "            Spacer(modifier = Modifier.height(40.dp))")

with open('app/src/main/kotlin/cn/xxstudy/assistant/ui/screens/CallScreen.kt', 'w') as f:
    f.write(content)
print("Patched CallScreen minimize")
