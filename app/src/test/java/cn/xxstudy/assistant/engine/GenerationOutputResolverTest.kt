package cn.xxstudy.assistant.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationOutputResolverTest {
    @Test
    fun unfinishedThinkingNeverBecomesASilentEmptyAnswer() {
        val parser = ThinkingStreamParser()
        parser.feed("<|thought_begin|>")
        parser.feed("这里只是未完成的思考内容")
        parser.finish()
        val snapshot = parser.getSnapshot()

        val answer = GenerationOutputResolver.resolve(
            answerText = snapshot.answerText,
            thinkingText = snapshot.thinkingText
        )

        assertTrue(answer.isNotBlank())
        assertTrue(answer.contains("生成上限"))
        assertFalse(answer.contains("未完成的思考内容"))
    }
}
