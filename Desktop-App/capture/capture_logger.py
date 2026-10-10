"""
DailyFlow Desktop - Level 1: capture logger.

Reads the on-screen text of allow-listed apps through Windows UI Automation
and appends each capture to a JSONL file. Every decision - captured or dropped,
and why - goes to a second JSONL trace, so the allow-list can be verified from
the logs alone.

Nothing here leaves the machine, and nothing is understood yet: this level
exists only to prove that capture produces the right text from the right apps.

Run:   python capture_logger.py            (Ctrl+C to stop)
Logs:  %LOCALAPPDATA%\\DailyFlow\\logs\\captures.jsonl, trace.jsonl
"""

import argparse
import ctypes
import ctypes.wintypes as wt
import datetime
import json
import math
import os
import re
import sys
import threading
import time
from pathlib import Path

import comtypes
import comtypes.client

UIA = comtypes.client.GetModule("UIAutomationCore.dll")

HERE = Path(__file__).resolve().parent
DEFAULT_ALLOWLIST = HERE / "allowlist.json"

# Outside the project folder on purpose: the project sits on a OneDrive-synced
# Desktop, and logs written there would upload raw screen text to the cloud.
DEFAULT_LOG_DIR = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "DailyFlow" / "logs"

POLL_S = 0.4
# A window must hold the foreground this long before it is read, so a quick
# alt-tab through several apps produces one capture, not one per app passed.
# Same figure as the Android app's debounce.
SETTLE_S = 1.2
# While an allowed app stays in front, re-read it this often so new messages
# arriving in an open chat are still captured.
RESCAN_S = 5.0
# Measured 2026-10-09: one read of WhatsApp Desktop (WebView2, ~2,100
# elements) takes ~6 s, all of it inside WhatsApp answering FindAllBuildCache;
# the Python side is ~20 ms. A fixed 5 s rescan would therefore read without
# pause, so the gap stretches to a multiple of the last read's duration.
RESCAN_READ_MULTIPLE = 3

MAX_ELEMENTS = 4000
MAX_TEXT_CHARS = 20000
MAX_LOG_BYTES = 4 * 1024 * 1024

# Dedup: the Android app's two gates, unchanged, plus one desktop addition.
#
# Android (MemoryRepository.process), per app, over the last 24 h:
#   1. exact text seen before                      -> drop ("exact-dup")
#   2. word overlap >= 0.80 with any of the last 40 -> drop ("near-dup")
# Overlap is Jaccard over Android's tokenSet: lower-cased letter/digit runs of
# three or more characters. Only screens that pass both are stored, and only
# stored screens are compared against, exactly as only Room rows are.
#
# The addition: a screen gate 2 would drop that still has lines never seen
# before is stored as an "update" holding only those lines, not dropped and
# not stored whole. Measured on the first real session (2026-10-10, 99
# captures, 294k chars in 15 min): gate 2 alone would have dropped 35, and 32
# of them carried new lines - WhatsApp messages arriving in an open chat, which
# change a few lines of a 2,000-char screen and so overlap 0.8-0.9. Android
# can afford that loss because it reads only when the screen changes; desktop
# re-reads on a timer, so it needs to keep the small change without repeating
# the large unchanged part.
DEDUP_WINDOW_S = 24 * 60 * 60
RECENT_PER_APP = 40
OVERLAP_THRESHOLD = 0.80
# What counts as a line worth noticing: long enough and wordy enough that a
# clock ("9:52 pm") or a bare count ("99+") is not "new". Numbers, dates and
# delivery statuses are ignored when comparing (see VOLATILE_WORDS), so
# "6 unread messages <chat>" is the same line as "5 unread messages <chat>";
# a changed preview still differs.
NEW_LINE_MIN_CHARS = 12
NEW_LINE_MIN_LETTERS = 3

# A node's text longer than this, when its descendants carry text too, is an
# aggregate label (WhatsApp gives its root a description concatenating half
# the screen) and is dropped in favour of the children. A chat row's own label
# - "5 unread messages <chat> Yesterday ~<sender> : <preview>" - is well under.
AGGREGATE_MAX_CHARS = 300
ANCESTOR_LEVELS = 3

# An element's accessible text is not clipped to what it displays. A WhatsApp
# group header is a 778x41 box showing "Name / member, member, ..." cut off
# after a few names, but its Name is the whole member list: 8,582 characters,
# 500 phone numbers (measured 2026-10-10). Text is therefore capped at roughly
# what the box can hold. Generous on purpose - a narrow average glyph and a
# tall line, then 1.5x slack - so only text far beyond its box is touched; a
# message bubble grows to fit its text and is never near the cap.
CLIP_CHAR_PX = 6      # average glyph width at 96 DPI, on the narrow side
CLIP_LINE_PX = 18     # line height at 96 DPI
CLIP_SLACK = 1.5
# Short text is never clipped. Icon buttons carry labels like "Mute chat"
# in boxes too small to "hold" them; clipping those produced fragments such
# as "Mute…" and no benefit, since the problem is only ever long text.
CLIP_MIN_CHARS = 80

# Blocked even if someone adds them to the allow-list. Shell surfaces with no
# content of their own, password managers, and remote sessions whose content
# belongs to another machine. Lower-cased.
ALWAYS_BLOCKED = {
    "explorer.exe", "searchhost.exe", "startmenuexperiencehost.exe",
    "shellexperiencehost.exe", "systemsettings.exe", "textinputhost.exe",
    "lockapp.exe", "applicationframehost.exe",
    "mstsc.exe", "msrdc.exe",
    "1password.exe", "bitwarden.exe", "keepass.exe", "keepassxc.exe",
    "python.exe", "pythonw.exe",
}

# Chromium browsers. Their address bar is the first Edit control in the
# window's tree (verified on Chrome 2026-10-10: "Address and search bar",
# Value "example.com"), which is what per-site rules read. Firefox exposes its
# tree differently and is not handled yet.
BROWSERS = {"chrome.exe", "msedge.exe", "brave.exe", "vivaldi.exe", "opera.exe"}
# Elements examined while looking for a browser window's page; the browser's
# own UI above the page is a few dozen.
PAGE_SEARCH_MAX = 400

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
# A DPI_AWARENESS_CONTEXT is a pointer-sized handle; ctypes' default int would
# truncate it on 64-bit Windows.
user32.GetThreadDpiAwarenessContext.restype = ctypes.c_void_p
user32.GetAwarenessFromDpiAwarenessContext.argtypes = [ctypes.c_void_p]
user32.GetDpiForWindow.argtypes = [wt.HWND]
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
DESKTOP_SWITCHDESKTOP = 0x0100


# ---------------------------------------------------------------- logging --

class JsonlLog:
    """Append-only JSONL with one rotated generation, like the Android trace."""

    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)

    def write(self, record: dict):
        try:
            if self.path.exists() and self.path.stat().st_size > MAX_LOG_BYTES:
                old = self.path.with_suffix(self.path.suffix + ".1")
                if old.exists():
                    old.unlink()
                self.path.rename(old)
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
        except OSError as e:
            print(f"  ! could not write {self.path.name}: {e}", file=sys.stderr)


def now_fields() -> dict:
    t = datetime.datetime.now().astimezone()
    return {"ts": t.isoformat(timespec="milliseconds"), "epoch_ms": int(t.timestamp() * 1000)}


# ------------------------------------------------------------- foreground --

def is_locked() -> bool:
    # The input desktop cannot be opened while the secure desktop (lock
    # screen, UAC) is up.
    h = user32.OpenInputDesktop(0, False, DESKTOP_SWITCHDESKTOP)
    if not h:
        return True
    user32.CloseDesktop(h)
    return False


def exe_of(pid: int) -> str | None:
    h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return None
    try:
        buf = ctypes.create_unicode_buffer(1024)
        size = wt.DWORD(len(buf))
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            return os.path.basename(buf.value)
        return None
    finally:
        kernel32.CloseHandle(h)


def foreground() -> dict | None:
    hwnd = user32.GetForegroundWindow()
    if not hwnd:
        return None
    pid = wt.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    length = user32.GetWindowTextLengthW(hwnd)
    title = ctypes.create_unicode_buffer(length + 1)
    user32.GetWindowTextW(hwnd, title, length + 1)
    return {"hwnd": hwnd, "pid": pid.value, "exe": exe_of(pid.value) or "?", "title": title.value}


# ---------------------------------------------------------------- reading --

class Reader:
    """Reads the text that is actually on screen in a window, in reading order.

    This is the desktop counterpart of the Android app's NodeExtractor, and
    keeps its two rules: text comes out in the order a person reads it, and
    icon-font glyphs are not text. Four more exist because of what a WhatsApp
    Desktop capture looked like before them (measured 2026-10-09/10):

    1. Only what is on screen. Fetching the whole subtree returned 25,066
       elements and 731,621 characters - every loaded chat and message - in
       27 s. The walk now goes one level per call and skips any branch whose box
       is outside the window or which is flagged offscreen, with everything
       inside it: ~1-2.5 s, ~7,500 characters. Branches with no box (the WinUI
       host, the WebView2 bridge) are descended into, not pruned - pruning them
       found almost nothing.

    2. Text is recorded when a node is visited, not when its parent lists it.
       Recording siblings as a batch emitted every chat row first and then
       every row's pieces afterwards, separating a row's sender from its own
       message - the same failure Android's reversed-push bug produced, where a
       preview came out ahead of its contact name and the model attributed it
       to the wrong person.

    3. The screen is exposed twice. The WinUI host and WebView2 both surface
       the same web content, so every visible line appeared twice. A node with
       the same text and the same box as one already seen is skipped with its
       whole subtree.

    4. Containers repeat their children. A chat row is labelled with its full
       text and then each cell repeats a piece of it. Text already contained
       in a kept ancestor's text is not recorded again; an aggregate label over
       AGGREGATE_MAX_CHARS whose children carry text is dropped in favour of
       those children.
    """

    PROPS = (
        UIA.UIA_NamePropertyId,
        UIA.UIA_ValueValuePropertyId,
        UIA.UIA_IsPasswordPropertyId,
        UIA.UIA_IsOffscreenPropertyId,
        UIA.UIA_BoundingRectanglePropertyId,
        UIA.UIA_ControlTypePropertyId,
    )

    def __init__(self):
        self.uia = comtypes.client.CreateObject(UIA.CUIAutomation, interface=UIA.IUIAutomation)
        self.cache = self.uia.CreateCacheRequest()
        for p in self.PROPS:
            self.cache.AddProperty(p)
        self.all = self.uia.CreateTrueCondition()
        self.is_edit = self.uia.CreatePropertyCondition(UIA.UIA_ControlTypePropertyId,
                                                        UIA.UIA_EditControlTypeId)
        self.address_bars: dict[int, object] = {}

    def browser_host(self, hwnd: int) -> str | None:
        """The host in a browser window's address bar, or None.

        Reads only the address bar, never the page, so a site that is not
        allowed is identified without any of its content being touched. The
        bar is found once per window and then re-read each poll, which is one
        cross-process call.
        """
        bar = self.address_bars.get(hwnd)
        for _ in (0, 1):
            if bar is None:
                try:
                    bar = self.uia.ElementFromHandle(hwnd).FindFirst(UIA.TreeScope_Descendants, self.is_edit)
                except Exception:
                    bar = None
                if bar is None:
                    return None
                self.address_bars[hwnd] = bar
            try:
                value = bar.GetCurrentPropertyValue(UIA.UIA_ValueValuePropertyId)
                return normalise_site(value) if isinstance(value, str) else None
            except Exception:
                # The window was rebuilt (a tab dragged out, a restart);
                # find the bar again once.
                self.address_bars.pop(hwnd, None)
                bar = None
        return None

    @staticmethod
    def _text_of(el) -> str:
        """Name, plus Value where it adds something. A Document's Value is its
        URL (WebView2 reports web.whatsapp.com with query parameters), not
        content, so it is left out."""
        parts = []
        name = el.GetCachedPropertyValue(UIA.UIA_NamePropertyId)
        if isinstance(name, str) and name.strip():
            parts.append(name.strip())
        if el.GetCachedPropertyValue(UIA.UIA_ControlTypePropertyId) != UIA.UIA_DocumentControlTypeId:
            value = el.GetCachedPropertyValue(UIA.UIA_ValueValuePropertyId)
            if isinstance(value, str) and value.strip() and value.strip() not in parts:
                parts.append(value.strip())
        text = " ".join(parts)
        return "" if is_icon_glyph_only(text) else text

    def page_of(self, hwnd: int):
        """The web page shown in a browser window: its largest on-screen
        Document. None if there is none.

        Found by walking down from the window and stopping at the first
        Documents met, rather than by a tree-wide search: the browser's own
        UI is a few dozen elements, while a tree-wide FindAll also walks the
        whole page (an open WhatsApp window took 27 s that way). Side panels
        are Documents too, which is why the largest is taken.
        """
        r = wt.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(r))
        win = (r.left, r.top, r.right, r.bottom)
        best, best_area, visited = None, 0, 0
        level = [self.uia.ElementFromHandle(hwnd)]
        while level and visited < PAGE_SEARCH_MAX:
            nxt = []
            for el in level:
                try:
                    kids = el.FindAllBuildCache(UIA.TreeScope_Children, self.all, self.cache)
                except Exception:
                    continue
                for i in range(kids.Length):
                    kid = kids.GetElement(i)
                    visited += 1
                    rect = kid.GetCachedPropertyValue(UIA.UIA_BoundingRectanglePropertyId)
                    if (not rect or len(rect) < 4 or rect[2] <= 0 or rect[3] <= 0
                            or kid.GetCachedPropertyValue(UIA.UIA_IsOffscreenPropertyId)):
                        continue
                    x, y, w, h = rect
                    if not (x < win[2] and x + w > win[0] and y < win[3] and y + h > win[1]):
                        continue
                    if kid.GetCachedPropertyValue(UIA.UIA_ControlTypePropertyId) == UIA.UIA_DocumentControlTypeId:
                        if w * h > best_area:
                            best, best_area = kid, w * h
                    else:
                        nxt.append(kid)
            # Stop at the first level that held a page: deeper Documents are
            # frames inside it and are read as part of it.
            if best is not None:
                break
            level = nxt
        return best

    def read(self, hwnd: int, page_only: bool = False) -> dict:
        """Reads the window - or, with page_only, only the web page in it,
        leaving out the tab strip, address bar and toolbar. In a browser the
        tab strip lists the titles of every open tab, including sites that
        are not allowed, so a browser is always read page-only."""
        start = time.perf_counter()
        seen = skipped_password = skipped_offscreen = skipped_duplicate = 0

        # A minimised window has nothing on screen to read.
        if user32.IsIconic(hwnd):
            return self._result("", 0, 0, 0, 0, False, start)

        root = self.uia.ElementFromHandle(hwnd)
        if page_only:
            root = self.page_of(hwnd)
            if root is None:
                return self._result("", 0, 0, 0, 0, False, start)

        r = wt.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(r))
        win = (r.left, r.top, r.right, r.bottom)

        # Boxes arrive in physical pixels when this process is DPI-aware (the
        # UI makes it so) and in 96-DPI pixels when it is not (the CLI), so
        # the clip constants are scaled only in the first case.
        clipped = 0
        aware = user32.GetAwarenessFromDpiAwarenessContext(user32.GetThreadDpiAwarenessContext())
        scale = (user32.GetDpiForWindow(hwnd) or 96) / 96 if aware else 1.0

        def has_box(rect):
            return bool(rect) and len(rect) >= 4 and rect[2] > 0 and rect[3] > 0

        def on_window(rect):
            x, y, w, h = rect
            return x < win[2] and x + w > win[0] and y < win[3] and y + h > win[1]

        # Pass 1: walk what is on screen, recording each node when it is
        # visited, so `nodes` is in reading order. Each entry is
        # [text, parent index, has-text-descendant].
        nodes: list[list] = []
        # (name, box) -> index of the first node seen with it. A later node
        # with the same identity is a second copy of the screen, unless the
        # first is its own ancestor: WhatsApp nests a frame and the document
        # inside it under one name and one box, and treating the inner one as
        # a copy threw the whole page away (163 chars captured instead of ~7k).
        identities: dict[tuple, int] = {}
        truncated = False
        # The starting element's own text is not recorded: for a window it
        # is the title, for a page the page title, and both are kept in the
        # capture's "title" field already.
        stack = [(root, -1, False)]   # (element, parent, has cached props)
        while stack:
            el, parent, cached = stack.pop()
            index = parent
            if cached:
                name = el.GetCachedPropertyValue(UIA.UIA_NamePropertyId)
                rect = el.GetCachedPropertyValue(UIA.UIA_BoundingRectanglePropertyId)
                if isinstance(name, str) and name.strip() and has_box(rect):
                    identity = (name.strip(), tuple(round(v) for v in rect))
                    first = identities.get(identity)
                    if first is None:
                        identities[identity] = len(nodes)
                    else:
                        p = parent
                        while p >= 0 and p != first:
                            p = nodes[p][1]
                        if p != first:
                            skipped_duplicate += 1
                            continue
                text = self._text_of(el)
                if text and has_box(rect):
                    capacity = (math.ceil(rect[2] / scale / CLIP_CHAR_PX)
                                * max(1, round(rect[3] / scale / CLIP_LINE_PX)))
                    if len(text) > max(CLIP_MIN_CHARS, capacity * CLIP_SLACK):
                        text = text[:max(capacity, CLIP_MIN_CHARS)].rstrip(" ,") + "…"
                        clipped += 1
                index = len(nodes)
                nodes.append([text, parent, False])
                if text:
                    p = parent
                    while p >= 0 and not nodes[p][2]:
                        nodes[p][2] = True
                        p = nodes[p][1]
            try:
                kids = el.FindAllBuildCache(UIA.TreeScope_Children, self.all, self.cache)
            except Exception:
                # The app rebuilt that part of its tree mid-read (WhatsApp does
                # this after a restore). Skip the branch, keep the rest.
                continue
            batch = []
            for i in range(kids.Length):
                kid = kids.GetElement(i)
                seen += 1
                rect = kid.GetCachedPropertyValue(UIA.UIA_BoundingRectanglePropertyId)
                if has_box(rect) and (not on_window(rect)
                                      or kid.GetCachedPropertyValue(UIA.UIA_IsOffscreenPropertyId)):
                    skipped_offscreen += 1
                    continue
                if kid.GetCachedPropertyValue(UIA.UIA_IsPasswordPropertyId):
                    skipped_password += 1
                    continue
                batch.append((kid, index, True))
            if seen > MAX_ELEMENTS:
                truncated = True
                break
            # Reversed so the stack pops children in their forward order.
            stack.extend(reversed(batch))

        # Pass 2: choose which texts to keep, top-down.
        kept = [False] * len(nodes)
        depth = [0] * len(nodes)
        # (depth, text) of kept ancestors within ANCESTOR_LEVELS. Only near
        # ancestors count: a row's cells sit one to three levels under the
        # row, and a container far above - a pane labelled with a page or
        # window title - should not swallow a heading that happens to repeat
        # it. (This was first added on a misread test: example.com's "missing
        # heading" turned out not to exist in its tree at all. The limit is
        # kept as the safer rule, not because a loss was observed.)
        context: list[tuple] = [()] * len(nodes)
        lines, total = [], 0
        for i, (text, parent, has_desc) in enumerate(nodes):
            if parent >= 0:
                depth[i] = depth[parent] + 1
                ctx = context[parent] + (((depth[parent], nodes[parent][0]),) if kept[parent] else ())
                ctx = tuple(c for c in ctx if c[0] >= depth[i] - ANCESTOR_LEVELS)
            else:
                ctx = ()
            context[i] = ctx
            if not text:
                continue
            if any(text in a for _, a in ctx):
                continue
            if has_desc and len(text) > AGGREGATE_MAX_CHARS:
                continue
            if lines and lines[-1] == text:
                continue
            kept[i] = True
            lines.append(text)
            total += len(text) + 1
            if total > MAX_TEXT_CHARS:
                truncated = True
                break

        text = "\n".join(lines)[:MAX_TEXT_CHARS]
        result = self._result(text, seen, skipped_password, skipped_offscreen, skipped_duplicate,
                              truncated, start)
        result["clipped"] = clipped
        return result

    @staticmethod
    def _result(text, seen, skipped_password, skipped_offscreen, skipped_duplicate, truncated,
                start) -> dict:
        return {
            "text": text,
            "elements": seen,
            "skipped_password": skipped_password,
            "skipped_offscreen": skipped_offscreen,
            "skipped_duplicate": skipped_duplicate,
            "truncated": truncated,
            "read_ms": round((time.perf_counter() - start) * 1000),
        }


def is_icon_glyph_only(s: str) -> bool:
    """True when every character is in a Unicode Private Use Area - an icon
    font rendered as text. Ported from the Android NodeExtractor, where such
    strings reached the model as meaningless symbols."""
    return bool(s) and all(
        0xE000 <= ord(c) <= 0xF8FF or 0xF0000 <= ord(c) <= 0xFFFFD or 0x100000 <= ord(c) <= 0x10FFFD
        for c in s
    )


# ------------------------------------------------------------------ dedup --

WORD = re.compile(r"[^\W\d_]+")

# Words that change without the content changing, ignored when deciding
# whether a line is new. Found in the first real session (2026-10-10, 99
# captures in 15 minutes): Gmail dates like "Sat, Oct 4, 2025, 9:41 pm" were
# counted new 27 times, because masking digits still left the weekday and
# month, and WhatsApp tick labels ("msg-dblcheck-delivered", "9:41 pm Read")
# flipped as messages were delivered. Only the comparison ignores them; the
# captured text is untouched.
VOLATILE_WORDS = {
    "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun",
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    "january", "february", "march", "april", "june", "july", "august", "september",
    "october", "november", "december",
    "am", "pm", "today", "yesterday", "ago", "min", "mins", "minute", "minutes",
    "hour", "hours", "day", "days", "now", "just",
    "msg", "dblcheck", "check", "delivered", "read", "sent", "seen", "pending", "edited",
}


def line_keys(text: str) -> set[str]:
    """The comparable lines of a screen: the words of each line, lower-cased,
    with numbers and VOLATILE_WORDS left out. A line that is nothing but a
    date, a time, a count or a delivery status therefore has no key and can
    never count as new."""
    keys = set()
    for line in text.split("\n"):
        if len(line.strip()) < NEW_LINE_MIN_CHARS:
            continue
        words = [w for w in WORD.findall(line.lower()) if w not in VOLATILE_WORDS]
        if sum(len(w) for w in words) < NEW_LINE_MIN_LETTERS:
            continue
        keys.add(" ".join(words))
    return keys


TOKEN_SPLIT = re.compile(r"[^\w]+|_", re.UNICODE)


def tokens(text: str) -> set[str]:
    """Android's tokenSet: lower-cased letter/digit runs of three or more
    characters. Short tokens are dropped because UI chrome is full of them
    ("to", "PM", "1") and they inflate the overlap between different screens."""
    return {t for t in TOKEN_SPLIT.split(text.lower()) if len(t) >= 3}


def jaccard(a: set[str], b: set[str]) -> float:
    """Intersection over union, 0 when either side is empty - as on Android."""
    if not a or not b:
        return 0.0
    shared = len(a & b)
    return shared / (len(a) + len(b) - shared)


class Dedup:
    """Per-source memory of stored screens. A source is an app, or an app and
    a site for browser pages.

    Seeded from captures.jsonl on start, the way Android's gates query Room:
    without that, every restart would re-capture every screen already logged.
    """

    def __init__(self, captures_path: Path):
        # source -> stored full screens: (epoch_s, text, tokens), oldest first
        self.screens: dict[str, list[tuple[float, str, set[str]]]] = {}
        # source -> {line key: epoch_s last seen}, from screens and updates
        self.lines: dict[str, dict[str, float]] = {}
        cutoff = time.time() - DEDUP_WINDOW_S
        try:
            with captures_path.open(encoding="utf-8") as f:
                for line in f:
                    try:
                        rec = json.loads(line)
                    except ValueError:
                        continue
                    t = rec.get("epoch_ms", 0) / 1000
                    if t < cutoff or not rec.get("exe") or not rec.get("text"):
                        continue
                    key = source_key(rec["exe"], rec.get("site"))
                    if rec.get("type", "screen") == "screen":
                        self._store_screen(key, t, rec["text"])
                    self._note_lines(key, t, rec["text"])
        except OSError:
            pass

    def _store_screen(self, key: str, t: float, text: str):
        history = self.screens.setdefault(key, [])
        history.append((t, text, tokens(text)))
        del history[:-RECENT_PER_APP]

    def _note_lines(self, key: str, t: float, text: str):
        seen = self.lines.setdefault(key, {})
        for k in line_keys(text):
            seen[k] = t

    def check(self, source: str, text: str, now: float | None = None) -> dict:
        """Decides what to do with one read. Returns a dict with:
          action  - "screen" (store whole), "update" (store new_text only),
                    "exact-dup" or "near-dup" (store nothing)
          overlap - best word overlap with a stored screen
          new_lines, new_text - the lines never seen before, in screen order
          base_ts - for an update, when the screen it extends was stored
        now: the read's time, for replaying old logs (dailyflow/importer.py);
        live capture leaves it as the clock.
        """
        key = source.lower()
        now = time.time() if now is None else now
        cutoff = now - DEDUP_WINDOW_S
        history = [h for h in self.screens.get(key, []) if h[0] >= cutoff]
        self.screens[key] = history
        seen = {k: t for k, t in self.lines.get(key, {}).items() if t >= cutoff}
        self.lines[key] = seen

        # Lines never seen before, kept in screen order with their real text.
        new_text_lines = []
        for line in text.split("\n"):
            keys = line_keys(line)
            if keys and not all(k in seen for k in keys):
                new_text_lines.append(line)

        result = {"overlap": 0.0, "new_lines": len(new_text_lines),
                  "new_text": "\n".join(new_text_lines), "base_ts": None}

        # Gate 1 - exact text.
        if any(prev == text for _, prev, _ in history):
            return {**result, "action": "exact-dup", "overlap": 1.0}

        # Gate 2 - word overlap.
        toks = tokens(text)
        best, base = 0.0, None
        for t, _, prev_toks in history:
            j = jaccard(toks, prev_toks)
            if j > best:
                best, base = j, t
        result["overlap"] = best

        if best < OVERLAP_THRESHOLD:
            self._store_screen(key, now, text)
            self._note_lines(key, now, text)
            return {**result, "action": "screen"}

        if new_text_lines:
            self._note_lines(key, now, text)
            return {**result, "action": "update", "base_ts": base}

        return {**result, "action": "near-dup"}


def source_key(exe: str, site: str | None) -> str:
    return f"{exe}|{site}".lower() if site else exe.lower()


# -------------------------------------------------------------- allowlist --

class Allowlist:
    """allowlist.json, re-read whenever it changes so it can be edited live -
    by hand, or by the UI, which writes the same file rather than holding a
    second copy of the list.

    `apps` are program names. `sites` are web hosts, which apply inside the
    browsers in BROWSERS: allowing a browser as an app would let every page
    through, banking included, so a page is instead read only when its host is
    listed here.
    """

    def __init__(self, path: Path):
        self.path = path
        self.mtime = None
        self.names: list[str] = []
        self.apps: set[str] = set()
        self.sites: list[str] = []
        # Sites the user has listed but unticked. Only the UI uses these - they
        # keep an unticked site on screen so it can be ticked again - and the
        # engine never reads them.
        self.site_options: list[str] = []
        self.reload(force=True)

    def reload(self, force=False) -> bool:
        try:
            m = self.path.stat().st_mtime
        except FileNotFoundError:
            if self.apps or self.sites or force:
                self.names, self.apps, self.sites = [], set(), []
                return True
            return False
        if not force and m == self.mtime:
            return False
        self.mtime = m
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            self.names = list(data.get("apps", []))
            self.apps = {a.lower() for a in self.names}
            self.sites = [s for s in (normalise_site(x) for x in data.get("sites", [])) if s]
            self.site_options = [s for s in (normalise_site(x) for x in data.get("site_options", []))
                                 if s]
        except (OSError, ValueError) as e:
            print(f"  ! allowlist.json unreadable, keeping previous list: {e}", file=sys.stderr)
            return False
        return True

    def allows(self, exe: str) -> bool:
        return exe.lower() in self.apps

    def allows_site(self, host: str) -> bool:
        """mail.google.com allows itself and its subdomains, not google.com."""
        return any(host == s or host.endswith("." + s) for s in self.sites)

    @staticmethod
    def save(path: Path, names: list[str] | None = None, sites: list[str] | None = None,
             site_options: list[str] | None = None):
        """Replaces whichever lists are given, keeping the others. Written to
        a temp file and swapped in, so the engine's live reload can never
        catch a half-written file."""
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        if names is not None:
            data["apps"] = sorted(set(names), key=str.lower)
        if sites is not None:
            data["sites"] = sorted({s for s in (normalise_site(x) for x in sites) if s})
        if site_options is not None:
            data["site_options"] = sorted({s for s in (normalise_site(x) for x in site_options) if s})
        data.setdefault("apps", [])
        data.setdefault("sites", [])
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, path)


def normalise_site(value: str) -> str:
    """'https://www.Mail.Google.com/mail/u/0/' -> 'mail.google.com'. Accepts
    whatever a person pastes or what a browser's address bar shows."""
    v = (value or "").strip().lower()
    v = re.sub(r"^[a-z][a-z0-9+.-]*://", "", v)
    v = re.split(r"[/?#]", v, maxsplit=1)[0]
    v = v.split("@")[-1].split(":")[0]
    if v.startswith("www."):
        v = v[4:]
    return v if "." in v and " " not in v else ""


# ----------------------------------------------------------------- engine --

class CaptureEngine:
    """The capture loop, runnable from the command line or a UI thread.

    Every record written to either log is also handed to [on_event], tagged
    with `kind` ("trace" or "capture"), so a UI can follow along without
    tailing the files.
    """

    def __init__(self, allowlist_path: Path, log_dir: Path, on_event=None):
        self.allowlist_path = allowlist_path
        self.log_dir = log_dir
        self.on_event = on_event or (lambda rec: None)
        self.captures = JsonlLog(log_dir / "captures.jsonl")
        self.trace = JsonlLog(log_dir / "trace.jsonl")
        self.allow = Allowlist(allowlist_path)
        self.stop_flag = threading.Event()
        self.paused = False

    def stop(self):
        self.stop_flag.set()

    def _trace(self, decision: str, reason: str, fg: dict | None = None, quiet=False,
               private=False, **extra):
        rec = {**now_fields(), "decision": decision, "reason": reason}
        if fg:
            rec.update(exe=fg["exe"], pid=fg["pid"])
            if not private:
                rec["title"] = fg["title"]
        rec.update({k: v for k, v in extra.items() if v is not None})
        self.trace.write(rec)
        self.on_event({"kind": "trace", "quiet": quiet, **rec})

    def run(self):
        # UIA is COM, and COM is per-thread: a UI runs this off its main
        # thread, which comtypes did not initialise on import.
        comtypes.CoInitialize()
        try:
            self._loop(Reader())
        finally:
            self._trace("stop", "logger-stopped")
            comtypes.CoUninitialize()

    def _loop(self, reader: "Reader"):
        allow = self.allow
        self._trace("start", "logger-started", allowlist=sorted(allow.apps), sites=allow.sites)

        current = None          # (hwnd, site) currently in front
        since = 0.0             # when it came to the front
        last_read = 0.0         # when it was last read
        rescan_gap = RESCAN_S   # grows with the last read's duration
        announced = None        # (hwnd, site, reason) last traced as a drop, to avoid spam
        locked_announced = paused_announced = False
        dedup = Dedup(self.captures.path)

        while not self.stop_flag.wait(POLL_S):
            if allow.reload():
                self._trace("config", "allowlist-reloaded", allowlist=sorted(allow.apps),
                            sites=allow.sites)
                announced = None

            if self.paused:
                if not paused_announced:
                    self._trace("drop", "paused")
                    paused_announced = True
                current = None
                continue
            paused_announced = False

            if is_locked():
                if not locked_announced:
                    self._trace("drop", "locked")
                    locked_announced = True
                continue
            locked_announced = False

            fg = foreground()
            if fg is None:
                continue
            exe = fg["exe"].lower()

            # Cheapest checks first; nothing is read from a window that fails
            # them. For a browser not allowed as a whole, only its address bar
            # is read, to learn which site is open.
            site = None
            if fg["pid"] == os.getpid() or exe in ALWAYS_BLOCKED:
                reason = "always-blocked"
            elif allow.allows(exe):
                reason = None
            elif exe in BROWSERS:
                site = reader.browser_host(fg["hwnd"])
                reason = None if site and allow.allows_site(site) else "site-not-allowed"
            else:
                reason = "not-allowed"

            # A tab change inside the same browser window is a switch too.
            now = time.monotonic()
            if (fg["hwnd"], site) != current:
                current, since, last_read, rescan_gap = (fg["hwnd"], site), now, 0.0, RESCAN_S

            if reason:
                if announced != (fg["hwnd"], site, reason):
                    # Program name only - no window title, no site. Titles and
                    # hosts of everything not allowed would amount to a log of
                    # every page and document opened, which is exactly what
                    # the allow-list exists to keep out. The Android trace
                    # likewise records only the package name.
                    self._trace("drop", reason, fg, private=True)
                    announced = (fg["hwnd"], site, reason)
                continue
            announced = None

            if now - since < SETTLE_S:
                continue
            if last_read and now - last_read < rescan_gap:
                continue
            trigger = "rescan" if last_read else "switch"

            # Traced before the read, not only after it. An open WhatsApp
            # window measured 18-26 s per read (2026-10-09), and with nothing
            # logged until the end, a capture in progress looked exactly like
            # the allowed app being ignored.
            self._trace("read", "reading", fg, trigger=trigger, site=site)
            try:
                r = reader.read(fg["hwnd"], page_only=exe in BROWSERS)
            except Exception as e:  # the window can close mid-read
                self._trace("error", "read-failed", fg, error=f"{type(e).__name__}: {e}", site=site)
                last_read = time.monotonic()
                continue
            # Measured from the end of the read, so a slow app is never read
            # back to back.
            last_read = time.monotonic()
            rescan_gap = max(RESCAN_S, RESCAN_READ_MULTIPLE * r["read_ms"] / 1000)

            # The page may have been navigated away during the read; if the
            # site is no longer allowed, the text is discarded unseen.
            if site is not None:
                after = reader.browser_host(fg["hwnd"])
                if not (after and allow.allows_site(after)):
                    self._trace("drop", "site-changed-during-read", fg, private=True)
                    continue

            if not r["text"].strip():
                self._trace("drop", "empty-text", fg, elements=r["elements"], read_ms=r["read_ms"],
                            site=site)
                continue

            # Each site is its own source for dedup, as each app is.
            d = dedup.check(source_key(fg["exe"], site), r["text"])
            if d["action"] in ("exact-dup", "near-dup"):
                # Rescans of an unchanged window land here every few seconds,
                # so these stay out of the UI unless asked for.
                self._trace("drop", d["action"], fg, quiet=True, trigger=trigger,
                            overlap=round(d["overlap"], 3), read_ms=r["read_ms"], site=site)
                continue
            is_update = d["action"] == "update"
            text = d["new_text"] if is_update else r["text"]
            base_ts = (datetime.datetime.fromtimestamp(d["base_ts"]).astimezone()
                       .isoformat(timespec="milliseconds") if is_update else None)

            rec = {
                **now_fields(),
                "exe": fg["exe"],
                "site": site,
                "pid": fg["pid"],
                "title": fg["title"],
                "trigger": trigger,
                "elements": r["elements"],
                "skipped_password": r["skipped_password"],
                "skipped_offscreen": r["skipped_offscreen"],
                "skipped_duplicate": r["skipped_duplicate"],
                "clipped": r.get("clipped", 0),
                "truncated": r["truncated"],
                "read_ms": r["read_ms"],
                # "screen": the whole visible screen. "update": only the lines
                # that are new since the screen stored at base_ts - the rest
                # of the screen is unchanged and is not repeated.
                "type": "update" if is_update else "screen",
                "base_ts": base_ts,
                "overlap": round(d["overlap"], 3),
                "text_chars": len(text),
                "new_lines": d["new_lines"],
                "text": text,
            }
            self.captures.write(rec)
            self.on_event({"kind": "capture", **rec})
            self._trace("capture", "update" if is_update else trigger, fg, elements=r["elements"],
                        text_chars=len(text), new_lines=d["new_lines"],
                        overlap=round(d["overlap"], 3), read_ms=r["read_ms"], site=site)


# -------------------------------------------------------------------- cli --

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--allowlist", type=Path, default=DEFAULT_ALLOWLIST)
    ap.add_argument("--log-dir", type=Path, default=DEFAULT_LOG_DIR)
    args = ap.parse_args()

    def show(rec):
        if rec["kind"] != "trace" or rec.get("quiet"):
            return
        where = rec.get("site") or rec.get("title", "")[:50]
        who = f"{rec['exe']} | {where}" if "exe" in rec else ""
        print(f"{rec['ts'][11:23]}  {rec['decision']:<7} {rec['reason']:<16} {who}")

    engine = CaptureEngine(args.allowlist, args.log_dir, show)
    print("DailyFlow capture logger")
    print(f"  allow-list: {sorted(engine.allow.apps) or '(empty - nothing will be captured)'}")
    print(f"  logs:       {args.log_dir}")
    print("  Ctrl+C to stop\n")
    try:
        engine.run()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
