"""DailyFlow for Windows: the Android app's pipeline, ported.

capture (../capture, Level 1) -> store (SQLite) -> extraction (Gemma via
LiteRT-LM) -> reminders, brief, Ask, people, voice notes. See
docs/PROJECT_CONTEXT.md section 8 for what was ported verbatim and why.
"""
