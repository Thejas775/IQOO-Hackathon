"""Which Gemma builds can be used, where the file is, and how it is fetched.

Ports Android's ModelCatalog, ModelLocator and ModelDownloader.

E2B, not the phone's E4B: this laptop has 7.7 GB of RAM shared with Chrome
and WhatsApp and no NPU. Google's figures are ~1.7 GB of working memory for
E2B on CPU against ~3.3 GB for E4B. E4B stays listed so it can be measured.

Sizes are the real Hugging Face LFS sizes (X-Linked-Size, checked
2026-10-10), recorded rather than trusted from the server at download time,
because the size is what proves a finished file is whole.
"""

import shutil
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .paths import load_settings

REPO_BASE = "https://huggingface.co"
BUFFER_BYTES = 1 << 16
SAFETY_MARGIN_BYTES = 300 * 1024 * 1024
PROGRESS_INTERVAL_S = 0.4


@dataclass(frozen=True)
class ModelSpec:
    display_name: str
    repo: str
    file_name: str
    size_bytes: int
    note: str

    @property
    def url(self) -> str:
        return f"{REPO_BASE}/{self.repo}/resolve/main/{self.file_name}"


CATALOG = [
    ModelSpec("Gemma 4 E2B - universal (CPU)", "litert-community/gemma-4-E2B-it-litert-lm",
              "gemma-4-E2B-it.litertlm", 2_588_147_712,
              "Recommended for this PC. Runs on the CPU; also takes audio."),
    ModelSpec("Gemma 4 E2B - GPU build", "litert-community/gemma-4-E2B-it-litert-lm",
              "gemma-4-E2B-it-gpu.litertlm", 2_008_432_640,
              "Smaller, for the GPU backend. Not measured on Iris Xe yet."),
    ModelSpec("Gemma 4 E4B - universal", "litert-community/gemma-4-E4B-it-litert-lm",
              "gemma-4-E4B-it.litertlm", 3_659_530_240,
              "The phone's model. Likely too large for 7.7 GB RAM."),
]


def by_file_name(name: str) -> ModelSpec | None:
    return next((m for m in CATALOG if m.file_name == name), None)


def model_dir() -> Path:
    return Path(load_settings()["model_dir"])


def find_model() -> Path | None:
    """The chosen model file if it is present and whole, else any whole one."""
    d = model_dir()
    chosen = load_settings()["model_file"]
    candidates = [chosen] + [m.file_name for m in CATALOG if m.file_name != chosen]
    for name in candidates:
        p = d / name
        spec = by_file_name(name)
        # A file of the wrong size is a broken download; handing it to the
        # engine gives an opaque native error instead of a clear one.
        if p.is_file() and (spec is None or p.stat().st_size == spec.size_bytes):
            return p
    return None


def part_file(spec: ModelSpec) -> Path:
    return model_dir() / (spec.file_name + ".part")


def download(spec: ModelSpec, on_progress=lambda done, total, rate: None,
             should_stop=lambda: False) -> tuple[bool, str]:
    """Resumable download. Returns (ok, message).

    Partial bytes live in a .part file and a retry sends a Range header, so a
    dropped connection costs nothing already fetched. urllib re-sends the
    Range header across Hugging Face's CDN redirect (unlike Java's
    HttpURLConnection, which the Android port had to follow by hand).
    """
    d = model_dir()
    d.mkdir(parents=True, exist_ok=True)
    target, part = d / spec.file_name, part_file(spec)

    if target.is_file() and target.stat().st_size == spec.size_bytes:
        return True, str(target)

    have = part.stat().st_size if part.exists() else 0
    if have > spec.size_bytes:          # stale or corrupt
        part.unlink()
        have = 0

    free = shutil.disk_usage(d).free
    need = spec.size_bytes - have
    if free < need + SAFETY_MARGIN_BYTES:
        return False, (f"Not enough free space on {d.drive}. Need about {need / 1e9:.1f} GB, "
                       f"{free / 1e9:.1f} GB available.")

    req = urllib.request.Request(spec.url, headers={"User-Agent": "DailyFlow/1.0 (Windows)"})
    if have:
        req.add_header("Range", f"bytes={have}-")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            if have and resp.status != 206:
                # The server ignored Range and is sending byte 0 onward;
                # appending it would silently corrupt the file.
                have = 0
            done = have
            last_t, last_b = time.time(), done
            on_progress(done, spec.size_bytes, 0)
            with part.open("ab" if have else "wb") as out:
                while True:
                    if should_stop():
                        return False, "Paused. Download again to resume."
                    chunk = resp.read(BUFFER_BYTES)
                    if not chunk:
                        break
                    out.write(chunk)
                    done += len(chunk)
                    now = time.time()
                    if now - last_t >= PROGRESS_INTERVAL_S:
                        on_progress(done, spec.size_bytes, (done - last_b) / (now - last_t))
                        last_t, last_b = now, done
    except urllib.error.HTTPError as e:
        return False, {404: "Model file not found on the server (HTTP 404).",
                       416: "Resume failed - the partial file doesn't match. Delete the .part file."
                       }.get(e.code, f"Download failed (HTTP {e.code}).")
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        return False, f"Network error ({e}). Download again to resume."

    got = part.stat().st_size
    if got != spec.size_bytes:
        return False, f"Download incomplete - {got / 1e9:.2f} of {spec.size_bytes / 1e9:.2f} GB. Retry to resume."
    if target.exists():
        target.unlink()
    part.rename(target)
    return True, str(target)


if __name__ == "__main__":
    # python -m dailyflow.models [file_name]  - download from a terminal
    import sys
    name = sys.argv[1] if len(sys.argv) > 1 else load_settings()["model_file"]
    spec = by_file_name(name)
    if spec is None:
        sys.exit(f"unknown model {name}")
    print(f"downloading {spec.file_name} to {model_dir()}", flush=True)
    last = [0.0]

    def show(done, total, rate):
        if time.time() - last[0] > 10:
            last[0] = time.time()
            print(f"{done / 1e9:.2f}/{total / 1e9:.2f} GB  {rate / 1e6:.1f} MB/s", flush=True)

    ok, msg = download(spec, show)
    print("done" if ok else "failed", msg, flush=True)
    sys.exit(0 if ok else 1)
