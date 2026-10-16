package app.imagine3d.llama

/** Receives generated text pieces. Return false to stop generating. */
fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}

/** Thin JNI surface over llama.cpp; see src/main/cpp/imagine_llama.cpp. */
internal object LlamaNative {
    init {
        System.loadLibrary("imagine_llama")
    }

    external fun backendInit(nativeLibDir: String)
    external fun systemInfo(): String
    external fun load(modelPath: String, mmprojPath: String?, nCtx: Int, nThreads: Int): Long
    external fun free(handle: Long)
    external fun info(handle: Long): String
    external fun cancel(handle: Long)
    external fun prefixSave(handle: Long, path: String): Boolean
    external fun prefixLoad(handle: Long, path: String): Int
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        images: Array<ByteArray>?,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Int,
        callback: TokenCallback,
    ): String
}
