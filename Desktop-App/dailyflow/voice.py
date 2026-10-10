"""Voice notes: record, then hand the WAV straight to Gemma's audio encoder.

Port of VoiceRecorder.kt + the voice path of GemmaEngine. Android records
16 kHz mono 16-bit PCM because that is what Gemma's audio encoder takes;
the same format is recorded here through Windows' built-in MCI (winmm), so
no audio library has to be installed.

There is no separate speech-to-text step on either platform: the model hears
the audio. The recording stays in %LOCALAPPDATA%\\DailyFlow\\voice.
"""

import ctypes
import time
from datetime import datetime

from .paths import VOICE_DIR

winmm = ctypes.WinDLL("winmm")
winmm.mciSendStringW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint, ctypes.c_void_p]
winmm.mciGetErrorStringW.argtypes = [ctypes.c_uint, ctypes.c_wchar_p, ctypes.c_uint]

MAX_SECONDS = 60     # Gemma's audio input is bounded; Android caps notes too
ALIAS = "dailyflow_rec"


def _mci(cmd: str) -> str:
    buf = ctypes.create_unicode_buffer(256)
    err = winmm.mciSendStringW(cmd, buf, 255, None)
    if err:
        msg = ctypes.create_unicode_buffer(256)
        winmm.mciGetErrorStringW(err, msg, 255)
        raise RuntimeError(f"{msg.value} ({cmd.split()[0]})")
    return buf.value


class Recorder:
    def __init__(self):
        self.started_at: float | None = None
        self.path = None

    @property
    def recording(self) -> bool:
        return self.started_at is not None

    def start(self):
        if self.recording:
            return
        VOICE_DIR.mkdir(parents=True, exist_ok=True)
        try:
            _mci(f"close {ALIAS}")
        except RuntimeError:
            pass
        _mci(f"open new type waveaudio alias {ALIAS}")
        # 16 kHz, mono, 16-bit: bytespersec and alignment must agree or MCI
        # silently records at its 11 kHz default.
        _mci(f"set {ALIAS} time format ms bitspersample 16 channels 1 samplespersec 16000 "
             f"bytespersec 32000 alignment 2")
        _mci(f"record {ALIAS}")
        self.started_at = time.time()

    def elapsed(self) -> float:
        return time.time() - self.started_at if self.started_at else 0.0

    def stop(self) -> str | None:
        """Stops and saves. Returns the WAV path, or None if nothing usable."""
        if not self.recording:
            return None
        secs = self.elapsed()
        self.started_at = None
        path = VOICE_DIR / f"note-{datetime.now():%Y%m%d-%H%M%S}.wav"
        try:
            _mci(f"stop {ALIAS}")
            _mci(f'save {ALIAS} "{path}"')
        finally:
            try:
                _mci(f"close {ALIAS}")
            except RuntimeError:
                pass
        if secs < 0.8 or not path.exists():
            return None
        self.path = str(path)
        return self.path

    def cancel(self):
        if self.recording:
            self.started_at = None
            try:
                _mci(f"stop {ALIAS}")
                _mci(f"close {ALIAS}")
            except RuntimeError:
                pass
