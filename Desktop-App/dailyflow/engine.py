"""The one loaded Gemma, shared by every feature. Port of GemmaEngine.kt.

Same runtime as the phone - LiteRT-LM, here its Windows wheel
(litert-lm-api 0.18.0) - and the same .litertlm model files, so prompts
measured on Android behave the same way here.

The rules carried over:
  * exactly one engine, behind one lock: extraction, Ask, the brief and voice
    all want it, and interleaving them corrupts each other's output;
  * a fresh conversation for every call: history would grow prefill all day
    and leak one screen into the next screen's summary;
  * 4096 tokens: one screen is ~1-2k tokens in and ~150 out;
  * lazy load, and unload after each extraction pass unless pinned - several
    gigabytes held all day on a 7.7 GB laptop is the thing to avoid.
"""

import threading
import time

from .models import find_model
from .paths import load_settings

MAX_TOKENS = 4096
# Android unloads when the activity goes to the background. A desktop window
# has no such moment, so an engine left loaded by Ask or the brief is freed
# after this long unused.
IDLE_UNLOAD_S = 180


class GemmaEngine:
    def __init__(self, log=lambda *a: None):
        self._lock = threading.RLock()
        self._engine = None
        self._audio = False
        self._pins = 0
        self.state = "absent"           # absent | loading | ready | failed
        self.last_error: str | None = None
        self.last_load_s: float | None = None
        self.last_call: dict | None = None
        self.last_used = 0.0
        self.log = log

    @staticmethod
    def is_model_available() -> bool:
        return find_model() is not None

    def _ensure_loaded(self, audio=False):
        if self._engine is not None and (self._audio or not audio):
            return self._engine
        if self._engine is not None:
            # Loaded without the audio encoder; reload with it.
            self._close()
        model = find_model()
        if model is None:
            self.state = "absent"
            return None
        self.state = "loading"
        t0 = time.time()
        try:
            import litert_lm
            litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
            backend = (litert_lm.Backend.GPU() if load_settings()["backend"] == "gpu"
                       else litert_lm.Backend.CPU())
            kwargs = {"audio_backend": litert_lm.Backend.CPU()} if audio else {}
            # Compiled GPU programs and repacked weights are cached next to the
            # model (~1.5 GB, so not on C:). Measured: the first GPU load
            # took 81 s compiling; later loads read the cache.
            cache = model.parent.parent / "cache"
            cache.mkdir(parents=True, exist_ok=True)
            try:
                self._engine = litert_lm.Engine(str(model), backend=backend, max_num_tokens=MAX_TOKENS,
                                                cache_dir=str(cache), **kwargs)
            except Exception as e:
                if load_settings()["backend"] != "gpu":
                    raise
                # GPU is faster here (§9.1) but driver-dependent; a failed GPU
                # load falls back to the CPU rather than leaving no model.
                self.log("engine", f"GPU load failed ({e}); using CPU")
                self._engine = litert_lm.Engine(str(model), backend=litert_lm.Backend.CPU(),
                                                max_num_tokens=MAX_TOKENS, cache_dir=str(cache), **kwargs)
            self._audio = audio
            self.state = "ready"
            self.last_error = None
            self.last_load_s = time.time() - t0
            self.log("engine", f"loaded {model.name} backend={load_settings()['backend']} "
                               f"audio={audio} in {self.last_load_s:.1f}s")
            return self._engine
        except Exception as e:
            self.state = "failed"
            self.last_error = str(e)
            self._engine = None
            self.log("engine", f"load failed: {e}")
            return None

    def complete(self, prompt: str, on_delta=None, audio_path: str | None = None,
                 should_stop=lambda: False) -> str | None:
        """Runs one prompt to completion in a fresh conversation. None on any
        failure - the caller decides what that means (extraction stops the
        pass rather than marking rows judged)."""
        with self._lock:
            eng = self._ensure_loaded(audio=audio_path is not None)
            if eng is None:
                return None
            t0 = time.time()
            first = None
            out = []
            try:
                with eng.create_conversation() as conv:
                    if audio_path:
                        msg = {"role": "user", "content": [
                            {"type": "audio", "path": audio_path},
                            {"type": "text", "text": prompt}]}
                    else:
                        msg = prompt
                    for chunk in conv.send_message_async(msg):
                        for item in chunk.get("content", []) if isinstance(chunk, dict) else []:
                            if item.get("type") == "text" and item.get("text"):
                                if first is None:
                                    first = time.time() - t0
                                out.append(item["text"])
                                if on_delta:
                                    on_delta(item["text"])
                        if should_stop():
                            conv.cancel_process()
                            break
                text = "".join(out).strip()
                self.last_used = time.time()
                self.last_call = {"prompt_chars": len(prompt), "reply_chars": len(text),
                                  "first_token_s": first, "total_s": time.time() - t0}
                return text
            except Exception as e:
                self.last_used = time.time()
                self.last_error = str(e)
                self.log("engine", f"inference failed: {e}")
                return None

    def pin(self):
        with self._lock:
            self._pins += 1

    def unpin(self):
        with self._lock:
            self._pins = max(0, self._pins - 1)

    def unload(self, force=False):
        with self._lock:
            if self._pins and not force:
                return
            self._close()

    def unload_if_idle(self):
        # Non-blocking: if a call holds the lock, the engine is not idle.
        if not self._lock.acquire(blocking=False):
            return
        try:
            if (self._engine is not None and not self._pins
                    and time.time() - self.last_used > IDLE_UNLOAD_S):
                self._close()
        finally:
            self._lock.release()

    def _close(self):
        if self._engine is not None:
            try:
                self._engine.close()
            except Exception:
                pass
            self._engine = None
            self._audio = False
            self.log("engine", "unloaded")
        self.state = "absent"
