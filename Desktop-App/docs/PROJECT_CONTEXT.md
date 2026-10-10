# DailyFlow Desktop — Project Context

**Purpose of this file:** the running record of what the Windows desktop version of DailyFlow
is, what has been decided, and what has been learned. Kept current as the design is discussed —
when a decision changes, edit it here rather than appending a contradiction.

**Last updated:** 2026-10-09
**Status:** Level 1 (capture → JSONL logs) prototype written, awaiting the user's verification
against real use. Nothing beyond Level 1 is to be built until that is signed off.
**Sibling:** [`../../Mobile-App`](../../Mobile-App) — the Android app this ports. Read its
[`docs/FEATURES.md`](../../Mobile-App/docs/FEATURES.md) for what the product does.

---

## 1. What this is

A Windows port of DailyFlow: a private, local-first memory layer that reads text from the apps
the user chooses, understands it with an on-device model, and surfaces commitments, requests and
deadlines. The same pipeline as the phone app, with the capture layer swapped:

| Stage | Android (exists) | Desktop (planned) |
|---|---|---|
| Screen capture | Accessibility Service | **Windows UI Automation (UIA)** |
| Notification capture | `NotificationListenerService` | WinRT `UserNotificationListener` |
| Dedup → store | Room | Local DB (TBD, e.g. SQLite) |
| Understanding | Gemma 4 E4B via LiteRT-LM | Gemma on-device — runtime TBD (§6) |
| Scheduling | WorkManager | Background service / timer (TBD) |

### Levels
Built one at a time; each is verified by the user before the next starts.

| Level | Scope | State |
|---|---|---|
| **1** | Capture text from allow-listed apps → JSONL logs; verify logs and allow-list | **Prototype + UI written** (§7) |
| 2+ | Storage, model runtime, extraction, reminders, screens — see §8.6 | Not started |

---

## 2. Capture: Windows UI Automation

UIA is Windows' accessibility layer — the API Narrator and NVDA read the screen through. It is the
direct equivalent of Android's `AccessibilityService`.

### Verified on the dev machine (2026-10-09, read-only)
- **No permission or grant is needed.** A plain Windows PowerShell script listed all 12 top-level
  windows across processes (VS Code, Chrome, WhatsApp, vivo Office Kit) and read the focused
  element. Unlike Android there is no user toggle — so consent has to be built by us (§3).
- **WhatsApp Desktop builds its tree lazily.** First probe: 4 elements (1 with text). After the
  Level 1 reader queried it with `ElementFromHandle` + `FindAllBuildCache`: **2,094 elements, ~250
  lines / ~5.5k chars of text** — while the window was *minimised*. So the earlier 4 was
  Chromium/WebView2 not yet having switched its accessibility tree on.
- **But WhatsApp is slow to read: ~6 s per full read** (5.7–8.5 s measured). Profiled: ~6,000 ms is
  inside `FindAllBuildCache` (WhatsApp serving the tree); the Python loop is ~20 ms. Control-view
  and content-view conditions do not help (same ~2,100 elements, same time). Untested levers:
  reading only the chat pane rather than the whole window, and whether a non-minimised window is
  faster.
- **An open (restored) WhatsApp window is ~13× bigger than a minimised one:** 26,349–26,394
  elements, **18–26 s per read**, and the 20,000-char cap is hit — so a capture is mostly chat
  list/sidebar and may never reach the open conversation (measured 2026-10-09). The foreground
  window is `WhatsApp.Root.exe`, class `WinUIDesktopWin32WindowClass` (WinUI host); the web UI
  itself lives in a child `msedgewebview2.exe` process.
- **Fixed: read only what is on screen.** The whole-window read of an open WhatsApp returned
  25,066 elements / **731,621 chars** in 27.3 s — everything WhatsApp had loaded, not what was
  displayed; `IsOffscreen` was set on 13,878 of them. The reader now walks the tree one level per
  call and skips any branch whose box is outside the window or which is flagged offscreen (with
  everything inside it); branches with no box are descended but not recorded. Same window:
  **~1–2.4 s, ~7,200–7,500 chars, 370 elements visited, not truncated.** End-to-end capture
  verified 2026-10-10.
- Pruning at *every* element without a box (first attempt) found only 187 chars — the WinUI host
  and WebView2 bridge layers have no box, so they must be descended into.
- First user test (2026-10-09) looked like WhatsApp was ignored: the logger traced nothing while
  an allowed app settled or was being read, so a 26 s read was indistinguishable from no capture.
  Fixed: a `read / reading` trace line is now written when a read starts.
- **`IsOffscreen` reported 0 elements offscreen on a minimised window**, so it cannot be relied on
  to mean "visible on screen" for WebView2 apps.
- Chrome page content: still untested (only 44 elements seen on the first probe).

### Android → Windows mapping

| Android (`CaptureService.kt`) | Windows UIA |
|---|---|
| `rootInActiveWindow` | `ElementFromHandle(GetForegroundWindow())` |
| `AccessibilityNodeInfo` walk (`NodeExtractor`) | `TreeWalker` (Control/Content view) or one cached `FindAll(Descendants)` |
| `TYPE_WINDOW_STATE_CHANGED` | `WindowOpened` / foreground change |
| `TYPE_WINDOW_CONTENT_CHANGED` | `StructureChanged` + `Name` property-changed |
| `TYPE_VIEW_TEXT_CHANGED` | `TextChanged`, `ValuePattern` |
| `TYPE_VIEW_FOCUSED` | `FocusChanged` |
| Node text | `Name`, `ValuePattern.Value`, `TextPattern.DocumentRange.GetText()` |
| Lock-screen guard | Free — lock screen and UAC run on the secure desktop, invisible to UIA |

### Capture loop (planned shape)
1. `SetWinEventHook(EVENT_SYSTEM_FOREGROUND)` → window handle → process name. Reads **no content**.
2. Process not on the allow-list (§3) → stop.
3. Register UIA handlers on **that app's window only** (`TreeScope.Subtree`), never desktop-wide.
4. Debounce (Android uses 1.2 s).
5. Fetch the subtree in **one** cached call (`CacheRequest` for `Name`, `ControlType`, `IsPassword`) —
   every uncached property read is a cross-process call and is slow on large trees.
6. Flatten text → dedup → queue for extraction.

Handlers run on a background thread and never block — the Windows version of Wick's main-thread
I/O landmine.

### Capture shape — aligned with the Android app (decided 2026-10-10)

**Principle, taken from Android: capture broadly, understand later.** The Android capture layer
does no judgement of meaning. It keeps every visible text node (`NodeExtractor`) and removes only
noise: privacy gates (deny-list, lock screen, window mismatch), icon-font glyphs, a 1.2 s
debounce, and dedup. *What is worth remembering* and *who said it* are decided afterwards by
Gemma (`ExtractionPrompt`) and enforced by `ExtractionParser`. Desktop Level 1 does the same:
the JSONL is raw screen text, and filtering for meaning is a later level.

**How names survive on Android — and on desktop.** Nothing extracts names at capture time.
They survive because (a) text is kept in reading order, so a contact's name precedes their
message — Android had a real bug where reversed traversal put a preview ahead of its contact and
the model misattributed it; (b) WhatsApp marks the owner as `Name (You)`, and the parser's
`rejectOwner` uses that marker to stop the owner being filed as a correspondent; (c) the
parser rejects pronouns ("me", "you") and UI labels ("Compose", "Search") offered as a
person. Notifications carry the sender explicitly: Android stores `title: body` (title = sender
or group), preferring `EXTRA_BIG_TEXT` over the truncated `EXTRA_TEXT`, dropping ongoing and
group-summary notifications and anything under 12 chars.

Desktop WhatsApp text is *better structured* than Android's for names: each chat row arrives as
one line — `<N> unread messages <chat name> <time> ~<sender> : <preview>` — and the owner's own
chat as `+91 … (You)`, so Android's `rejectOwner` rule applies unchanged. In the open chat,
messages appear as sender label (`You:`), body, then `<time> Read`.

| | Android | Desktop (now) |
|---|---|---|
| What is read | Visible nodes (Android's tree is mostly visible-only) | Visible-only pruned walk (§2) |
| Text per node | `text` + `contentDescription` | `Name` + `Value` (not a Document's Value — that is the page URL) |
| Order | Pre-order DFS, children pushed reversed | Same — text recorded when a node is *visited* (was: when its parent listed it, which split rows from their own pieces) |
| Icon glyphs | Private-Use-Area-only strings dropped | Same rule, ported |
| Joined with | spaces | **newlines** — one UI element per line, keeps row boundaries (deliberate difference) |
| Container repeats | n/a (Android containers rarely carry text) | Text already inside a kept ancestor's text is dropped; an aggregate label > 300 chars with textual children is dropped for its children |
| Duplicate screen copy | n/a | WinUI host + WebView2 expose the page twice: a node with the same name+box as an earlier *non-ancestor* node is skipped with its subtree |
| Exact dedup | 24 h, per app, persisted | 24 h, per app/site, seeded from `captures.jsonl` on start |
| Near dedup | Jaccard ≥ 0.80 vs last 40 in 24 h → drop | **Same gate**, plus: a screen it would drop that still has never-seen lines is stored as an `update` holding only those lines. See below |
| Notifications | `NotificationListenerService` | **Not yet** — WinRT `UserNotificationListener`, needs package identity (§4) |

**Dedup now = Android's gates + update records (2026-10-10, supersedes the line-only rule
below).** Analysis of the first 15-min session (99 captures, 294k chars): every capture was a
full screen; WhatsApp rescans repeated ~2,000 chars to add 1–5 lines. Android's gate 2 alone
would have dropped 35 of 99 — and 32 of those carried genuinely new lines (new messages, new
emails), the loss Android accepts because it reads only on screen-change events while desktop
re-reads on a 5 s timer. Implemented: gate 1 exact text (24 h), gate 2 Jaccard ≥ 0.80 vs the
last 40 *stored screens* (Android `tokenSet`: lower-cased letter/digit runs ≥ 3 chars) — and a
screen gate 2 would drop that has lines never seen (dates/ticks ignored via `line_keys`) is
written as `"type": "update"` with only those lines and `base_ts` = the screen it extends.
Replay of the session: 64 screens + 32 updates (6.2k chars) + 3 near-dup drops; stored text
294k → 209k chars (−30 %); new content lost: 0. Live: unchanged rescans → `exact-dup`.
What remains large: Gmail (43 full screens — genuinely different emails, overlap 0.4–0.6) and
WhatsApp rescans where the chat list reorders (overlap 0.72–0.78, under Android's 0.80, so
stored whole as on the phone).

Earlier rule, now replaced — **Why desktop dedup departs from Android (2026-10-10).** With the Jaccard ≥ 0.80 rule, a
group-chat screen before and after *one new message* overlapped 0.89 and the new message was
dropped — and that was with no member list; the desktop capture also carries the whole chat
list, so one message is a small share of the words. The rule now compares *lines*: whitespace
collapsed, lower-cased, digits masked (so clocks and unread counts don't count as change), lines
under 12 chars or 3 letters ignored. **Volatile words ignored too (2026-10-10):** the first
session made 99 captures in 15 min, mostly from Gmail dates ("Sat, Oct 4, 2025, 9:41 pm" counted
new 27× — masking digits left weekday/month) and WhatsApp ticks ("msg-dblcheck-delivered",
"9:41 pm Read"). `line_keys` now compares only the words of a line, minus numbers and
`VOLATILE_WORDS` (weekdays, months, am/pm, today/yesterday, delivered/read/sent/seen…), so a
line that is only a date, time, count or status never counts as new. Comparison only — the
captured text is unchanged. Tested: dates re-rendered → dropped; tick delivered→read → dropped;
new email, new message → kept. Tested earlier: unchanged rescan, clock tick, unread-count change,
return to an earlier screen → dropped; one new message (2 new lines), another chat's preview
changing (1 new line) → kept. Live: first read kept (40 new lines); a rescan 5 s later kept 11
genuinely new lines — WhatsApp was still loading/scrolling after being restored.

Measured on the same WhatsApp window after these changes: 1,659 chars (was ~7,500 with the
whole screen doubled and rows repeated), 186 elements, 1.5 s, one duplicate copy skipped.
First attempt at the duplicate rule skipped nested same-name/same-box wrappers and captured only
163 chars — hence the ancestor exception.

**Accessible text is not clipped to what is displayed (found 2026-10-10).** A group chat's
header shows the group name and a member list cut off with "…" after a few names, but its UIA
`Name` is the whole list — 8,582 chars, 500 phone numbers + "525 more" — in a 778×41 box. Each
element's text is now capped at roughly what its box can hold (6 px per glyph, 18 px per line at
96 DPI, scaled by the window's DPI when the process is DPI-aware; clipped only when longer than
1.5× that, and never below 80 chars so short icon-button labels stay whole). Result: member list
8,582 → 261 chars; 3 elements clipped on a typical WhatsApp screen; no line over 300 chars.
The cap is deliberately generous, so a few more members than are visible survive.
**Group member lists: removal tried and reverted (2026-10-10).** WhatsApp's group header is one
Button labelled `<group name> <member>, …, 525 more` (501 entries, 495 phone numbers, no child
elements). A text rule that removed the list and recovered the group name worked, but was
reverted by decision: it is a judgement about *meaning*, which Android leaves to the
understanding level, and it depended on guessing where the group name ends. **Clipping stays**
— it is about matching what is on screen, so the header keeps ~15 numbers and ends in "…". The
understanding level is expected to ignore them, as on the phone.

**The Android app does not handle rosters at all.** `NodeExtractor` keeps every node's full
`text`/`contentDescription` (no clipping, no list detection), `MemoryRepository` gates only on
duplicates, and the only bound is `ExtractionPrompt.MAX_SNIPPET_CHARS = 3000`, which truncates
the screen *at prompt time*. If the phone's WhatsApp exposes the full roster the way desktop
does (unverified — Android WhatsApp's group subtitle may be shorter), a large group's roster
would sit near the top of the screen text and consume most of those 3,000 chars, pushing the
actual messages out of what Gemma sees. Worth checking on the device; the same `roster_head`
rule would port directly into `NodeExtractor`.

Android's `TextView.getText()` is likewise unclipped by ellipsizing, so the phone app probably
captures full member lists too — unverified.

Still in the text and left for the understanding level, as on Android: navigation labels
(Chats, Calls, Status…), the window title with unread count, WhatsApp's long accessibility
description of the chat list, "Type a message to …", icon names like `msg-dblcheck-read`.

### Limits
- **Elevated (admin) windows are unreadable** from a normal-integrity process (UIPI). Bypassing
  needs `uiAccess=true` in a signed app installed under Program Files. Not planned.
- **Self-drawn UIs** (games, some Electron/Flutter apps, remote desktop windows) expose little or
  nothing. Fallback if needed: screenshot + on-device `Windows.Media.Ocr`.
- **Event floods**, especially from Chromium.

### Language / library
| Option | Notes |
|---|---|
| **C# + FlaUI (UIA3)** | Leading candidate — most practical UIA story |
| C++ `IUIAutomation` COM | Fastest, most work |
| Python `uiautomation` / `pywinauto` | Prototyping |
| Rust `uiautomation` crate | Viable |
| Kotlin/JVM | Poor — would need JNA to COM. Android capture code does not port directly |

---

## 3. Decision: allow-list, not deny-list

**Decided 2026-10-09.** The user picks the apps DailyFlow may read; **everything else is off by
default.**

**Why**, versus the Android app's current deny-list (`CapturePreferences.DEFAULT_EXCLUDED` — reads
every app except a blocked list):
- On Android the dangerous set is small and known (payment apps). On a PC nearly any window can
  be sensitive — banking sites, password managers, HR/medical portals, remote sessions.
- A deny-list reads anything new or unforeseen by default; an allow-list is private until opted in.
- Users want ~3–6 apps. Ticking a few beats blocking hundreds.
- Fewer events → fewer reads → fewer model inferences.

UIA has no OS-level per-app scoping, so the app enforces it (capture loop steps 1–3 in §2).

### First-run UX
- "Choose what DailyFlow can read": open/recent apps with icons, **nothing ticked**.
- Suggestions: WhatsApp, Outlook/Mail, Teams, Slack, Chrome/Edge.
- Editable any time; global **Pause**; tray icon shows when capture is active.
- Since Windows asks no permission, this screen *is* the consent — it must be explicit.

### Always-blocked backstop (even if the user allows the app)
Carried over from the Android deny-list in spirit:

| Android blocks | Desktop equivalent |
|---|---|
| Payment apps | Password managers (Bitwarden, 1Password, KeePass); banking/payment sites by URL |
| Lock screen | Lock screen / UAC (already invisible to UIA) |
| Keyboard | Any `IsPassword` field; on-screen keyboard |
| Launcher, System UI, Settings | Desktop, taskbar, Start, Settings, File Explorer |
| — | Remote desktop windows; DailyFlow's own windows |

### Browsers — per-site allow-list (built 2026-10-10)
"Allow Chrome" would mean every website, so `allowlist.json` has a `sites` list as well as
`apps`. In a Chromium browser (`BROWSERS`: Chrome, Edge, Brave, Vivaldi, Opera) that is not
itself ticked, the logger reads **only the address bar** — the first Edit control in the window,
"Address and search bar", whose Value is e.g. `mail.google.com/mail/u/0/#inbox` — and reads the
page only if the host matches a listed site (itself or a subdomain; `mail.google.com` does not
allow `google.com` or `…google.com.attacker.net`). Otherwise `site-not-allowed`, page untouched.
The host is re-checked after the read, and the text discarded if the tab navigated away mid-read.
A tab change counts as a switch (settle and dedup reset); each site is its own dedup source.
Ticking a browser as a whole app asks for confirmation. Firefox is not handled (different tree).

Verified 2026-10-10 with a test allow-list of `example.com`: example.com → captured, `site:
example.com`, 1,209 chars, 0.18 s; iana.org → `site-not-allowed`, nothing read.

**Chrome page text is fully exposed through UIA** (example.com: 50 elements, 0.6 s, all body text
including other scripts) — no `--force-renderer-accessibility` needed.

**Trace privacy (changed 2026-10-10):** drops for `not-allowed`, `site-not-allowed` and
`always-blocked` now record the program name only — no window title, no host. Titles of
everything not allowed amounted to a log of every page and document opened. Matches Android,
whose trace records only the package name.

**Ancestor rule narrowed:** text is dropped as a repeat only when a kept ancestor within 3 levels
contains it. *Correction:* this was prompted by example.com's heading apparently being dropped
for appearing in the window title. That was a misread test — the match was Chrome's **tab**
"Example Domain" in the tab strip; example.com's page has no heading element at all. The
3-level limit is kept as the safer rule, but no loss was ever observed.

**Browsers are read page-only (2026-10-10).** The first real session showed the whole Chrome
window being read for an allowed site: tab strip (titles of *every* open tab, including sites not
allowed), address bar (its URL changed per email and counted as new 21×), toolbar. That broke
"a site not ticked is never read". `Reader.page_of` now walks down from the window and stops at
the first on-screen Documents, taking the largest (side panels are Documents too); the read
starts there and the page title itself is not recorded. Verified with example.com and iana.org
in one window: capture has the page's 7 lines only — no "Address and search bar", "Tab search",
"New Tab", "Reload", "Bookmark", nor the other tab's title; read in 82 ms.

### Current allow-list (agreed 2026-10-10, checked against `capture/allowlist.json`)

One file, `Desktop-App/capture/allowlist.json`, is used by both the Level 1 window (`capture/app.py`)
and the full app's Capture page (`dailyflow_app.py`); the engine re-reads it on change.

| | Entry | Why |
|---|---|---|
| App ✅ | `WhatsApp.Root.exe` | most conversations and requests (re-ticked by the user, 11:07) |
| Site ✅ | `mail.google.com` | deadlines, bills, confirmations |
| Site ✅ | `calendar.google.com` | events are deadlines by definition |
| Site ✅ | `classroom.google.com` | assignment due dates |
| Site ✅ | `app.slack.com`, `teams.microsoft.com`, `discord.com` | team / project requests |
| Site ✅ | `web.whatsapp.com` | WhatsApp when used in the browser |
| Not allowed | Spotify | song names — no commitments, only noise (unticked on advice) |
| Not allowed | VS Code | code is noise and open files can hold secrets (`.env`, tokens) |
| Not allowed | `chrome.exe` as a whole | would expose every site incl. banking; specific sites instead |
| Removed | `mail.google.com.exe` | fake app entry from typing a site into "Add by name…"; that now redirects to Websites |

### Android parity (proposed, not done)
The phone app can move to the same model: set `AccessibilityServiceInfo.packageNames` from the
user's choices so **Android itself** delivers events only from those apps, keep an in-code
`isAllowed` check as a second guard, and keep today's `DEFAULT_EXCLUDED` as the backstop.
`NotificationListenerService` has no system-level filter, so its allow-list is in code.
Side benefit: Google Play scrutinises non-accessibility uses of the Accessibility API; explicit
opt-in helps.

---

## 4. Notifications
WinRT `UserNotificationListener` — requires user consent and package identity (MSIX packaging or
sparse package). Apply the same allow-list in code. Not yet investigated further.

---

## 5. Privacy principles (carried from Android)
- Captured text never leaves the machine; understanding is on-device.
- Password fields and secure surfaces are never read.
- Pause and per-app control are always one click away.
- Trace log of capture decisions, rotated (Android: `DebugLog`, 4 MB + one generation).

---

## 6. Open questions
1. ~~Does WhatsApp Desktop chat text appear once a UIA client queries it?~~ **Yes** (§2).
   ~~Read time~~ fixed by visible-only reading (§2). Still open: Chrome page content; whether to
   go further and read only the open chat pane (the visible chat list is currently included, since
   it is on screen).
2. On-device Gemma on Windows: LiteRT-LM lists desktop support — is there a usable Windows build,
   and at what version? Alternatives (llama.cpp / ONNX Runtime GenAI) if not. Must fit this
   laptop: 7.7 GB RAM, Iris Xe, no NPU (§8.5) — likely Gemma 4 **E2B**, not E4B. Does the
   chosen runtime take audio input (voice notes)?
2a. **Disk — resolved (2026-10-10):** C: has only 3 GB free, but E: (200 GB, 129 GB free) and
   H: (100 GB, 77 GB free) are internal fixed partitions. Model files and the Level 3 runtime
   (its venv / binaries) go on **E:**, e.g. `E:\DailyFlow\models`. C: stays tight for Windows
   itself (pagefile, updates), which is worth freeing a little anyway on 7.7 GB RAM.
2b. Prompt size on desktop: Android's 3,000-char cap would cut Gmail bodies (up to ~9.7k chars);
   re-size once tokens/char is measured on the Windows runtime (§8.4).
3. Language: C# + FlaUI (leading) vs others in §2.
4. Local storage and background scheduling choices.
5. Packaging: MSIX (needed for `UserNotificationListener`) vs plain installer.

---

## 7. Level 1 — capture logger (prototype)

**Python 3.11 + `comtypes`**, chosen for this level only because it is what's installed (no .NET
SDK on the dev machine) and it gets real logs fastest. The language for the real app is still
open question #3.

### Files
| File | Role |
|---|---|
| `capture/capture_logger.py` | `CaptureEngine` (the loop) + command-line entry point |
| `capture/app.py` | **Desktop UI** (tkinter, ships with Python) over the same engine |
| `capture/allowlist.json` | `{"apps": ["WhatsApp.Root.exe"]}` — exe names, case-insensitive, **re-read live** when saved. The single source of truth: the UI writes this file rather than keeping its own list |

### UI (`app.py`)
- **Left panel is two tabs, Apps and Websites** (2026-10-10) — stacked, the two lists did not fit
  and the apps list collapsed. Both are tick lists. Websites lists the suggested sites
  (`SUGGESTED_SITES`: mail, calendar, classroom, Slack, Teams, Discord, WhatsApp Web) plus any
  added; unticked ones stay listed via `site_options` in `allowlist.json`, which the engine
  ignores. "Add by name…" spots a website address and offers to add it under Websites — typing
  `mail.google.com` there had created a nonexistent program `mail.google.com.exe`.
- **Left — "Apps DailyFlow can read":** every app with a real window (visible, titled, unowned,
  not a tool window, not cloaked) plus allow-listed apps that aren't open. Click to tick/untick;
  writes `allowlist.json` atomically and the running engine picks it up within 0.4 s.
  Always-blocked apps are shown greyed and refuse to be ticked. "Add by name…" for apps not open.
- **Top right:** status (Capturing / Paused / Stopped), Pause/Resume, Start/Stop. Starting with
  nothing ticked asks first. Stop waits for an in-flight read (WhatsApp ~6 s).
- **Activity tab:** live counters (captured, not allowed, always blocked, duplicates, errors, last
  read time) and every trace decision, colour-coded. Unchanged rescans hidden unless toggled on.
- **Captures tab:** every capture; selecting one shows **exactly the text that was read**, with
  element count and password fields skipped.
- **Footer:** log path, open logs folder, open `allowlist.json`.
- Last 200 lines of both logs are loaded on open, so history survives a restart.
- The engine runs on a background thread with its own COM initialisation (UIA is per-thread COM);
  records reach the UI through a queue drained every 150 ms.
- Tested 2026-10-09: start → UI's own window traced `always-blocked` (python.exe + own-pid check),
  so DailyFlow never reads itself.

### Logs — outside the project on purpose
`%LOCALAPPDATA%\DailyFlow\logs\` (override with `--log-dir`). The project folder is on a
OneDrive-synced Desktop; logging there would upload raw screen text to the cloud. Each file
rotates at 4 MB, one generation kept (`.jsonl.1`), as on Android.

- **`captures.jsonl`** — one line per capture. `type` is `screen` (whole visible screen) or
  `update` (only lines new since the screen at `base_ts`; also `overlap`, `new_lines`). Fields: `ts`, `epoch_ms`, `exe`, `pid`, `title`,
  `trigger` (`switch` | `rescan`), `elements`, `skipped_password`, `skipped_offscreen`,
  `truncated`, `read_ms`, `text_chars`, `text`.
- **`trace.jsonl`** — every decision: `decision` (`start` | `config` | `read` | `drop` |
  `capture` | `error` | `stop`) and `reason`. `read / reading` marks a read starting:

| reason | meaning |
|---|---|
| `not-allowed` | foreground exe isn't in `allowlist.json` — **nothing was read** |
| `always-blocked` | on the backstop list (§3), even if allow-listed — nothing read |
| `locked` | lock screen / UAC up |
| `empty-text` | allowed, read, no text |
| `exact-dup` / `near-dup` | same text as a recent capture of that app / word overlap ≥ 0.80 |
| `allowlist-reloaded` | `allowlist.json` edited while running |

Drops are traced once per window, not every poll.

### Loop
Poll the foreground window every 0.4 s → cheap checks (backstop, allow-list) → wait until the
window has been in front **1.2 s** → read **only what is on screen** (pruned tree walk, §2; skip
`IsPassword`; cap 4,000 elements visited / 20,000 chars) → exact and Jaccard dedup per app (last 10) → write. While an
allowed app stays in front it is re-read every `max(5 s, 3 × last read time)` — the multiple
exists because WhatsApp reads take ~6 s.

### How to run and verify
```powershell
python -m pip install --user comtypes        # once
cd "Desktop-App\capture"
python app.py                                # UI
python capture_logger.py                     # or headless; Ctrl+C to stop
```
Verify: switch between WhatsApp and other apps → only `WhatsApp.Root.exe` lines in
`captures.jsonl`; everything else appears in `trace.jsonl` as `not-allowed`. Add/remove an exe in
`allowlist.json` while it runs → `allowlist-reloaded`, and behaviour follows.

### Verified so far (by Claude, 2026-10-09)
- Runs; VS Code in front → traced `not-allowed`, nothing read.
- Reader on WhatsApp (minimised): 2,094 elements, ~250 lines, ~6 s per read.
- **Not yet verified by the user** against real use.

### Known limits of this prototype
- Polling, not UIA events — simple, but rescans on a timer rather than when content changes.
- Reads everything visible in the window, including WhatsApp's visible chat list and sidebar,
  not just the open chat.
- "On screen" means inside the window and not flagged offscreen. Another window covering part of
  the foreground window is not accounted for.
- Captures whatever is displayed while an allowed app is in front ≥ 1.2 s — it cannot know
  whether the user actually read it.
- Reads block the loop (now ~1–2.5 s for WhatsApp).
- Browsers: Chromium only (Chrome, Edge, Brave, Vivaldi, Opera); Firefox is not handled.
- UWP apps hosted by `ApplicationFrameHost.exe` are blocked (the host is on the backstop list)
  rather than resolved to the real app.

---

## 8. Android → Windows: full port review (2026-10-10)

A complete read of `Mobile-App/` (~13k lines of Kotlin) with one question: what has to exist
on Windows for the same product. **The code is the source of truth**: `Mobile-App/docs/PROJECT_CONTEXT.md`
is dated 2026-09-05 and still describes the benchmark stage; `Mobile-App/docs/FEATURES.md`
matches the shipped app.

### 8.1 The Android architecture, end to end

```
CAPTURE (always on)
  CaptureService (Accessibility)  ─┐  gates: paused · lock screen · deny-list · window mismatch
  NotificationCaptureService       ─┤  NodeExtractor: text + contentDescription, reading order,
                                    │  icon glyphs dropped, ≤2000 nodes, joined with spaces
                                    ▼
  MemoryRepository.enqueue ── 1.2 s debounce per app (notifications not debounced)
  MemoryRepository.process ── exact text 24 h → Jaccard ≥0.80 vs last 40 (24 h) → embed (MobileBERT)
                                    ▼
  Room `memories` row: timestamp, packageName, rawText, eventType, extracted=false

UNDERSTAND (WorkManager, every 15 min, battery-not-low; or "Read my day")
  ExtractionWorker → foreground service (else the OS kills the multi-GB load)
  ExtractionRunner: load Gemma → for each pending row, ONE screen per inference:
     ExtractionPrompt(package, capturedAt, rawText≤3000, recent people ≤20, open commitments ≤12 numbered)
     → GemmaEngine.complete (fresh conversation, mutex, 4096 tokens)
     → ExtractionParser.parseAll (blocks split by ---)
     → per block: auto-tick COMPLETES (validated ordinal) · summary dedup Jaccard ≥0.55 vs 60 recent
       · saveExtraction (first block) / addDerived (further blocks, rawText copied) · schedule reminder
  → unload Gemma (steady-state memory between passes = 0)

USE
  Today: counts, morning brief (Gemma), open commitments + Draft follow-up (Gemma, never sent),
         today's timeline, people row
  Calendar: month grid placed at IFNULL(dueAt, timestamp), day sheet merged with device calendar
  Ask: MemorySearch (keyword; vectors only if a real embedder is sideloaded) → AskPrompt → stream
  Person: stats (open/kept/total/last), commitments vs interactions, Gemma relationship line
  Voice: 16 kHz mono WAV → Gemma audio encoder → same block format → save items
  Reminders: one per commitment at dueAt (date-only → 9 AM), re-checked before firing
  You: events captured, hours on, pause, manage app access, erase all, tuning, connect assistant
```

### 8.2 What transfers to Windows **unchanged** (the platform-independent brain)

These are prompts, rules and thresholds, each backed by a measured failure in the Android code.
Port them verbatim — they are the most valuable part of the mobile app.

| Piece | Android file | Rules to carry over exactly |
|---|---|---|
| Extraction prompt | `llm/ExtractionPrompt.kt` | 9 fixed `KEY: value` lines per block, `---` between blocks, one block per person/item; "worth / not worth remembering" lists; "event with nothing in it is not a memory"; "when unsure, prefer to record"; PEOPLE ALREADY KNOWN (≤20); STILL OPEN numbered ordinals (≤12); DUE resolved against CAPTURED AT, date-only → 00:00:00; QUOTE verbatim or empty |
| Why flat lines, one screen per call | same | JSON + batches of 5 measured: 2/5 batches unparseable, no speed gain (9.7 s/screen either way) → reverted |
| Parser | `llm/ExtractionParser.kt` | strip `-`/`*`/`**`, split on first colon; `NULLISH` set; **isMemory = a summary was written** (models contradict their own ISLOOP); quote kept only if it appears (whitespace/case-normalised) in rawText, ≥8 chars; person: reject `NOT_A_PERSON`, `UI_LABELS`, and names marked `(You)`/`(owner)`/`· you`/`- you` on screen; person ≤40 chars, project ≤60; category ∈ Work/Personal/Home/Other; due bounded −90 d … +3 y; midnight → 9 AM with `dueAtExplicit=false`; COMPLETES = digits only, >0, then validated against the list actually sent |
| Within-screen dedup | same | drop blocks whose summary repeats another block's on the same screen |
| Summary dedup | `MemoryRepository.hasSimilarSummary` | Jaccard ≥0.55 vs 60 recent summaries (24 h), skip when <3 tokens |
| Direction | `data/Direction.kt` | SELF_PROMISE / THEY_ASKED / FROM_THEM / MANUAL; unknown → FROM_THEM ("inventing an obligation is the worse failure"); `isObligation` = first two; card labels |
| Data model | `data/MemoryEntity.kt` | one table; `extracted` ≠ `summary != null` (else "nothing here" retries forever); `isLoop` from summary presence; derived rows copy parent rawText (so quotes still verify); `eventType` stored; `embeddingModel` stored with every vector; `dueAtExplicit`; `projectName` kept though unused; float vectors as **little-endian** BLOB |
| Queries | `data/MemoryDao.kt` | open commitment = `isLoop=1 AND commitmentDone=0 AND direction IN (SELF_PROMISE,THEY_ASKED)`; calendar placement `IFNULL(dueAt, timestamp)` (grid and day sheet must agree); person stats gated on direction; people ordered by `MAX(timestamp)` |
| Morning brief, relationship line, draft | `llm/BriefGenerator.kt` | "use only what is listed", 2–3 sentences, trimmed to 3 sentences; ≤15 / ≤12 memories; drafts are **shown, never sent**; return null (caller has a factual fallback) rather than an apology |
| Ask | `llm/AskPrompt.kt`, `llm/MemorySearch.kt` | refuse rather than guess; "MEMORIES (recent, may be unrelated)" when nothing matched; greet small talk; citation built from the rows used, never by the model; keyword search with stopwords and ≥4-char prefix matching both ways; TOP_K 8, MIN_SIMILARITY 0.35, RECENT_FALLBACK 4; vectors only with a real retrieval embedder (MobileBERT scored unrelated summaries 0.9975) |
| Voice prompt | `llm/VoicePrompt.kt` | same block format and parser; default is *keep* (the user chose to record); QUOTE always empty; one block per thing |
| Reminders | `work/Reminder*.kt` | one per memory id, replace on reschedule; past due fires immediately; **re-check before firing** (deleted / already sent / done / not a commitment → silent success); mark sent *after* showing; cancel on done/erase; "fire all now" tester |
| Engine discipline | `llm/GemmaEngine.kt`, `llm/InferenceEngine.kt` | exactly one model per process behind one lock (extraction, Ask, brief share it); fresh conversation every call (no history prefill, no cross-screen leakage); 4096-token KV budget (32K froze the phone); lazy load; unload after a pass and when the UI goes away, unless a pass has it pinned; GPU→CPU fallback with `initialize()` *inside* the try |
| Model download | `llm/ModelDownloader.kt`, `ModelCatalog`, `ModelLocator` | `.part` file + `Range` resume; follow redirects by hand (built-in follower drops `Range` across hosts); progress throttled (Vision-AI-Gemma emitted 390k updates); free-space check + 300 MB margin; validate against known sizes; locate by scanning for any model file ≥500 MB, largest wins |
| Privacy | throughout | raw text never leaves the device; only network use is the model download; no backup of the DB; pause; erase-all also cancels in-flight captures; trace log rotated |

### 8.3 What has to change on Windows

| Android | Role | Windows equivalent | Notes |
|---|---|---|---|
| AccessibilityService + NodeExtractor | screen text | **UIA reader (Level 1, built)** | visible-only walk, page-only in browsers, clipping, duplicate-copy skip |
| Accessibility events + 1.2 s debounce | when to read | 0.4 s foreground poll + 1.2 s settle + 5 s rescan | Android never re-reads an unchanged screen; desktop does → far more rows (99 in 15 min). Event-driven UIA reads would close the gap |
| NotificationListenerService | unopened messages | WinRT `UserNotificationListener` | needs package identity (MSIX / sparse package) and user consent |
| Room | store | **SQLite** (`sqlite3` in Python) with the same `memories` schema | plus desktop columns: `site`, `type` (screen/update), `baseId` |
| MediaPipe TextEmbedder (MobileBERT, bundled) | embeddings | **skip in v1** | Android stores vectors but uses them for nothing decisive (dedup is Jaccard, Ask is keyword). Add only with a real retrieval model |
| LiteRT-LM (Kotlin, Android) + Gemma 4 E4B | understanding | **open: needs a Windows runtime** | candidates: LiteRT-LM desktop build if usable on Windows, llama.cpp with a GGUF Gemma, ONNX Runtime GenAI (DirectML), Ollama. Requirements: streaming, one session at a time, fresh conversation, 4096 context; audio input only if voice notes are kept |
| WorkManager periodic 15 min + foreground promotion + battery-not-low | run extraction | in-process scheduler thread; battery via `GetSystemPowerStatus` | no OOM-killer like Android, but 7.7 GB RAM makes load→drain→unload just as important |
| NotificationChannel reminders | nudges | Windows toast notifications | unpackaged Python needs an AppUserModelID; reminders only fire while the app runs unless a scheduled task is used |
| CalendarContract (device calendar merge) | real events in Calendar tab | none equivalent without package identity | `calendar.google.com` is already captured through the browser; drop the merge in v1 |
| AudioRecord 16 kHz WAV → Gemma audio | voice notes | microphone capture (e.g. `sounddevice`) → runtime with audio input | if the runtime has no audio encoder, voice waits: a cloud speech recogniser would break "0 cloud calls" (the reason Android sends audio to Gemma) |
| Compose screens | UI | tkinter now; screens to rebuild: Today, Calendar, Ask, Person, Memories, Voice, You, Tuning, model setup | |
| `SourceApp` package → label/colour | badges | exe/site → label map | `WhatsApp.Root.exe` → WhatsApp, `mail.google.com` → Gmail, `calendar.google.com` → Calendar, `app.slack.com` → Slack, … |
| `DebugLog` (plain text) | trace | `trace.jsonl` (built) | |
| `CaptureStats` | "events seen", hours-on ring | derive from `trace.jsonl` | |
| Vision (camera/gallery) | — | **skip** | used only by the benchmark screen, not by the product |
| Cloud sync (Supabase) + MCP | deferred on Android | **skip** | Android's backend (migrations, Edge Functions, `mcp-auth`) was never committed and is gone |

### 8.4 Where the desktop text differs, and what the brain must account for

1. **Prompt wording.** The extraction prompt says the text is "flattened from the Android
   accessibility tree … roughly reading order, no speaker labels". Desktop text is one UI
   element per line, chat rows arrive whole (`<N> unread messages <chat> <time> ~<sender> :
   <preview>`), and group messages carry `Open chat details for <name> <number>`. The opening
   paragraph needs a desktop variant; the rules stay.
2. **Screen size vs `MAX_SNIPPET_CHARS = 3000`.** Android truncates each screen to 3,000 chars at
   prompt time (~750 tokens). Desktop screens measured 1,600–2,900 chars (WhatsApp) and up to
   9,700 (an open Gmail message): truncating at 3,000 would cut most of an email body. Re-size
   against the 4,096-token budget once the Windows runtime's tokens/char is measured.
3. **Update records.** An `update` holds only new lines. Extraction needs context for them —
   at least the window title and the sender/chat lines of the screen at `baseId` — or the model
   cannot attribute a lone new message.
4. **UI labels to reject as persons.** Add desktop furniture to `UI_LABELS`-style checks:
   "Open chat details for", "Type a message to …", "Search or start a new chat", "Chat list",
   "Profile details", Gmail's "Inbox", "Primary", "Promotions", "Compose".
5. **Owner marker.** WhatsApp Desktop also shows the owner as `+91 … (You)` — the Android
   `rejectOwner` rule works unchanged.
6. **Volume.** Android inference measured ~9.7 s per screen on a Snapdragon 8 Elite Gen 5 GPU.
   This laptop's CPU will be slower; with desktop capture volume the queue can outgrow a pass.
   Mitigations already in the Android design: pass per 15 min, pending queue drains over passes,
   stop the pass on engine failure (rows stay pending).

### 8.5 This laptop's hardware — decides the model

Checked 2026-10-10:

| | |
|---|---|
| RAM | **7.7 GB** total |
| CPU | Intel Core i5-1235U, 10 cores / 12 threads |
| GPU | Intel Iris Xe (integrated, shares system RAM) |
| NPU | none |
| Disk | C: 3 GB free of 175 GB; **E: 129 GB free** of 200 GB; H: 77 GB free of 100 GB (all internal) |

Consequences:
- **Gemma 4 E4B does not fit as-is.** Its files are 2.97 GB (GPU build) and 3.66 GB
  (universal); Google's CPU figure is ~3.3 GB of memory for E4B — on 7.7 GB alongside Chrome
  and WhatsApp. **E2B** (2.58 GB file, ~1.7 GB memory on CPU per Google's Android benchmark) is
  the realistic default here; E4B only if measured to fit.
- **Disk is not a blocker:** models and the runtime live on E: (129 GB free). The model path
  is configurable (Android's `ModelLocator` idea), not hard-coded to the user profile on C:.
- The Android "unload after every pass" discipline matters more here, not less.

### 8.6 Windows levels (status updated in §9)

| Level | Scope | Depends on |
|---|---|---|
| 1 | Capture → JSONL logs, allow-list, sites, dedup | **built; under user verification** |
| 2 | SQLite store with the `memories` schema (+ `site`, `type`, `baseId`); captures written there, JSONL kept as the readable log | — |
| 3 | Windows inference runtime + model download + benchmark (tok/s, memory, E2B vs E4B) | runtime choice (models on E:) |
| 4 | Extraction pass: port `ExtractionPrompt` / `ExtractionParser` verbatim + §8.4 changes; summary dedup; auto-tick | 2, 3 |
| 5 | Reminders as Windows toasts | 4 |
| 6 | Screens: Today (+ brief, draft), Calendar, Ask, Person, Memories | 4 |
| 7 | Voice notes | a runtime with audio input |
| 8 | Notification capture | package identity (MSIX) |
| — | Cloud sync / MCP | out of scope (backend gone) |

## 9. Full app built (2026-10-10)

The user asked to "build out the full app", so levels 2–7 were built in one go on top of Level 1.
Level 8 (notification capture) is not built: it needs MSIX package identity.

### 9.1 Runtime decision — LiteRT-LM on Windows, Gemma 4 E2B

- **Runtime: `litert-lm-api` 0.18.0** (PyPI, ships a native `win_amd64` wheel with `litert-lm.dll`).
  The same engine as the phone, so the same `.litertlm` files and the prompts measured on Android
  carry over. llama.cpp / Ollama / ONNX were not needed. Audio input is supported
  (`{"type":"audio","path":…}` content), so voice notes port too.
- **Model: `gemma-4-E2B-it.litertlm`** (2,588,147,712 bytes, `litert-community/gemma-4-E2B-it-litert-lm`),
  downloaded by the app's own resumable downloader to **`E:\DailyFlow\models`**. Cache
  (compiled GPU programs + repacked weights, ~1.5 GB) in `E:\DailyFlow\cache`.
- Measured on this laptop (i5-1235U, Iris Xe, 7.7 GB), real extraction prompt, one 9-line chat screen
  (~4.4k chars of prompt):

| Backend | Load | First token | One screen |
|---|---|---|---|
| CPU (default threads) | 13.8 s cold / 2.1 s cached | 22 s cold / 15 s cached | **80 s cold / 51 s cached** |
| GPU, first ever load | 81 s (shader compile) | 14 s | — |
| GPU, cached | 15 s | 9.9 s, then 2.7 s warm | **33–40 s** |
| (phone, E4B, for reference) | | | ~9.7 s |

  Synthetic short prompt: GPU decodes ~12 tok/s, CPU ~5.7 (4 threads 4.6, 10 threads 3.8 — more
  threads is slower on this 2P+8E chip). Process RSS with the model loaded: ~0.85 GB (weights mmapped).
  **Consequence:** a pass costs ~35 s per stored screen on GPU. Dedup at capture (Level 1) is what
  keeps this affordable; a 99-capture session would be ~1 hour of background reading.

### 9.2 Quality findings on E2B, and the extra checks (now OFF by default — see §9.8)

1. **Weekdays resolved wrong.** With Android's bare `CAPTURED AT: …Z` (UTC), "by Friday 5pm" on a
   Saturday came back as that Saturday and "Monday" as Sunday. Fixed: `CAPTURED AT` / `RECORDED AT`
   now give local time *and* the weekday (`2026-10-10T17:20:00 local time, Saturday 10 October 2026`).
   Local also matches `parse_due`, which reads DUE as local wall-clock.
2. **Chrome became a memory.** The input box "Type a message" came back as "You need to type a
   message", quoted exactly. Fixed in the parser: a block whose QUOTE is only a UI label is dropped.
   *Not enough on its own:* re-measured with the weekday named, "by Friday" still came back as
   Saturday the 17th — and again with the next seven dates listed by name in the prompt (kept,
   it does no harm). Final fix in the parser (`_match_named_weekday`): when the summary/quote
   names exactly one weekday and DUE falls in the coming week on a different weekday, DUE moves
   to that weekday's next date, keeping the time. Now lands on Fri 16 Oct 17:00.
2b. **An advert was kept** ("You are seeing an advertisement for a Diwali sale", PERSON "Sponsored").
   Fixed: a summary that itself says advert/advertisement/promotional/sponsored is not a memory.
2c. **Same-pass completion.** Android fetches STILL OPEN once per pass, so a request found on screen
   1 can't be ticked by "sent it" on screen 3 of the same pass. Now new obligations are appended
   to the list as they are saved. (In the test the model still didn't answer COMPLETES for "Sent
   the quotation just now", and the summary dedup — Jaccard ≥ 0.55, unchanged from Android —
   dropped that summary as a near-repeat of the request. Known limit; left as is.)
3. The model answers `ISLOOP: no` on blocks it clearly meant (all four, in the first test) — exactly
   the Android finding; the parser already keys off SUMMARY, so nothing was lost.

### 9.3 What was built, file by file

```
Desktop-App/
  dailyflow_app.py        the full window (run this)
  capture/                Level 1, unchanged except app.App can now be embedded (host=…)
  dailyflow/
    paths.py              %LOCALAPPDATA%\DailyFlow (db, logs, voice, settings.json); model dir setting
    models.py             ModelCatalog + ModelLocator + resumable ModelDownloader (E2B / E2B-GPU / E4B)
    store.py              Room `memories` → SQLite; ingest from captures.jsonl; MemoryDao queries
    extraction.py         ExtractionPrompt + ExtractionParser + Direction + VoicePrompt
    engine.py             GemmaEngine: one engine, one lock, fresh conversation per call, 4096 tokens
    pipeline.py           ExtractionRunner + scheduler + reminders (toasts) + DebugLog (app.jsonl)
    assist.py             BriefGenerator (brief, person line, draft) + AskPrompt + MemorySearch (keyword)
    voice.py              16 kHz mono WAV recorder via winmm MCI (no extra package)
```

- **Store ← logs, not ← engine.** The capture engine still only writes `captures.jsonl`; the store
  ingests new lines every 20 s and before each pass (byte offset in `meta`, `captureKey` unique, so
  re-reads never duplicate; rotation to `.jsonl.1` handled). Update records are linked to their base
  screen (`baseId`), and the base screen's head (700 chars) goes into the prompt as marked context.
- **Schema** = MemoryEntity minus embeddings (+ `site`, `title`, `captureType`, `baseId`,
  `captureKey`, `parentId`). Derived rows (2nd+ block of a screen) copy the parent's raw text, as on Android.
- **Scheduling.** Background thread: pass every 15 min (setting), first one a minute after start;
  "Read now" button; one pass at a time; cancellable between screens. Engine pinned during a pass and
  unloaded at its end; anything else (Ask, brief) unloads after 3 min idle.
- **Reminders.** No per-reminder timers: every 30 s the thread fires rows whose `dueAt` has passed,
  re-checking done / not-a-memory first (ReminderWorker's re-check, done continuously), marks
  `reminderSent` after showing. Toast via `winotify` (PowerShell-backed, no package identity needed).
  "Test reminders" = ReminderTester (doesn't mark sent).
- **Screens** (tkinter, same look as Level 1): Today (brief card — see §9.8, open commitments,
  today's timeline, Read now, Add task), Calendar (month grid, dated on deadline / undated on capture
  day — the observeMonth rule), Ask (keyword search → Gemma, streamed, refuses rather than guesses,
  citation built from rows used), People (stats + Gemma "where things stand"), Memories (filter),
  Voice, Capture (the Level 1 UI embedded), Model & settings (download/resume, backend CPU/GPU,
  speed test, intervals, test reminders, erase, live pipeline log).
- Every card: direction pill, source badge, due label, verified quote, Done / Draft follow-up
  (shown, never sent) / What was on screen / person / Delete.

### 9.4 Differences from Android that remain (after the §9.8 alignment)

Only what Windows forces:
- Prompt wording: "PC" and "Windows UI Automation tree" instead of phone / Android accessibility tree.
- **Update records** (Level 1 dedup; Android drops near-duplicates instead): the base screen's head
  (≤ 700 chars) is given as marked context, **inside** the 3000-char budget, and the quote/owner
  checks run against context + text.
- `UI_LABELS` also names desktop chrome (WhatsApp Desktop / Gmail labels) — the same rule, the
  platform's own buttons. "Google" deliberately not added (real sender).
- Reminders poll every 30 s instead of one WorkManager job per row — same re-check, same
  "spent once" behaviour, including skipping (and consuming) a row already done.
- Scheduled passes: a thread instead of WorkManager; same 15 min, same battery-not-low rule
  (skipped on battery ≤ 15 %), "Read now" ignores it.
- Engine unloads after 3 min idle (no "app went to background" moment on a desktop window).
- Not ported: embeddings/vector search, vision, device-calendar merge, cloud/MCP, Tuning/Bench screens
  (a one-screen speed test replaces them).

### 9.5 Status

| Level | Status |
|---|---|
| 1 Capture | built; awaiting user sign-off (logs archived to `archive/20261010-174636` for a fresh run) |
| 2 SQLite | built, tested (99 real captures ingested into a throwaway DB, idempotent) |
| 3 Runtime + model | built; E2B downloaded; benchmarked (§9.1) |
| 4 Extraction | built; parser unit-tested; end-to-end on synthetic captures (§9.6) |
| 5 Reminders | built |
| 6 Screens | built; every page opens without error |
| 7 Voice | built; needs a real microphone test by the user |
| 8 Notifications | not built (MSIX) |

### 9.6 End-to-end test (synthetic captures, throwaway DB, GPU)

Four records in a scratch `captures.jsonl`: a WhatsApp chat (Rahul asks for a quotation "by Friday
5pm"), a Gmail bill (Axis Bank, due 15 Oct), a Gmail advert, and an *update* record ("Sent the final
quotation just now") pointing at the chat. Result after the fixes above:

- ingest 4/4, update linked to its base screen; pass read 4 screens in 70 s (**8–22 s per screen**
  warm on GPU — about the phone's 9.7 s; the 33–40 s in §9.1 was the first calls after load);
- Rahul → THEY_ASKED, due **Fri 16 Oct 17:00** (explicit); Axis Bank → due Thu 15 Oct 09:00
  (not explicit — 9 AM anchor); advert → not a memory; update → summary dedup dropped it;
- Ask "What bill is due on 15 Oct?" → correct one-sentence answer, citation "Source ·
  mail.google.com, today" (25 s incl. model load);
- reminder for an overdue row fired once, not twice.

### 9.7 How to run

```
cd Desktop-App
python dailyflow_app.py        # or: pythonw dailyflow_app.py  (no console)
```
Capture page → tick apps/sites → Start capture. The store ingests every 20 s; Gemma reads every
15 min (or press **Read now** on Today). Requires `comtypes`, `litert-lm-api==0.18.0`,
`winotify==1.1.0` (all installed with `pip install --user`).

### 9.8 Aligned with the mobile logic (2026-10-10, at the user's request)

Trigger: the first real pass over the imported sessions failed on row 1 — `Input token ids are too
long … 5886 >= 4096`. Cause: the desktop-only `MAX_SNIPPET_CHARS = 6000`. The pass stops on an
engine failure (as on Android), so all 117 rows stayed pending. The user then asked that the app
follow the same logic as mobile. Done:

| Area | Was (desktop) | Now = Android |
|---|---|---|
| Screen text cap | 6000 chars | **3000** (`ExtractionPrompt.MAX_SNIPPET_CHARS`); update context counted inside it |
| Quote / owner checks | against the truncated text | against the row's full `rawText` (`parseAll(reply, row.rawText)`) |
| CAPTURED AT | local time + weekday + next-7-days table | **UTC `…Z` stamp** |
| DUE parsing | also accepted a bare date | `yyyy-MM-ddTHH:mm:ss` only |
| Weekday fix, advert rule, label-quote rule, same-pass tick-off | always on | **off**; `extra_checks` setting (Model & settings) turns all four on |
| Reply length | capped at 1024 tokens | no cap (as `GemmaEngine.complete`) |
| STILL OPEN order | undated last | `ORDER BY dueAt ASC` |
| Ask memory lines | included the due date | `[when, with person] summary` only |
| Morning brief | button, cached per day | **automatic, once per app open, only if the engine is already loaded**; fallback line from the counts (`fallbackBrief` wording) |
| Person line | button, cached | automatic once per person per app open, only if loaded; `fallbackLine` wording |
| Voice note | saved straight away | items shown, **Save** stores them as manual memories (`VoiceViewModel.save` → `addManual`) |
| Scheduled pass | always | skipped when on battery ≤ 15 % (`setRequiresBatteryNotLow`) |
| Reminder for a row already done | left pending | consumed silently (ReminderWorker returns success) |

Checked after the change: all 117 imported prompts tokenise to 1,162–2,946 tokens (median 1,775),
none near 4,096; with `extra_checks` off the parser reproduces the phone's behaviour on the test
reply (keeps "Type a message" and the advert, keeps the model's Saturday date, rejects a bare
date); with it on, the four fixes apply. Every page opens; the brief card shows "Still reading
your day." while rows are pending, exactly the phone's fallback.

Archived sessions can be imported (Model & settings → Import archived captures…,
`dailyflow/importer.py`): records are replayed through today's Level 1 dedup with each record's own
time, then inserted idempotently. 10:43 + 10:55 sessions → 117 rows (82 screens, 35 updates, all
linked to their base screen).

## 10. Phone ↔ PC sync (built 2026-10-10)

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

