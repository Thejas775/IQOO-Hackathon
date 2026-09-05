package com.knoxx.dailyflow.llm

/**
 * The Gemma 4 E4B builds DailyFlow can download, from Hugging Face's
 * litert-community org. Published under Apache 2.0 and **not gated**, so a
 * plain unauthenticated GET works — no token, no license click-through.
 *
 * Sizes are the real LFS object sizes, recorded here rather than trusted from
 * Content-Length at runtime: it lets the UI show an honest download size before
 * starting, and lets a finished download be validated against a number we
 * actually know instead of an arbitrary "big enough" threshold.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val note: String
) {
    val url: String get() = "$REPO_BASE/$REPO/resolve/main/$fileName"

    val sizeGb: Double get() = sizeBytes / 1_000_000_000.0

    companion object {
        const val REPO_BASE = "https://huggingface.co"
        const val REPO = "litert-community/gemma-4-E4B-it-litert-lm"
    }
}

object ModelCatalog {

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = "e4b-gpu",
            displayName = "Gemma 4 E4B — GPU",
            fileName = "gemma-4-E4B-it-gpu.litertlm",
            sizeBytes = 2_969_059_328,
            note = "Pre-compiled for GPU execution. The right build for Snapdragon/Adreno."
        ),
        ModelSpec(
            id = "e4b",
            displayName = "Gemma 4 E4B — universal",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240,
            note = "Runs anywhere, including CPU-only. Larger download."
        )
    )

    /** The GPU build: the demo device has working OpenCL, and this is both the
     *  faster and the smaller of the two. */
    val default: ModelSpec = all.first()

    fun byFileName(fileName: String): ModelSpec? = all.firstOrNull { it.fileName == fileName }
}
