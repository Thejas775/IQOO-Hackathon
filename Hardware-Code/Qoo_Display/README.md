# Qoo_Display — DailyFlow companion device

The hardware half of DailyFlow: an ESP32-S3 watch board that shows the QOO mascot in
whichever state the system is in, captures a bounded voice take, and streams it to the
phone. The phone does all understanding — the device carries no model.

**Board:** Elecrow CrowPanel 2.01in HMI watch · ESP32-S3R8 · 240×296 GC9309 · AXS5106L
touch · PDM mic · 16 MB flash · 8 MB octal PSRAM

---

## Build and flash

ESP-IDF v5.4.4, installed via EIM. `export.ps1` alone does **not** work — use the
EIM-generated profile:

```powershell
. "C:\Espressif\tools\Microsoft.v5.4.4.PowerShell_profile.ps1"
idf.py -B C:\esp\b\qoo build
idf.py -B C:\esp\b\qoo -p COM7 flash monitor
```

`-B` keeps the build directory short. Not strictly required from this path, but the
habit matters — object paths overflow CMake's 250-char limit from deeper trees.

---

## Interaction

No on-screen controls, by design: on stage the device should look like it is simply
running. Two gestures, anywhere on the glass.

| State | Mascot | Shows | Long press (500 ms) | Short tap |
|---|---|---|---|---|
| Pairing | Waving | 6-digit code | accept pairing | — |
| Ready | Resting | READY | start recording | mute |
| Recording | Running | `mm:ss` elapsed | stop | — |
| Thinking | Thinking | THINKING | *(ignored)* | *(ignored)* |
| Muted | Sleeping | MIC MUTED | unmute | unmute |

A long press fires **on the hold**, not on release, so the screen reacts while your
finger is still down — no aim required and nothing to fumble.

**Recording is capped at 180 s** (`RECORD_CAP_S`). The cap lives on the device, not in
the app, because the device owns the microphone: if the link drops mid-take it still has
to stop on its own.

**The mute state is the privacy indicator.** QOO asleep means the microphone is actually
off. That is the honest answer to "it records all the time" — anyone in the room can read
the device's state without being told.

---

## What is simulated

Two transitions are stubbed so the whole flow is demonstrable before the network layer
exists. Both are marked `SIMULATED` in `main.c` and are the lines the network code
replaces:

1. **Pairing acceptance** — a long press stands in for the phone confirming the code.
2. **Extraction finishing** — the device returns to Ready after 3 s instead of being told.

Everything else is real.

---

## Layout

| File | Role |
|---|---|
| `main/main.c` | State machine, gesture timing, pairing code |
| `main/display.c/.h` | GC9309 bring-up, framebuffer compositor, slide transitions |
| `main/touch.c/.h` | AXS5106L reads |
| `main/qoo_frames.c/.h` | Generated mascot art, one frame per `Qoo` enum state |
| `main/qoo_ui.c/.h` | Generated digit sheet and state labels |
| `tools/mkqoo.py` | Regenerates the mascot frames from the app's drawables |
| `tools/mkui.py` | Regenerates the digits and labels |

Regenerate assets after changing the art or the wording:

```bash
python tools/mkqoo.py main/qoo_frames.c
python tools/mkui.py main/qoo_ui.c main/qoo_ui.h
```

Both write PNG previews next to the scripts — check those before flashing.

---

## Three things that will bite you

**The touch interrupt pin is a report-ready pulse, not a "finger down" level.** Gating
I2C reads on it — which Lesson03 does, and which this firmware originally copied — makes
a sustained press read as rapid press/release chatter. The gesture layer then sees a
stream of completed short taps and a long press can never accumulate its hold time; the
observed symptom was the device flipping between READY and MIC MUTED and never pairing.
`touch.c` now polls the data register every cycle and ignores the interrupt line
entirely, with a two-sample debounce on top. Do not reintroduce the gate.


**Pixels are big-endian RGB565.** Confirmed from the factory Arduino source, where
`LV_COLOR_16_SWAP` is 1 and the sketch calls `draw16bitBeRGBBitmap()`. Get it backwards
and QOO's amber turns blue. Note that Lesson03 of the vendor course does *not* byte-swap
in its flush callback — do not use it as the colour reference.

**SPI DMA cannot read memory-mapped flash.** The generated frames are `const` arrays, so
every band is copied into an internal DMA buffer before transfer. `display.c` already does
this; don't "optimise" the copy away.

---

## Next

- PDM microphone capture (Lesson13 of the vendor course is the reference)
- WiFi join + UDP announce `{code, ip}` + TCP audio stream
- Replace both `SIMULATED` transitions with real messages from the phone

On the app side, QOO-originated memories want a `"qoo.device"` pseudo-package and a
matching `SourceApp("QOO", amber…)` in `ui/SourceApp.kt`, alongside the existing
`ManualSource` / `CalendarSource` entries. No schema change needed.
