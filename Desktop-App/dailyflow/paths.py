"""Where everything lives, and the small settings file.

Private data (the database, logs, voice notes) stays under %LOCALAPPDATA%,
never in the OneDrive-synced project folder: it is raw screen text.

The model is different. It is 2-3 GB and public, and C: on the development
laptop has 3 GB free, so its folder is a setting rather than a fixed path -
the same idea as Android's ModelLocator, which looks in one known place
whether the file was downloaded or sideloaded.
"""

import json
import os
import threading
from pathlib import Path

DATA_DIR = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "DailyFlow"
LOG_DIR = DATA_DIR / "logs"
DB_PATH = DATA_DIR / "dailyflow.db"
VOICE_DIR = DATA_DIR / "voice"
SETTINGS_PATH = DATA_DIR / "settings.json"
APP_LOG_PATH = LOG_DIR / "app.jsonl"


def _default_model_dir() -> str:
    # The drive with the most free space, preferring a non-system drive: the
    # model is the one large thing this app stores.
    import shutil
    best, best_free = DATA_DIR / "models", -1
    for letter in "DEFGHIJ":
        root = Path(f"{letter}:\\")
        try:
            free = shutil.disk_usage(root).free
        except OSError:
            continue
        if free > best_free:
            best, best_free = root / "DailyFlow" / "models", free
    return str(best)


DEFAULTS = {
    "model_dir": None,          # filled on first read
    "model_file": "gemma-4-E2B-it.litertlm",
    # GPU: 33-40 s per screen on Iris Xe against 51-80 s on the CPU (measured
    # 2026-10-10, PROJECT_CONTEXT.md §9.1). The engine falls back to CPU.
    "backend": "gpu",
    "extract_every_min": 15,    # Android's WorkManager period
    "auto_extract": True,
    "reminders": True,
    # Off = the phone's extraction logic exactly. On = four checks added for
    # the laptop's smaller model (PROJECT_CONTEXT.md §9.2).
    "extra_checks": False,
}

_lock = threading.Lock()


def load_settings() -> dict:
    with _lock:
        try:
            data = json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        merged = {**DEFAULTS, **data}
        if not merged["model_dir"]:
            merged["model_dir"] = _default_model_dir()
            _write(merged)
        return merged


def save_settings(**changes) -> dict:
    current = load_settings()
    with _lock:
        current.update(changes)
        _write(current)
    return current


def _write(data: dict):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = SETTINGS_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    os.replace(tmp, SETTINGS_PATH)
