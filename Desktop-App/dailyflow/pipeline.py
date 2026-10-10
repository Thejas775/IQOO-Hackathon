"""The extraction pass and the reminders. Ports ExtractionRunner.kt,
ExtractionScheduler.kt, ReminderScheduler.kt and ReminderWorker.kt.

Android runs the pass from WorkManager every 15 minutes and fires reminders
from one-shot work requests. Here both are a background thread: Windows has
no WorkManager, and the app is a tray-less window the owner keeps open, so a
timer inside it is the honest equivalent. Reminders are not scheduled as
timers at all - every 30 s the thread asks the store which are due, which
is the ReminderWorker re-check done continuously, and survives restarts
without re-scheduling anything.
"""

import json
import threading
import time
from datetime import datetime

from . import extraction
from .engine import GemmaEngine
from .paths import APP_LOG_PATH, LOG_DIR, load_settings
from .store import OPEN_COMMITMENT_PROMPT_LIMIT, Memory, Store, now_ms

REMINDER_POLL_S = 30
INGEST_POLL_S = 20


class AppLog:
    """DebugLog.kt: one line per pipeline decision, so 'why is X missing' is
    answerable from a file. Summaries are the owner's data and are logged
    only as previews, in the private %LOCALAPPDATA% folder."""

    def __init__(self):
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.listeners = []

    def __call__(self, tag: str, msg: str):
        rec = {"ts": datetime.now().astimezone().isoformat(timespec="milliseconds"),
               "tag": tag, "msg": msg}
        with self._lock:
            try:
                if APP_LOG_PATH.exists() and APP_LOG_PATH.stat().st_size > 4_000_000:
                    APP_LOG_PATH.replace(APP_LOG_PATH.with_suffix(".jsonl.1"))
                with APP_LOG_PATH.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            except OSError:
                pass
        for fn in list(self.listeners):
            try:
                fn(rec)
            except Exception:
                pass


def battery_low() -> bool:
    """Android's "battery not low" is below ~15% and not charging."""
    import ctypes

    class SPS(ctypes.Structure):
        _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte),
                    ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_ubyte),
                    ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]
    s = SPS()
    if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)):
        return False
    on_battery = s.ACLineStatus == 0
    return on_battery and s.BatteryLifePercent <= 15


def toast(title: str, body: str):
    try:
        from winotify import Notification
        Notification(app_id="DailyFlow", title=title, msg=body[:250], duration="long").show()
        return True
    except Exception:
        return False


def source_label(m: Memory) -> str:
    if m.site:
        return m.site
    exe = (m.packageName or "").lower()
    return {"whatsapp.exe": "WhatsApp", "whatsapp.root.exe": "WhatsApp", "slack.exe": "Slack",
            "ms-teams.exe": "Teams", "teams.exe": "Teams", "discord.exe": "Discord",
            "telegram.exe": "Telegram", "outlook.exe": "Outlook", "olk.exe": "Outlook",
            "notion.exe": "Notion", "voice": "Voice note", "": "Manual",
            # Memories synced from the phone carry its package names
            # (ui/SourceApp.kt KNOWN, same labels).
            "com.whatsapp": "WhatsApp", "com.whatsapp.w4b": "WhatsApp", "com.slack": "Slack",
            "com.google.android.gm": "Gmail", "com.instagram.android": "Instagram",
            "org.telegram.messenger": "Telegram", "com.microsoft.teams": "Teams",
            "com.linkedin.android": "LinkedIn", "com.google.android.apps.messaging": "Messages",
            "com.google.android.calendar": "Calendar", "com.google.android.apps.docs": "Docs",
            "com.discord": "Discord", "com.notion.id": "Notion",
            }.get(exe) or _fallback_label(exe)


def _fallback_label(name: str) -> str:
    if name.endswith(".exe"):
        return name.removesuffix(".exe").capitalize()[:14]
    # An Android package: the most distinctive segment, as sourceAppFor does.
    parts = [x for x in name.split(".") if x and x not in ("com", "org", "android", "app", "apps")]
    return (parts[-1] if parts else name).capitalize()[:14]


class Pipeline:
    def __init__(self, store: Store, engine: GemmaEngine, log: AppLog):
        self.store, self.engine, self.log = store, engine, log
        self.progress = {"running": False, "done": 0, "total": 0, "message": None}
        self._stop = threading.Event()
        self._cancel_pass = threading.Event()
        self._pass_lock = threading.Lock()
        self._wake = threading.Event()
        self.listeners = []
        self.next_pass_at: float | None = None

    def _emit(self, **p):
        self.progress = {**self.progress, **p}
        for fn in list(self.listeners):
            try:
                fn(self.progress)
            except Exception:
                pass

    # ── background loops ───────────────────────────────────────────────

    def start(self):
        threading.Thread(target=self._scheduler, name="extract-scheduler", daemon=True).start()
        threading.Thread(target=self._reminders, name="reminders", daemon=True).start()

    def stop(self):
        self._stop.set()
        self._cancel_pass.set()
        self._wake.set()

    def _scheduler(self):
        last_ingest = 0.0
        # Android enqueues its periodic work on app start (KEEP), and WorkManager
        # runs the first period straight away; a minute's grace lets the window
        # finish opening first.
        self.next_pass_at = time.time() + 60
        while not self._stop.is_set():
            if time.time() - last_ingest > INGEST_POLL_S:
                try:
                    n = self.store.ingest()
                    if n:
                        self.log("ingest", f"added {n} capture(s) from captures.jsonl")
                except Exception as e:
                    self.log("ingest", f"failed: {e}")
                last_ingest = time.time()
            s = load_settings()
            if s["auto_extract"] and self.next_pass_at and time.time() >= self.next_pass_at:
                if battery_low():
                    # ExtractionScheduler's setRequiresBatteryNotLow(true): a
                    # scheduled pass waits. "Read now" ignores it, as on Android.
                    self.log("extract", "scheduled pass skipped: battery low")
                else:
                    self.run_once()
                self.next_pass_at = time.time() + 60 * max(1, int(s["extract_every_min"]))
            self.engine.unload_if_idle()
            self._wake.wait(5)
            self._wake.clear()

    def run_in_background(self):
        threading.Thread(target=self.run_once, name="extract-now", daemon=True).start()

    def cancel(self):
        self._cancel_pass.set()

    # ── the pass ───────────────────────────────────────────────────────

    def run_once(self):
        if not self._pass_lock.acquire(blocking=False):
            return      # one pass at a time; a second request joins the first
        try:
            self._cancel_pass.clear()
            self._run()
        finally:
            self._pass_lock.release()

    def _run(self):
        try:
            self.store.ingest()
        except Exception as e:
            self.log("ingest", f"failed: {e}")
        pending = self.store.pending_extraction()
        if not pending:
            self._emit(running=False, done=0, total=0, message="Nothing new to read")
            return
        if not self.engine.is_model_available():
            self._emit(running=False, done=0, total=len(pending), message="Model not installed")
            return

        self.log("extract", f"pass start: {len(pending)} pending")
        self._emit(running=True, done=0, total=len(pending), message="Loading Gemma...")
        self.engine.pin()
        done = 0
        t_pass = time.time()
        try:
            people = self.store.recent_people()
            # Fetched once, maintained in place: an item ticked off earlier in
            # this pass must not be offered again.
            open_ = [(m.id, m.summary) for m in self.store.open_commitments() if m.summary]

            for row in pending:
                if self._cancel_pass.is_set() or self._stop.is_set():
                    self._emit(running=False, message=f"Stopped after {done}")
                    return
                context = None
                if row.captureType == "update" and row.baseId:
                    base = self.store.by_id(row.baseId)
                    context = base.rawText if base else None
                prompt = extraction.build_prompt(source_label(row), row.timestamp, row.rawText,
                                                 people, [s for _, s in open_], context)
                self._emit(message=f"Reading {done + 1} of {len(pending)} ({source_label(row)})")
                reply = self.engine.complete(prompt, should_stop=lambda: self._cancel_pass.is_set())
                if reply is None:
                    # Engine failure, not a judgement on this row: stop, and the
                    # rest stay pending for the next pass.
                    self.log("extract", f"inference returned nothing; {len(pending) - done} left pending")
                    self._emit(running=False, message="Couldn't finish: " + (self.engine.last_error or "engine error"))
                    return
                call = self.engine.last_call or {}
                results = extraction.parse_all(reply, extraction.source_text(row.rawText, context),
                                               row.timestamp)
                self._apply(row, results[0], open_)
                for extra in results[1:]:
                    if not extra.summary:
                        continue
                    if self.store.has_similar_summary(extra.summary):
                        self.log("extract", f"row {row.id} extra duplicate, dropped")
                        continue
                    new_id = self.store.add_derived(row, extra)
                    self._offer_as_open(new_id, extra, open_)
                    self.log("extract", f"row {row.id} extra -> id={new_id} person={extra.person}")
                done += 1
                self.log("extract", f"row {row.id} {source_label(row)} blocks={len(results)} "
                                    f"memory={results[0].is_memory} in {call.get('total_s', 0):.1f}s "
                                    f"(first token {call.get('first_token_s') or 0:.1f}s, "
                                    f"{call.get('prompt_chars')} chars in)")
                self._emit(done=done)

            secs = time.time() - t_pass
            self._emit(running=False, done=done,
                       message=f"Read {done} screen(s) in {secs:.0f}s" if done else "Nothing new")
        except Exception as e:
            self.log("extract", f"pass crashed: {e!r}")
            self._emit(running=False, message=f"Error: {e}")
        finally:
            self.log("extract", f"pass end: read {done} of {len(pending)}")
            self.engine.unpin()
            # Released after every pass, as on Android: steady-state memory
            # between passes is zero.
            self.engine.unload()

    def _apply(self, row: Memory, result: extraction.Extraction, open_: list):
        # Auto-tick, validated against the list actually sent; an
        # out-of-range ordinal is dropped, never clamped.
        if result.completes_index is not None:
            i = result.completes_index - 1
            if 0 <= i < len(open_):
                target_id, summary = open_.pop(i)
                self.store.set_done(target_id, True)
                self.log("autotick", f"done id={target_id}")
            else:
                self.log("autotick", f"ignored out-of-range {result.completes_index} (open={len(open_)})")

        if result.is_memory:
            if result.summary and self.store.has_similar_summary(result.summary):
                self.log("extract", f"row {row.id} duplicate summary, dropped")
                self.store.mark_not_a_memory(row.id)
                return
            self.store.save_extraction(row.id, result)
            self._offer_as_open(row.id, result, open_)
        else:
            self.store.mark_not_a_memory(row.id)

    @staticmethod
    def _offer_as_open(id_, ex, open_):
        """EXTRA CHECK (off unless the extra_checks setting is on): a commitment
        found earlier in this pass can be completed by a later screen of the
        same pass. Android fetches the open list once per pass and never adds
        to it. Appended, so earlier ordinals keep meaning what they meant."""
        if not extraction.extra_checks():
            return
        if (ex.direction in extraction.OBLIGATION_DIRECTIONS and ex.summary
                and len(open_) < OPEN_COMMITMENT_PROMPT_LIMIT):
            open_.append((id_, ex.summary))

    # ── reminders ──────────────────────────────────────────────────────

    def _reminders(self):
        while not self._stop.is_set():
            try:
                if load_settings()["reminders"]:
                    self.fire_due()
            except Exception as e:
                self.log("remind", f"error: {e}")
            self._stop.wait(REMINDER_POLL_S)

    def fire_due(self) -> int:
        """Fires every reminder whose time has come. Re-checks each row first,
        because the gap between scheduling and firing is the point: the owner
        may have done the thing meanwhile (ReminderWorker.doWork)."""
        fired = 0
        now = now_ms()
        for m in self.store.pending_reminders():
            if m.dueAt is None or m.dueAt > now:
                break           # sorted by dueAt; nothing later is due either
            reason = ("already done" if m.commitmentDone else "not a commitment" if m.isLoop != 1
                      else "no summary" if not m.summary else None)
            if reason:
                # ReminderWorker returns success here: the one nudge is spent.
                self.log("remind", f"skip id={m.id} ({reason})")
                self.store.mark_reminder_sent(m.id)
                continue
            if toast("DailyFlow", m.summary):
                self.log("remind", f"fired id={m.id}")
            else:
                self.log("remind", f"toast failed id={m.id}")
            # Marked after showing, so a crash mid-send retries.
            self.store.mark_reminder_sent(m.id)
            fired += 1
        return fired

    def test_reminders(self) -> int:
        """ReminderTester: shows every scheduled reminder now, without marking
        them sent, so the real nudge still arrives on time."""
        pending = self.store.pending_reminders()
        if not pending:
            toast("DailyFlow", "No reminders scheduled yet - read your day first, then try again.")
            return 0
        for m in pending[:5]:
            kind = "at" if m.dueAtExplicit else "by"
            when = datetime.fromtimestamp(m.dueAt / 1000).strftime("%d %b, %I:%M %p")
            toast("DailyFlow", f"[{kind} {when}] {m.summary}")
        return len(pending)
