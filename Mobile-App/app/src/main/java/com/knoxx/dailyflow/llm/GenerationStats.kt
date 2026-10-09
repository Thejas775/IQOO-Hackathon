package com.knoxx.dailyflow.llm

/**
 * Timing for one generation.
 *
 * The split between prefill and decode is the whole point of this harness:
 * they scale differently (prefill with prompt length, decode with output
 * length), so a single blended "tokens/sec" number hides which one is slow.
 *
 * On token counting: LiteRT-LM's streaming API emits incremental text deltas,
 * not token ids, so [tokens] is the number of stream emissions. That's the
 * standard proxy — one decode step per emission — but it is a proxy, not a
 * tokenizer count. [charsPerSec] is reported alongside as a measure that
 * doesn't depend on that assumption at all; if the two ever tell different
 * stories, trust chars/sec.
 */
data class GenerationStats(
    /** Stream emissions ≈ decode steps. See class note. */
    val tokens: Int,
    val chars: Int,
    /** Send → first emission. This is prefill + any lazy engine warm-up. */
    val ttftMs: Long,
    /** First emission → last emission. Pure decode. */
    val decodeMs: Long,
    /** Send → last emission. */
    val totalMs: Long,
    val backend: BackendChoice,
    /** Whether this turn included an image. Image turns carry hundreds of extra
     *  prefill tokens from the vision encoder, so their TTFT is not comparable
     *  to a text-only turn's — the UI keeps the two averaged separately. */
    val hadImage: Boolean = false
) {
    /**
     * The headline number. Excludes prefill, and uses tokens-1 because the
     * first emission's cost is already accounted for in [ttftMs] — counting it
     * again here would inflate the rate on short replies.
     */
    val decodeTokensPerSec: Double
        get() = if (decodeMs > 0 && tokens > 1) (tokens - 1) * 1000.0 / decodeMs else 0.0

    /** End-to-end rate including prefill — what the user actually feels. */
    val overallTokensPerSec: Double
        get() = if (totalMs > 0 && tokens > 0) tokens * 1000.0 / totalMs else 0.0

    val charsPerSec: Double
        get() = if (totalMs > 0) chars * 1000.0 / totalMs else 0.0
}
