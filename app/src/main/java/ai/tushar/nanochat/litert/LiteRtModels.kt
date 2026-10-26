package ai.tushar.nanochat.litert

import ai.tushar.nanochat.model.ModelId

/**
 * A `.litertlm` file on Hugging Face and how to run it. Adding a LiteRT model is one row here plus a [ModelId].
 * [bytes] is the published size; a file of exactly that size (downloaded in-app or pushed with adb) counts as complete.
 */
data class LiteRtSpec(
    val id: ModelId,
    val repo: String,
    val file: String,
    val bytes: Long,
    /** KV cache size: input plus output tokens. Bigger costs memory. */
    val maxTokens: Int,
    val maxImages: Int,
    /** Gemma 4 vision budget per image: 70, 140, 280, 560 or 1120 tokens. */
    val visualTokenBudget: Int,
    val audioTokensPerSecond: Int,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$file"
}

val LiteRtCatalog: Map<ModelId, LiteRtSpec> = listOf(
    LiteRtSpec(
        id = ModelId.GemmaE4B,
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        // The file Google's AI Edge Gallery uses: text decoder plus the vision and audio encoders, with MTP speculative decoding.
        file = "gemma-4-E4B-it.litertlm",
        bytes = 3_659_530_240L,
        maxTokens = 8192,
        maxImages = 4,
        visualTokenBudget = 280,
        audioTokensPerSecond = 25,
    ),
).associateBy { it.id }
