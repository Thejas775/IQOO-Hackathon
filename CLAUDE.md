# DailyFlow

IQOO hackathon project. Local-first personal memory layer for Android: captures on-screen text via the Accessibility API, understands it with an **on-device LLM (Gemma 4 via LiteRT-LM)**, surfaces commitments and open loops. Cloud Gemini is a fallback, not the default.

A from-scratch rebuild of `../Wick` with a clean, incremental commit history.

## Read this first

**`docs/PROJECT_CONTEXT.md`** holds the full picture: current state, decisions and their rationale, the prior-art bug list, the backend architecture, and the roadmap. Read it before doing any work here — it's written to bring a cold session fully up to speed.

## Rules

- **Never run Gradle.** No `gradlew`, no builds, no sync. Thejas keeps a build running in Android Studio and a second Gradle invocation fights the daemon lock. Write code, then hand off. If a compile break is plausible, flag the specific risk rather than verifying by building.
- **Prefer giving commands over running them** for anything outside this directory.
- **Don't start building features until asked.** Thejas directs the sequence.
- **One feature per commit.** Message explains *why*, not just what.
- **Comment the non-obvious with evidence.** When a choice was forced by a real bug or measurement, record that. This is the most valuable habit carried over from Wick.

## Reference repos (siblings, not dependencies)

- `../Wick` — the app being rebuilt. Good pipeline design; see the landmine list in `docs/PROJECT_CONTEXT.md` §5 before copying anything.
- `../Vision-AI-Gemma` — proven-correct LiteRT-LM 0.11.0 API surface, and the deterministic line-based LLM output pattern that on-device extraction should use.

## Layout

```
app/src/main/java/com/knoxx/dailyflow/
  llm/          InferenceEngine, ModelLocator, GenerationStats
  ui/           BenchScreen, theme/
  BenchViewModel.kt
  MainActivity.kt
```

The model is **downloaded in-app** from Hugging Face (public, Apache 2.0, not gated), with `adb push` as an offline fallback into the same directory:
```
adb push gemma-4-E4B-it-gpu.litertlm /sdcard/Android/data/com.knoxx.dailyflow/files/
```
Model files are gitignored — they are multi-GB and must never enter history.
