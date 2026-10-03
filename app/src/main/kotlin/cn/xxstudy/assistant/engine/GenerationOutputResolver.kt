package cn.xxstudy.assistant.engine

/** Converts incomplete generations into a visible result without leaking chain-of-thought. */
object GenerationOutputResolver {
    fun resolve(answerText: String, thinkingText: String?): String {
        if (answerText.isNotBlank()) return answerText
        if (!thinkingText.isNullOrBlank()) {
            return "模型本轮思考已达生成上限，尚未输出正文。请重试，或关闭深度思考后再问。"
        }
        return "（回复为空）"
    }
}
