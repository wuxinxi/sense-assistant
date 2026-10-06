package cn.xxstudy.assistant.rag

import org.junit.Assert.*
import org.junit.Test

class RagSourceReaderTest {
    @Test fun copiesSeparateSourceBlocksWithLanguageAndWhitespaceIntact() {
        val content = "说明\n```dart\nfinal values = [1, 2];\n  print(values[1]);\n```\n~~~yaml\nhive_flutter: ^1.1.0\n~~~"
        val blocks = RagSourceReader.codeBlocks(content)
        assertEquals(listOf("dart", "yaml"), blocks.map { it.language })
        assertEquals("final values = [1, 2];\n  print(values[1]);\n", blocks[0].code)
        assertEquals("hive_flutter: ^1.1.0\n", blocks[1].code)
        assertFalse(blocks[0].code.contains("说明"))
    }

    @Test fun inlineCodeDoesNotCreateACopyCodeButton() {
        assertTrue(RagSourceReader.codeBlocks("调用 `Hive.openBox`。[1]").isEmpty())
    }

    @Test fun quotedAndIndentedCodeAreRecognized() {
        val blocks = RagSourceReader.codeBlocks("> ```dart\n> items[1];\n> ```\n\n    print('hello');\n")
        assertEquals(2, blocks.size)
        assertEquals("items[1];\n", blocks[0].code)
        assertEquals("print('hello');\n", blocks[1].code)
    }
}
