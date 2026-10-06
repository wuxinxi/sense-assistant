package cn.xxstudy.assistant.engine;

public final class LlamaEngine {
    static { System.loadLibrary("sense_assistant"); }
    public native boolean initContext(String model, int contextSize, boolean gpu);
    public native String generateText(String prompt, LlamaCallback callback);
    public native void resetSession();
    public native void releaseContext();
}
