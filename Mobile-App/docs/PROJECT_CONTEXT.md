# DailyFlow — Project Context

**Purpose of this file:** the durable record of what DailyFlow is, what's been decided, and what's been learned. Written so that anyone (or any AI session with no memory of earlier conversations) can read this and be fully caught up. Keep it current — when a decision changes, edit this file in the same commit.

**Last updated:** 2026-09-05
**Repo:** `C:\Users\Thejas\Desktop\Thejas\IQOO`
**Package:** `com.knoxx.dailyflow` · **App name:** Daily Flow

---

## 1. What this is

DailyFlow is an IQOO hackathon project: a **local-first personal memory layer for Android**. It watches on-screen text across other apps via the Accessibility API, understands it with an **on-device LLM**, and turns it into a queryable memory of your commitments, decisions, and open loops.

It is a deliberate **from-scratch rebuild of Wick** (`../Wick`), with two goals:

1. **Clean, incremental commit history.** Features land one at a time, each a real working increment. The git log should read as genuine development, because it is.
2. **On-device Gemma as the default and priority.** Cloud Gemini stays as a selectable fallback, not the main path.

### Why the on-device shift matters

Wick's `ConnectClaudeScreen` told users *"Wick syncs what it understands — never the raw screen text."* That was true of the Supabase sync but misleading overall, because Gemini received **every raw captured snippet**. Moving understanding on-device makes the claim literally true end to end: raw screen text never leaves the phone, only one-sentence summaries sync, and only after explicit consent. That is both a better product and a much stronger story for judges than "local-first, asterisk."

---

## 2. Current state

### Built (uncommitted as of 2026-09-05)

A **token-throughput benchmark harness** — a minimal streaming chat UI whose real job is measuring on-device inference speed before committing to the architecture.

| File | Role |
|---|---|
| `llm/ModelCatalog.kt` | The two downloadable E4B builds, with real LFS sizes |
| `llm/ModelDownloader.kt` | Resumable HF download: `.part` file, `Range` resume, manual redirects, throttled progress, free-space check |
| `llm/ModelLocator.kt` | Finds a usable `.litertlm` from either route |
| `llm/GenerationStats.kt` | Per-run timing: TTFT, decode ms, token/char rates |
| `llm/InferenceEngine.kt` | LiteRT-LM wrapper; GPU→CPU fallback; streaming |
| `BenchViewModel.kt` | Download + run orchestration, per-run and session stats |
| `ui/BenchScreen.kt` | Model picker, download progress, chat UI, metrics |
| `MainActivity.kt` | Hosts `BenchScreen` |

### Model builds (verified against the HF API, 2026-09-05)

| File | Size | Use |
|---|---|---|
| `gemma-4-E4B-it-gpu.litertlm` | 2.97 GB | Default — pre-compiled for GPU |
| `gemma-4-E4B-it.litertlm` | 3.66 GB | Universal, incl. CPU-only |

Repo is public, Apache 2.0, `gated: false`.

Also modified: `libs.versions.toml` (added coroutines, litertlm 0.11.0, lifecycle 2.11.0), `app/build.gradle.kts` (minSdk 24→29, Java 11→17, new deps), `AndroidManifest.xml` (OpenCL `uses-native-library`).

**Not yet verified to compile.** The LiteRT-LM API surface was mirrored from `../Vision-AI-Gemma`, which is proven against 0.11.0, but this build hasn't been run.

### How to use the benchmark

```bash
adb push gemma-4-E4B-it.litertlm /sdcard/Android/data/com.knoxx.dailyflow/files/
```

Launch, pick GPU or CPU, send prompts. Read **decode tok/s** as the headline number.

**Interpreting results:**
- **First run is not representative** — it includes one-time native warm-up (kernel compilation, weight paging). The session "mean" deliberately excludes it.
- **TTFT is prefill**, and scales with prompt length. **Decode rate** scales with output length. They are reported separately on purpose; a single blended number hides which one is slow.
- If the header shows *"running on CPU"* while GPU is selected, GPU init failed and fell back — every number is a CPU number. Don't miss this.
- **Token counts are stream-emission counts, not tokenizer counts.** LiteRT-LM emits text deltas, not token ids. One emission ≈ one decode step is the standard proxy, but `char/s` is reported alongside as a measure that doesn't rely on that assumption. If the two disagree, trust char/s.

---

## 3. Decisions already made

| Decision | Rationale |
|---|---|
| **In-app download, with sideload as a fallback** | `litert-community/gemma-4-E4B-it-litert-lm` is **not gated** and is Apache 2.0 — verified against the HF API — so a plain unauthenticated GET works. Both routes write to the same directory, so either one satisfies `ModelLocator`. |
| **Resumable download with a `.part` file** | ~3 GB over venue wifi. Restarting from zero on every dropped connection isn't viable. Redirects are followed manually because the built-in follower drops the `Range` header across hosts, silently turning every resume into a restart. |
| **Validate against known LFS sizes, not a threshold** | Real sizes are recorded in `ModelCatalog`, so a truncated transfer is caught precisely instead of by an arbitrary "big enough" guess (a bug Vision-AI-Gemma had, with two contradictory thresholds). |
| **Scan for any `*.litertlm`, don't hardcode a filename** | The litert-community repos ship several per-platform variants. Hardcoding breaks on a different build — a trap Vision-AI-Gemma fell into. |
| **minSdk 29** | LiteRT-LM ships arm64-v8a/x86_64 natives and expects a modern runtime. Also avoids the `AccessibilityNodeInfo.recycle()` obligation that applies below API 33. |
| **Gemma 4 E4B** (not E2B) | Demo device has headroom; E4B is the better model. E2B remains the fallback if E4B is too slow. |
| **Fresh conversation per benchmark run** | Otherwise prefill grows with accumulated history and TTFT drifts upward across a session, reading as "the model is getting slower." |
| **MCP is built last** | It's the least demo-critical piece and the one that historically ate the most time (see §5). |

---

## 4. Prior art — where the code comes from

Two reference repos sit alongside this one. **Read their code, don't copy their bugs.**

### `../Wick` — the thing being rebuilt
Complete Android client: accessibility capture → dedup → Room → embeddings → Gemini extraction → Supabase sync → MCP. ~4.2k LOC. The pipeline design is good and worth following closely. Its inline comments encode real debugging history with evidence — those are the most valuable thing in either repo.

Note: Wick's published git history is broken (a `git commit -am` committed 20 modified files but none of the 21 new files they depended on). The files were later recovered locally, so the working tree is complete even though the repo history isn't. **The `supabase/` backend — migrations, Edge Functions, and the `mcp-auth` service — was never committed and is genuinely gone.**

### `../Vision-AI-Gemma` — the LiteRT-LM reference
Offline reading assistant for blind users. Its `engine/InferenceEngine.kt` is the **proven-correct LiteRT-LM 0.11.0 API surface**: `Engine`, `EngineConfig(modelPath, backend, cacheDir)`, `engine.createConversation()`, `conv.sendMessage(Contents.of(Content.Text(...)))`, `conv.sendMessageAsync(...)` returning a Flow of objects you `.toString()`.

Also worth stealing: `AgentParser`'s **deterministic line-based output format** (`SPEAK:` / `ACTION:` / `TITLE:` / `DATE:` / `ASK:`) with a hand-written parser, instead of asking for JSON. This is the right pattern for small on-device models and directly applies to DailyFlow's extraction step (see §6).

---

## 5. Landmines — bugs found in the prior repos

These were all found by audit and are **not** to be reproduced.

**From Vision-AI-Gemma:**
1. **Dead GPU→CPU fallback.** `try { EngineConfig(GPU) } catch { EngineConfig(CPU) }` never fires — `EngineConfig` is an inert value object that doesn't throw. The failure happens at `engine.initialize()`, which sat *outside* the try. Result: hard failure on exactly the devices the fallback existed to rescue. **DailyFlow's `InferenceEngine` wraps `Engine()` + `initialize()` inside the try.**
2. ~390,000 StateFlow emissions during a multi-GB download (progress fired per 8 KB chunk). Throttle progress callbacks.
3. Contradictory model size gates: download accepted only `>1 GB`, startup rejected `<100 MB`.
4. `(conversation as? AutoCloseable)?.close()` — if the type doesn't implement it, the safe cast yields null and native memory leaks silently on every reset. **Still unverified for 0.11.0; check it.**
5. Hardcoded model filename against a repo with per-platform variants.

**From Wick:**
6. **Synchronous file I/O on the main thread**, twice per accessibility event (open/write/flush/close per event, ~8,700 events/day). ANR risk. Use one long-lived writer on a background dispatcher.
7. `foregroundServiceType="specialUse"` declared without the `FOREGROUND_SERVICE_SPECIAL_USE` permission → `SecurityException` on API 34+.
8. `allowBackup="true"` with untouched template backup rules → the whole memory database is eligible for Google cloud backup, through a channel no consent UI mentions.
9. `gemini_exchange_log.jsonl` grew forever with no rotation, holding every raw prompt ever sent.
10. `batch.zip(results)` silently truncates on a count mismatch, leaving rows `extracted = false` forever → infinite retry loop against a paid API.
11. API key in `BuildConfig` → plaintext constant in `classes.dex`, extractable from any APK.

---

## 5b. Vision / image input — researched 2026-09-05

**Gemma 4 is natively multimodal** (text, vision, audio). LiteRT-LM loads the image and audio encoders on demand, so declaring a vision backend costs nothing on text-only turns.

**API:**
```kotlin
EngineConfig(modelPath, backend, visionBackend = Backend.GPU(), audioBackend = ...)
conversation.sendMessageAsync(Contents.of(
    Content.ImageFile("/path/to.jpg"),   // or Content.ImageBytes(byteArray)
    Content.Text("Describe this image."),
))
```
Image goes **before** the text — Gemma's multimodal template expects the image to precede the text referring to it.

**⚠️ The critical finding — `litert-lm#2056`:** with `visionBackend = Backend.CPU()`, Gemma 4 E4B deterministically SIGSEGVs in native `memmove` on the **second image turn** of a conversation. Reported on Snapdragon 8 Elite / Adreno 830 — this project's exact target class of device. **`visionBackend = Backend.GPU()` eliminates it entirely.**

DailyFlow defends twice over:
1. The init ladder is `GPU/GPU → CPU-main/GPU-vision → CPU/CPU`. Vision stays on GPU even when the main model falls back, because the fix is independent of the main backend. CPU vision is the last rung and the UI warns when it's active.
2. The conversation is reset after every run, so every image turn is the *first* of its conversation — the crash needs a second one.

**Which files support vision:** only the `-web` build is documented as text-only. Both `gemma-4-E4B-it-gpu.litertlm` and `gemma-4-E4B-it.litertlm` should carry the encoder.

**Related open issue:** `#1874` — the Kotlin API lacks custom `PromptTemplate` support, described as "blocking Gemma 4 multimodal functionality." If image answers come back as nonsense rather than failing outright, look here first; the CLI path uses an explicit `<start_of_image>` token.

### Larger Gemma 4 models — researched, ruled out for Android

**Only E2B and E4B are Android models.** Both cards state "ready for deployment on Android, iOS, Desktop, IoT and Web." The larger three do not, and 12B has a *hard technical block*, not just a missing claim:

**`litert-lm#2461` — Gemma 4 12B fails to load on Android, both backends:**
- **CPU is refused outright.** The model's metadata sets `section_backend_constraint: gpu` on the `tf_lite_prefill_decode` section, so CPU loading returns `INVALID_ARGUMENT`. You cannot trade speed for memory.
- **GPU hits a per-allocation ceiling.** It requests a ~2.1 GB single allocation; Android OpenCL drivers commonly cap *per-allocation* at 1 GiB. The reporter had **16 GB of system RAM and it still failed** — this is a driver limit, so more RAM does not help.
- Unresolved, no maintainer response.

Also: 12B requires LiteRT-LM ≥ 0.17 (we're on 0.11.0), is a 5.99 GB download, and wants ~7.3–7.9 GB of GPU memory.

`26B-A4B` (15.8 GB, ~15 GB GPU) and `31B` (19.2 GB, ~19.6 GB GPU) are currently **web-only** — their cards say "ready for deployment on web" with wider platform support "to come."

**Conclusion: E4B is the ceiling on this hardware.** Not a compromise — the ceiling.

### MEASURED on the actual demo device — 2026-09-05

Device: **vivo iQOO I2501**, SoC **SM8850** (Snapdragon 8 Elite Gen 5), Android 16 / API 36,
arm64-v8a, 15.6 GB RAM. Model: `gemma-4-E4B-it.litertlm` (universal build, 3,659,530,240 bytes).

**Context window — settled, not inferred.** The engine prints its own validated config:

```
MainExecutorSettings: backend: GPU
max_tokens: 4096
clear_kv_cache_before_prefill: true
VisionExecutorSettings:
  EncoderBackend: GPU
  AdapterBackend: CPU
```

**`max_tokens: 4096` — but that was *our* default, not the model's limit.** The engine prints
whatever it was configured with, and we never configured it. `maxNumTokens` is an `EngineConfig`
field (Google's own AI Edge Gallery does `EngineConfig(... maxNumTokens = maxTokens ...)`), and
omitting it takes LiteRT-LM's 4096 default — silently discarding seven eighths of E4B's usable
32k window. Now set explicitly, with an in-app 4K/8K/16K/32K selector.

Raising it is not free: this is the **KV-cache budget**, so memory scales with it, and there are
field reports of instability above 4096 — `litert#6765` (SIGSEGV above 4096 on iOS arm64) and
`litert-lm#1681`, where a team settled on 3072 to stay "safely below the crash threshold" on
Tensor G5. Both are other devices. The selector exists so the ceiling on *this* hardware gets
measured rather than guessed.

**Throughput (GPU, 3 runs):** best **15.9 tok/s**, warm mean **12.3 tok/s**. Below Google's
published 22.1 tok/s for E4B/GPU on an S26 Ultra. Two likely reasons, neither confirmed: the
installed APK is a **debug build with `-Xcheck:jni` active** (logcat shows `Late-enabling -Xcheck:jni`
plus Studio's instrumentation agent), which taxes every JNI crossing — and LiteRT-LM streams
through JNI; and runs 2–3 generated much longer outputs, so decode averages down as the
sequence grows. Re-measure on a release build before treating 12–16 tok/s as the real number.

**Memory:** TOTAL PSS 1.42 GB, Graphics 394 MB, Native heap 287 MB — for a 3.49 GB model.
Weights stay memory-mapped, exactly as the model card claims.

**Model sections present** (from `litert_lm_loader`), which confirms multimodality directly:

| Section | Size | Backend constraint |
|---|---|---|
| `tf_lite_vision_encoder` | 224 MB | prefers fp16 |
| `tf_lite_vision_adapter` | 7.9 MB | **cpu (forced by the model)** |
| `tf_lite_audio_encoder_hw` | ~837 MB | cpu |
| `tf_lite_prefill_decode` | — | prefers fp16 |
| `tf_lite_mtp_drafter` | ~2.3 GB | — |

Two things follow. **The vision adapter runs on CPU no matter what we request** — the constraint
is baked into the model's own metadata, so our GPU-vision ladder only governs the encoder.
And an **MTP drafter is present**, meaning speculative decoding is available and unexplored —
a possible speed win if 12–16 tok/s proves too slow.

Also note: `engine_settings.cc` reports *"The Main backend constraint is not set"* for this
build — unlike 12B, which pins the prefill/decode section to GPU and thereby refuses CPU.

**Still untested:** behaviour at the 4096 boundary — graceful truncation, an error, or a crash.

### Official Android benchmarks (targets to compare our numbers against)

Google's own figures, S26 Ultra, 1024 prefill / 256 decode tokens, 2048 context:

| Model | Backend | Prefill tok/s | Decode tok/s | TTFT | Model MB | Mem MB |
|---|---|---:|---:|---:|---:|---:|
| E4B | GPU | 1,293 | **22.1** | 0.8 s | 3,654 | 710 |
| E4B | CPU | 195 | 17.7 | 5.3 s | 3,654 | 3,283 |
| E2B | CPU | 557 | **46.9** | 1.8 s | 2,583 | 1,733 |

If our Elite Gen 5 GPU numbers land far below ~22 tok/s decode on E4B, something is misconfigured — most likely a silent fall back to CPU. Note GPU uses *less* CPU memory (710 MB vs 3,283 MB), since weights stay memory-mapped on the GPU side.

### Other litert-community models worth knowing about

Relevant to DailyFlow's later phases:

- **`embeddinggemma-300m`** — a proper on-device embedding model, and the drop-in fix for Wick's dedup failure (see below).
- **`FunctionGemma_270M_Mobile_Actions`** — a 270M model fine-tuned for mobile action/function calling. Directly relevant if the AutoPilot layer gets built.
- **`PaddleOCR-VL-1.6`** — a dedicated OCR vision-language model, if Gemma's own OCR proves weak on dense documents.
- Also present: Gemma3 1B/4B, `gemma-3-270m-it`, Qwen3 0.6B/4B, Phi-4-mini, FastVLM-0.5B, SmolVLM-256M, TranslateGemma-4B.

---

## 5c. Embeddings — what Wick used, and what to use instead

**Wick used MediaPipe Tasks `TextEmbedder`** (`com.google.mediapipe:tasks-text:1.0.0`) with a bundled asset `bert_embedder.tflite` (26,142,232 bytes). Wick's own comment describes it as MobileBERT-based.

That model **no longer appears in MediaPipe's Text Embedder documentation.** The current lineup is:

| Model | File | Quantization | CPU latency (S26 Ultra, 4 threads) | Input |
|---|---|---|---|---|
| **EmbeddingGemma 300M** | `embedding_gemma.task` | int4+int8 mixed | 200 ms | 512 tokens |
| Universal Sentence Encoder | `universal_sentence_encoder.tflite` | float32 | 10 ms | 3 strings |

**Why this matters — and the precise finding.** The embedder was *not* abandoned wholesale. Wick uses it successfully on **raw screen text**, and rejected it only for **summary text**:

| Layer | Where | Method | Verdict |
|---|---|---|---|
| 1. Raw-log repeat | `LogRepository.record` | exact joined-text repeat per package (no time bound) + 2 s min write interval | raw JSONL only, not the memory pipeline |
| 2. Burst debounce | `MemoryRepository.enqueue` | 3 s, **`TYPE_VIEW_TEXT_CHANGED` only**, per package | scoped deliberately — a blanket debounce across all event types let only 4 rows survive from ~160 captures in 18 min |
| 3. Exact text | `processCapture` | `hasRecentExactText`, 24 h window, per package, **persisted in Room** not in-memory | works |
| 4. **Semantic, raw text** | `processCapture` | cosine ≥ **0.95** vs recent embeddings, same package, 24 h | **embeddings used here, and they work** |
| 5. Model-side | `GeminiExtractionClient` prompt | 50 recent summaries passed as context, model marks dupes `isLoop=false` | works |
| 6. **Summary text** | `isDuplicateOfExistingMemory` | **normalized string equality** — lowercase, strip `[.,!?;:'"]`, collapse whitespace | **embeddings rejected here** |

The 0.9975 failure was at **layer 6 only**. Long raw screen dumps carry enough distinguishing content for a weak embedder; short template-shaped sentences (Gemini's summaries all open "You noted…", "You promised…", "You agreed…") do not. Wick's comment is explicit that this was a measured decision, not a guess, and that false negatives were the acceptable tradeoff — better to show a paraphrased duplicate than to hide a genuinely distinct memory.

**So EmbeddingGemma's target is layer 6**, where a real sentence-similarity model would work and MobileBERT couldn't. Layer 4 is fine as-is.

**Also carry forward a performance caveat:** layer 4 loads *every* recent embedding for that package and deserializes each BLOB on every capture — O(n) per event, in-app, on the IO dispatcher. Fine at Wick's volume; worth an index or a cap if capture rate rises.

**EmbeddingGemma 300M is trained for exactly this** — retrieval, semantic similarity, classification, clustering — and is served through the **same `TextEmbedder` API**, so the swap is an asset change plus an options tweak, not a rewrite. At 200 ms CPU it is ~20× slower than USE, which is irrelevant for a background dedup pass.

Use EmbeddingGemma when the dedup layer gets built. Do not re-adopt `bert_embedder.tflite`.

---

## 6. The extraction problem (important)

Wick's `ExtractionWorker` batches **20 snippets × up to 3000 chars**, plus 50 existing summaries as context, and demands a strict JSON array back via Gemini's `responseSchema`.

**None of that survives the move on-device:**
- **Context is far smaller than the headline number.** Three different figures get quoted and only the smallest one is operative:

  | Figure | Source | What it means |
  |---|---|---|
  | 256K | Gemma 4 model card (ai.google.dev) | The architecture's ceiling. Not what you get. |
  | 32K | litert-community E4B card | What the `.litertlm` build claims to support. |
  | **4K** | Real deployments + HF discussion #18 | **The practical default `max_num_tokens`.** |

  And raising it is not free: `litert#6765` reports `max_num_tokens > 4096` **SIGSEGVs in reshape on iOS arm64**, with the response being that 32k "should" work given sufficient memory. So treat anything above 4K as unverified on our hardware until measured.

  Wick's batch of 20 × 3000 chars is roughly 15k tokens, plus ~1k of prompt and ~1k of summary context. That **would** fit in a true 32k window — so "the context can't hold it" was wrong. At the 4K default it doesn't come close, and the KV cache cost of raising the window to fit it is exactly the memory we don't want to spend on a phone.
- LiteRT-LM has **no schema-constrained output**. There is no `responseSchema` equivalent.

So the on-device path needs a different shape: **one snippet per inference**, a deterministic line-based output format (per Vision-AI-Gemma's `AgentParser`), and a hand-written parser. This is a different worker, different prompt, different parser — not a swapped-out client.

The benchmark exists to answer: **is one-snippet-per-inference fast enough to keep up with real capture volume?** That number decides the architecture.

---

## 7. Backend architecture (to be rebuilt)

Reconstructed from Wick's surviving client code. The server side is gone and will be rebuilt on a **brand-new Supabase project** (the old one is `ref: jsximbpvozthkqlnmvmv` — do not push migrations to it; `supabase unlink` first).

```
App ── Supabase SDK ──► Supabase        auth + memories table + insert_memory RPC
                                        (summary encrypted server-side)
App ── OkHttp ────────► mcp-auth        POST /account/rotate-password
                        (Deno Deploy)   POST /account/revoke
                                        POST /account/delete-data
                                        → {ok, revoked_sessions, deleted_memories}
Claude ───────────────► MCP server      streamable HTTP + OAuth
```

Three separate URLs (`MCP_SERVER_URL`, `MCP_AUTH_URL`, `MCP_OWNER_PASSWORD` — all from `local.properties`). The error shape the client parses (`{error, error_description}`) is the **OAuth 2.0 standard format**, confirming `mcp-auth` was a real OAuth authorization server issuing revocable sessions.

**The wall previously hit:** OAuth for Claude custom connectors couldn't be done in Supabase Edge Functions alone, which is why a second service on Deno Deploy exists. Thejas recalls possibly using Cloudflare too — **there is zero Cloudflare reference in any of the 174 files**, so either he's thinking of Deno Deploy, or Cloudflare was doing DNS/proxying that never touched source. Unresolved and not blocking.

When we get here: **verify the current custom-connector auth spec before designing** — it has moved, and that movement is what cost the time last round.

---

## 8. Open decisions

1. **Demo scope.** Core loop (capture → on-device Gemma → memories UI) only, or also AutoPilot / daily digest / MCP?
2. **Gemini fallback UX.** Settings toggle, or automatic when the device can't run Gemma?
3. **E4B vs E2B** — decided by benchmark results.
4. **MCP host** — reuse the Deno Deploy shape, move to Cloudflare (which ships a purpose-built OAuth provider library), or cut remote MCP from the demo.
5. **Capture volume vs inference speed.** If one-snippet-per-inference can't keep up, options are an on-device pre-filter, batching within the context limit, or falling back to cloud for burst periods.

---

## 9. Roadmap

- **0 — Benchmark harness** ✅ built, not yet verified
- **1 — Capture layer.** Accessibility service, `NodeExtractor`, JSONL logging with rotation. Port from Wick, fix landmine #6.
- **2 — Storage.** Room, `MemoryEntity`, dedup (exact-text → embedding → semantic).
- **3 — On-device understanding.** Gemma extraction worker, line-based parser. The heart of the project.
- **4 — Memory UI.** Today / Calendar / Projects / People.
- **5 — Gemini fallback.** Introduce the `ExtractionEngine` interface *at this commit* — the moment the second implementation exists. Not before (premature), not after (rewrite).
- **6 — Cloud sync.** New Supabase project, RLS, encrypted summaries.
- **7 — MCP.** Last, for the reasons in §7.

---

## 10. Working agreements

- **Never run Gradle.** Thejas keeps a build running in Android Studio; a second Gradle invocation fights the daemon lock. Write code, hand off, let him build. If a compile break is plausible, flag the specific risk instead of verifying by building.
- **Prefer giving commands over running them** for anything outside the project directory.
- **Don't start building features until asked.** He directs the sequence.
- **One feature per commit**, with a message that explains *why*, not just what.
- **Carry the comment style forward from Wick** — when a non-obvious choice is made, record the evidence that forced it. Those comments were the single most valuable thing in the old codebase.

## 11. Phone ↔ PC sync (built 2026-10-10)

Asked for: memories, tasks and the calendar in step between the phone and DailyFlow on a PC; every
memory still says which device it came from; pairing from the phone's profile ("Add computer") with a
code. Owner's decisions: **same Wi-Fi only** (no server — keeps "nothing leaves your devices") and
**memories only** (raw screen text never syncs; "What was on screen" works only where it was captured).

**Shape.** The PC listens (TCP 47811, discovery UDP 47812); the phone starts every exchange, because it
knows when it is on Wi-Fi and a phone cannot be reached reliably. Protocol, byte for byte:
`Desktop-App/dailyflow/sync_protocol.py` ↔ `Mobile-App/.../sync/SyncProtocol.kt` + `PcPairing.kt`.

**Pairing.** PC: Model & settings → Phones → *Pair a phone* shows a 6-digit code (5 min, 5 tries) and
its address. Phone: You → *Add computer* → finds PCs on the Wi-Fi by broadcast (or type the address,
for networks that isolate clients) → type the code. SPAKE2 over the RFC 3526 2048-bit group: the code
never crosses the network, a listener learns nothing brute-forceable, and an active attacker gets one
guess per try. (Rejected: sending the code — sniffable; HMAC of the code under DH — a man in the middle
brute-forces 10^6 codes offline in milliseconds.) Result: a 32-byte key per phone↔PC pair; every sync
message is AES-256-GCM with the phone id as AAD.

**Data.** Both databases gained `syncId` (cross-device id; local ids collide), `originDevice/Name/Kind`
(NULL = this device), `updatedAt`, `changeSeq`, `lastChangedBy`, and a `sync_tombstones` table.
Android: Room v2 → v3 migration (`MIGRATION_2_3`; existing memories get ids and are sent on first sync).
Every write that changes a memory goes through one `touch` (Android `MemoryRepository`, PC `Store`):
new memory, extraction saved, derived row, done/undone, manual task; delete writes a tombstone.
Only understood memories travel (isLoop with a summary). "Erase everything" is this device only.

**Sync.** Phone sends its changes since the PC's last ack (`pushedSeq`) plus its cursor of the PC's
changes (`pcCursor`); the PC applies, answers with its changes after that cursor, in batches of 200
until neither side has more. A change never echoes back to the device it came from (`lastChangedBy`).
Conflicts: newer `updatedAt` wins; a deletion beats edits made before it. When: phone app start, a few
seconds after any change on the phone (WorkManager, KEEP so an in-flight sync is never cancelled),
every 15 min on unmetered network, *Sync now*. PC changes reach the phone at the phone's next sync.

**Reminders.** A synced memory already due is marked reminded on arrival (the other device nudged;
no burst of stale reminders on first sync); future ones are scheduled; ticked done elsewhere cancels.

**Showing origin.** Phone: a violet "FROM <PC NAME>" chip beside the app badge on Today, Calendar,
Person, and "· from <PC>" on Memories; PC app names/sites are labelled (whatsapp.root.exe → WhatsApp,
mail.google.com → Gmail). PC: "📱 FROM <PHONE>" chip on every card; Android packages labelled.
PC rows are kept out of the phone's "Manage app access" list.

**Verified.** PC side end to end with a phone simulator (discovery; refused when not pairing; wrong
code caught on the phone; right code pairs; code single-use; both directions; done both ways; stale
edit ignored; deletions both ways; unpaired device and wrong key rejected). MODP prime checked
(2048-bit safe prime). Android SQL (migration + every SyncDao query) run against SQLite.
**Not verified: the Kotlin has not been compiled** (no Gradle by rule, no JDK on the machine) — see
risks below.

**Compile/run risks to check in Android Studio**
- Room schema check on open: MIGRATION_2_3 must match the entity exactly (defaults `0` on the two
  counters, `index_memories_syncId` unique). A mismatch throws "Migration didn't properly handle".
- `"$NEXT_SEQ"` string templates inside `@Query` (const val, so constant — should compile).
- Windows Firewall must allow python/pythonw on Private networks for TCP 47811 and UDP 47812, and the
  Wi-Fi profile must be Private; a Public profile blocks inbound connections.

**Known limits.** The same message read on both devices before either syncs becomes two memories
(summary dedup only sees what is already local). Clock skew between devices beyond seconds can
make an older edit win. Replay of an old captured sync message is accepted but harmless (LWW).

