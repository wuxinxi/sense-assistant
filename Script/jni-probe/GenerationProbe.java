package cn.xxstudy.assistant.engine;

import java.util.Locale;

/** Runs the actual APK JNI library without restarting/installing the user app.
 * Never prints reasoning or source documents; only fixture pass/fail signals.
 */
public final class GenerationProbe {
    private static void run(LlamaEngine engine, String name, String question, boolean thinking, String expected) {
        engine.resetSession();
        String prompt = "<|im_start|>system\nYou are a helpful assistant. Use plain English, no emoji.<|im_end|>\n"
                + "<|im_start|>user\n" + question + "<|im_end|>\n<|im_start|>assistant\n";
        if (!thinking) prompt = "<|system_cmd_disable_thinking|>" + prompt;
        final StringBuilder stream = new StringBuilder();
        String raw = engine.generateText(prompt, stream::append);
        int metricsIndex = raw.indexOf("<|metrics|>");
        String result = metricsIndex < 0 ? raw : raw.substring(0, metricsIndex);
        String answer = result;
        boolean hadThinking = result.contains("<think>") || result.contains("<|thought_begin|>");
        if (hadThinking) {
            String endTag = result.contains("<|thought_begin|>") ? "<|thought_end|>" : "</think>";
            int end = result.indexOf(endTag);
            if (end < 0) throw new AssertionError(name + ": thinking never ended");
            answer = result.substring(end + endTag.length());
        }
        if (answer.trim().isEmpty() || answer.startsWith("Error:")) throw new AssertionError(name + ": missing final answer");
        if (!thinking && hadThinking) throw new AssertionError(name + ": disabled mode emitted reasoning");
        if (!answer.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))) {
            // Synthetic fixture only; never include the reasoning segment.
            throw new AssertionError(name + ": final answer did not match fixture; answer="
                    + answer.substring(0, Math.min(256, answer.length())).replace('\n', ' '));
        }
        System.out.println("PASS case=" + name + " thinking=" + hadThinking + " answer_chars=" + answer.length()
                + " stream_chars=" + stream.length() + " metrics=" + (metricsIndex < 0 ? "missing" : raw.substring(metricsIndex + 11)));
    }
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("model path required");
        LlamaEngine engine = new LlamaEngine();
        try {
            if (!engine.initContext(args[0], 2048, false)) throw new AssertionError("CPU init failed");
            int failures = 0;
            try { run(engine, "long-thinking-answer", "Find the smallest positive integer x with remainder 12 when divided by 97, remainder 34 when divided by 89, and remainder 56 when divided by 83. In the final answer give the integer.", true, ""); }
            catch (AssertionError e) { System.out.println("FAIL " + e.getMessage()); failures++; }
            try { run(engine, "bounded-thinking", "unicorn", true, "unicorn"); }
            catch (AssertionError e) { System.out.println("FAIL " + e.getMessage()); failures++; }
            try { run(engine, "disabled-thinking-markup", "In Dart, what is the type of a list of strings? Answer only with the type including angle brackets.", false, "List<String>"); }
            catch (AssertionError e) { System.out.println("FAIL " + e.getMessage()); failures++; }
            try { run(engine, "disabled-thinking-math", "What is 17 times 19? Give the numerical result.", false, "323"); }
            catch (AssertionError e) { System.out.println("FAIL " + e.getMessage()); failures++; }
            if (failures != 0) throw new AssertionError(failures + " synthetic fixtures failed");
        } finally {
            engine.releaseContext();
        }
    }
}
