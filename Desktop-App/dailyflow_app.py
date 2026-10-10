"""
DailyFlow for Windows - the full app.

Pages, as on the phone: Today, Calendar, Ask, People, Memories, Voice - plus
Capture (the Level 1 allow-list and logs) and Model & settings.

Run:  python dailyflow_app.py     (pythonw for no console window)

Everything stays on this PC: captures and the database under
%LOCALAPPDATA%\\DailyFlow, the model wherever Settings points (E: here).
Nothing is sent anywhere; the only network use is the model download.
"""

import ctypes
import os
import queue
import sys
import threading
import time
import tkinter as tk
from datetime import datetime, timedelta
from pathlib import Path
from tkinter import messagebox, ttk

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "capture"))
sys.path.insert(0, str(HERE))

import app as capture_ui                                      # noqa: E402  (Level 1 UI)
from dailyflow import assist, extraction                      # noqa: E402
from dailyflow.engine import GemmaEngine                      # noqa: E402
from dailyflow.models import CATALOG, by_file_name, download, find_model, model_dir, part_file  # noqa: E402
from dailyflow.paths import APP_LOG_PATH, DATA_DIR, load_settings, save_settings  # noqa: E402
from dailyflow.pipeline import AppLog, Pipeline, source_label  # noqa: E402
from dailyflow.sync_server import SyncServer                  # noqa: E402
from dailyflow.store import Memory, Store, day_bounds, now_ms  # noqa: E402
from dailyflow.voice import MAX_SECONDS, Recorder             # noqa: E402

C = {
    "bg": "#f6f5f2", "panel": "#ffffff", "side": "#1f2330", "side_text": "#c9cdd8",
    "side_active": "#ffffff", "text": "#1d2330", "muted": "#6b7280", "line": "#e7e5e0",
    "accent": "#2f6fed", "orange": "#c2410c", "orange_tint": "#fff1e6",
    "green": "#137a3a", "green_tint": "#e8f6ee", "blue_tint": "#e8effd",
    "violet": "#6d28d9", "violet_tint": "#f1ebfd", "grey_tint": "#f1f1ef", "red": "#b91c1c",
}
FONT = "Segoe UI"


def fmt_time(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%I:%M %p").lstrip("0")


def due_label(m: Memory) -> str | None:
    """DateLabels.dueLabel, plus the hour when it was actually said."""
    if not m.dueAt:
        return None
    d = datetime.fromtimestamp(m.dueAt / 1000)
    days = (d.date() - datetime.now().date()).days
    hour = f", {fmt_time(m.dueAt)}" if m.dueAtExplicit else ""
    if m.dueAt < now_ms() and not m.commitmentDone:
        return "Overdue" + (f" · {d:%d %b}" if days < -1 else "")
    if days == 0:
        return "Due today" + hour
    if days == 1:
        return "Due tomorrow" + hour
    if 2 <= days <= 6:
        return f"Due {d:%A}" + hour
    return f"Due {d:%d %b}" + hour


def greeting():
    h = datetime.now().hour
    return ("Good morning" if 5 <= h <= 11 else "Good afternoon" if 12 <= h <= 16
            else "Good evening" if 17 <= h <= 21 else "Still up")


class Scroll(ttk.Frame):
    """A vertically scrolling frame; children go in .inner."""

    def __init__(self, parent, bg=C["bg"]):
        super().__init__(parent)
        self.canvas = tk.Canvas(self, bg=bg, highlightthickness=0, bd=0)
        self.sb = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.inner = tk.Frame(self.canvas, bg=bg)
        self.inner.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self._win = self.canvas.create_window((0, 0), window=self.inner, anchor="nw")
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(self._win, width=e.width))
        self.canvas.configure(yscrollcommand=self.sb.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.sb.pack(side="right", fill="y")
        self.canvas.bind("<Enter>", lambda e: self.canvas.bind_all("<MouseWheel>", self._wheel))
        self.canvas.bind("<Leave>", lambda e: self.canvas.unbind_all("<MouseWheel>"))

    def _wheel(self, e):
        self.canvas.yview_scroll(int(-e.delta / 120), "units")

    def clear(self):
        for w in self.inner.winfo_children():
            w.destroy()
        self.canvas.yview_moveto(0)


class DailyFlowApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.ui_q: queue.Queue = queue.Queue()
        self.log = AppLog()
        self.store = Store()
        self.engine = GemmaEngine(log=self.log)
        self.pipeline = Pipeline(self.store, self.engine, self.log)
        self.recorder = Recorder()
        self._refresh_pending = False
        self._download_stop = False
        self.downloading = False
        self.brief = None               # once per app open, as TodayViewModel
        self._brief_running = False
        self.person_lines: dict = {}    # once per person per app open

        root.title("DailyFlow")
        root.geometry("1240x800")
        root.minsize(980, 620)
        root.configure(bg=C["bg"])
        self._style()

        self.store.listeners.append(lambda: self.call(self.schedule_refresh))
        self.pipeline.listeners.append(lambda p: self.call(self.on_progress, p))
        self.log.listeners.append(lambda rec: self.call(self.on_log, rec))

        self._build_shell()
        self.pages = {}
        for name, builder in (("Today", self.build_today), ("Calendar", self.build_calendar),
                              ("Ask", self.build_ask), ("People", self.build_people),
                              ("Memories", self.build_memories), ("Voice", self.build_voice),
                              ("Capture", self.build_capture), ("Model & settings", self.build_settings)):
            frame = tk.Frame(self.content, bg=C["bg"])
            self.pages[name] = frame
            builder(frame)
        self.current = None
        self.show("Today")

        self.pipeline.start()
        # Phone sync: the PC listens on the Wi-Fi; the phone connects.
        self.sync = SyncServer(self.store, log=self.log)
        self.sync.on_paired = lambda dev, name: self.call(self.on_paired, name)
        self.sync.start()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.after(100, self._drain)
        root.after(1000, self._tick)

    # ── plumbing ───────────────────────────────────────────────────────

    def call(self, fn, *args):
        """Run fn on the Tk thread. Every background callback goes through here."""
        self.ui_q.put((fn, args))

    def _drain(self):
        try:
            for _ in range(200):
                fn, args = self.ui_q.get_nowait()
                try:
                    fn(*args)
                except Exception as e:
                    print("ui error:", repr(e), file=sys.stderr)
        except queue.Empty:
            pass
        self.root.after(100, self._drain)

    def bg(self, work, done=None):
        def run():
            try:
                result = work()
            except Exception as e:
                result = e
            if done:
                self.call(done, result)
        threading.Thread(target=run, daemon=True).start()

    def schedule_refresh(self):
        if self._refresh_pending:
            return
        self._refresh_pending = True

        def go():
            self._refresh_pending = False
            self.refresh_current()
        self.root.after(400, go)

    def refresh_current(self):
        fn = getattr(self, f"refresh_{self.current.lower().split()[0]}", None) if self.current else None
        if fn:
            fn()
        self.update_status()

    def _style(self):
        s = ttk.Style(self.root)
        s.theme_use("clam")
        s.configure(".", font=(FONT, 10), background=C["bg"], foreground=C["text"])
        s.configure("TButton", padding=(12, 6))
        s.configure("Accent.TButton", background=C["accent"], foreground="white",
                    font=(FONT + " Semibold", 10), padding=(14, 7), borderwidth=0)
        s.map("Accent.TButton", background=[("active", "#2559c7"), ("disabled", "#a5b8e8")])
        s.configure("Small.TButton", padding=(8, 3), font=(FONT, 9))
        s.configure("Treeview", rowheight=26, background=C["panel"], fieldbackground=C["panel"], borderwidth=0)
        s.configure("Treeview.Heading", font=(FONT + " Semibold", 9), background=C["bg"],
                    foreground=C["muted"], relief="flat")
        s.map("Treeview", background=[("selected", "#dbe6fd")], foreground=[("selected", C["text"])])
        s.configure("TNotebook", background=C["bg"], borderwidth=0)
        s.configure("TNotebook.Tab", padding=(16, 6), font=(FONT + " Semibold", 10),
                    background="#e5e7eb", foreground=C["muted"])
        s.map("TNotebook.Tab", background=[("selected", C["panel"])], foreground=[("selected", C["accent"])])
        s.configure("TCheckbutton", background=C["panel"])
        s.configure("Horizontal.TProgressbar", background=C["accent"], troughcolor=C["line"], borderwidth=0)
        # Styles the embedded Level 1 capture UI expects.
        cc = capture_ui.COLORS
        s.configure("Panel.TFrame", background=cc["panel"])
        s.configure("Panel.TLabel", background=cc["panel"])
        s.configure("Title.TLabel", font=(FONT + " Semibold", 16))
        s.configure("Sub.TLabel", foreground=cc["muted"])
        s.configure("PanelSub.TLabel", background=cc["panel"], foreground=cc["muted"])
        s.configure("Head.TLabel", background=cc["panel"], font=(FONT + " Semibold", 11))
        s.configure("Stat.TLabel", background=cc["panel"], font=(FONT + " Semibold", 15))
        s.configure("StatSub.TLabel", background=cc["panel"], foreground=cc["muted"], font=(FONT, 9))

    def _build_shell(self):
        side = tk.Frame(self.root, bg=C["side"], width=200)
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        tk.Label(side, text="DailyFlow", bg=C["side"], fg="white",
                 font=(FONT + " Semibold", 17)).pack(anchor="w", padx=20, pady=(22, 0))
        tk.Label(side, text="private memory · on this PC", bg=C["side"], fg=C["side_text"],
                 font=(FONT, 9)).pack(anchor="w", padx=20, pady=(0, 18))
        self.nav = {}
        for name in ("Today", "Calendar", "Ask", "People", "Memories", "Voice", "Capture",
                     "Model & settings"):
            b = tk.Label(side, text=name, bg=C["side"], fg=C["side_text"], anchor="w",
                         font=(FONT + " Semibold", 11), padx=20, pady=9, cursor="hand2")
            b.pack(fill="x")
            b.bind("<Button-1>", lambda e, n=name: self.show(n))
            self.nav[name] = b
        self.status = tk.Label(side, text="", bg=C["side"], fg=C["side_text"], font=(FONT, 9),
                               justify="left", anchor="w", wraplength=170)
        self.status.pack(side="bottom", fill="x", padx=20, pady=16)
        self.content = tk.Frame(self.root, bg=C["bg"])
        self.content.pack(side="left", fill="both", expand=True)

    def show(self, name):
        if self.current:
            self.pages[self.current].pack_forget()
            self.nav[self.current].configure(bg=C["side"], fg=C["side_text"])
        self.current = name
        self.pages[name].pack(fill="both", expand=True)
        self.nav[name].configure(bg="#2e3446", fg=C["side_active"])
        self.refresh_current()

    def update_status(self):
        c = self.store.counts()
        model = find_model()
        cap = getattr(self, "cap_state", "stopped")
        p = self.pipeline.progress
        lines = [f"Capture: {cap}",
                 f"Model: {model.name.replace('.litertlm', '') if model else 'not installed'}"
                 + (f" ({self.engine.state})" if model else ""),
                 f"{c['captured']} screens · {c['memories']} memories",
                 f"{c['pending']} waiting to be read"]
        peers = self.store.peers()
        if peers:
            last = max((x["lastSyncAt"] or 0) for x in peers)
            lines.append(f"Phone: synced {assist.relative_time(last)}" if last else "Phone: paired, not synced yet")
        if p.get("running"):
            lines.append(f"Reading {p['done']}/{p['total']}…")
        elif self.pipeline.next_pass_at and load_settings()["auto_extract"]:
            mins = max(0, int((self.pipeline.next_pass_at - time.time()) / 60))
            lines.append(f"Next read in {mins} min")
        self.status.configure(text="\n".join(lines))

    def _tick(self):
        self.update_status()
        if self.recorder.recording:
            self.voice_timer.configure(text=f"{self.recorder.elapsed():.0f}s / {MAX_SECONDS}s")
            if self.recorder.elapsed() >= MAX_SECONDS:
                self.toggle_record()
        self.root.after(1000, self._tick)

    def header(self, page, title, subtitle=None):
        h = tk.Frame(page, bg=C["bg"])
        h.pack(fill="x", padx=28, pady=(22, 8))
        tk.Label(h, text=title, bg=C["bg"], fg=C["text"], font=(FONT + " Semibold", 20)).pack(anchor="w")
        if subtitle:
            tk.Label(h, text=subtitle, bg=C["bg"], fg=C["muted"], font=(FONT, 10)).pack(anchor="w")
        return h

    def section(self, parent, text, right=None):
        f = tk.Frame(parent, bg=C["bg"])
        f.pack(fill="x", padx=4, pady=(18, 6))
        tk.Label(f, text=text.upper(), bg=C["bg"], fg=C["muted"], font=(FONT + " Semibold", 9)).pack(side="left")
        if right:
            tk.Label(f, text=right, bg=C["bg"], fg=C["muted"], font=(FONT, 9)).pack(side="right")
        return f

    def empty(self, parent, text):
        tk.Label(parent, text=text, bg=C["bg"], fg=C["muted"], font=(FONT, 10), justify="left",
                 wraplength=700).pack(anchor="w", padx=6, pady=6)

    # ── the memory card, used on every page ───────────────────────────

    def card(self, parent, m: Memory, actions=True, show_date=False):
        oblig = m.direction in extraction.OBLIGATION_DIRECTIONS
        card = tk.Frame(parent, bg=C["panel"], highlightbackground=C["line"], highlightthickness=1)
        card.pack(fill="x", padx=4, pady=4)
        top = tk.Frame(card, bg=C["panel"])
        top.pack(fill="x", padx=14, pady=(10, 0))
        pill_bg, pill_fg = ((C["orange_tint"], C["orange"]) if oblig and not m.commitmentDone else
                            (C["green_tint"], C["green"]) if m.commitmentDone else
                            (C["violet_tint"], C["violet"]) if m.direction == "MANUAL" else
                            (C["grey_tint"], C["muted"]))
        label = "Done" if m.commitmentDone and oblig else extraction.direction_label(m.direction, m.personName)
        tk.Label(top, text=label, bg=pill_bg, fg=pill_fg, font=(FONT + " Semibold", 9),
                 padx=8, pady=1).pack(side="left")
        tk.Label(top, text=source_label(m).upper(), bg=C["blue_tint"], fg=C["accent"],
                 font=(FONT + " Semibold", 8), padx=6, pady=1).pack(side="left", padx=6)
        if m.originDevice:
            # Made on the paired phone and synced here - said on every card, so
            # the owner can always tell which device saw it.
            tk.Label(top, text="📱 FROM " + (m.originName or "PHONE").upper(), bg=C["violet_tint"],
                     fg=C["violet"], font=(FONT + " Semibold", 8), padx=6, pady=1).pack(side="left", padx=(0, 6))
        when = (datetime.fromtimestamp(m.timestamp / 1000).strftime("%d %b, ") if show_date else "") + fmt_time(m.timestamp)
        tk.Label(top, text=when, bg=C["panel"], fg=C["muted"], font=(FONT, 9)).pack(side="left")
        d = due_label(m)
        if d:
            tk.Label(top, text=d, bg=C["panel"], fg=C["red"] if d.startswith("Overdue") else C["orange"],
                     font=(FONT + " Semibold", 9)).pack(side="right")
        fg = C["muted"] if m.commitmentDone else C["text"]
        font = (FONT, 11, "overstrike") if m.commitmentDone else (FONT, 11)
        tk.Label(card, text=m.summary or "", bg=C["panel"], fg=fg, font=font, justify="left",
                 anchor="w", wraplength=760).pack(fill="x", padx=14, pady=(6, 0))
        if m.sourceQuote:
            tk.Label(card, text=f"“{m.sourceQuote}”", bg=C["panel"], fg=C["muted"],
                     font=(FONT, 10, "italic"), justify="left", anchor="w",
                     wraplength=760).pack(fill="x", padx=14, pady=(2, 0))
        bar = tk.Frame(card, bg=C["panel"])
        bar.pack(fill="x", padx=10, pady=(6, 8))
        if actions:
            if oblig or m.direction == "MANUAL":
                ttk.Button(bar, text="Mark open" if m.commitmentDone else "Done", style="Small.TButton",
                           command=lambda: self.store.set_done(m.id, not m.commitmentDone)).pack(side="left", padx=2)
            if oblig and not m.commitmentDone:
                ttk.Button(bar, text="Draft follow-up", style="Small.TButton",
                           command=lambda: self.draft(m)).pack(side="left", padx=2)
            if m.rawText and m.eventType not in ("MANUAL", "VOICE"):
                ttk.Button(bar, text="What was on screen", style="Small.TButton",
                           command=lambda: self.show_raw(m)).pack(side="left", padx=2)
            if m.personName:
                ttk.Button(bar, text=m.personName, style="Small.TButton",
                           command=lambda: self.open_person(m.personName)).pack(side="left", padx=2)
            ttk.Button(bar, text="Delete", style="Small.TButton",
                       command=lambda: self.delete(m)).pack(side="right", padx=2)
        return card

    def delete(self, m):
        if messagebox.askyesno("Delete memory", "Delete this memory?", parent=self.root):
            self.store.delete(m.id)

    def show_raw(self, m: Memory):
        w = tk.Toplevel(self.root)
        w.title(f"Captured from {source_label(m)} at {fmt_time(m.timestamp)}")
        w.geometry("760x560")
        t = tk.Text(w, wrap="word", font=("Consolas", 10), padx=12, pady=10, relief="flat")
        if m.baseId:
            base = self.store.by_id(m.baseId)
            if base:
                t.insert("end", "— earlier on the same screen (context) —\n", "muted")
                t.insert("end", base.rawText[:extraction.CONTEXT_CHARS] + "\n\n— new lines —\n", "muted")
        t.insert("end", m.rawText)
        t.tag_configure("muted", foreground=C["muted"])
        t.configure(state="disabled")
        t.pack(fill="both", expand=True)

    def draft(self, m: Memory):
        w = tk.Toplevel(self.root)
        w.title("Draft follow-up")
        w.geometry("560x260")
        w.configure(bg=C["panel"])
        tk.Label(w, text=m.summary, bg=C["panel"], fg=C["muted"], wraplength=520, justify="left").pack(anchor="w", padx=16, pady=(14, 6))
        t = tk.Text(w, wrap="word", height=6, font=(FONT, 11), relief="flat", bg="#fbfbfa", padx=10, pady=8)
        t.pack(fill="both", expand=True, padx=16)
        t.insert("end", "Gemma is writing a draft…")
        bar = tk.Frame(w, bg=C["panel"])
        bar.pack(fill="x", padx=16, pady=10)
        tk.Label(bar, text="Never sent - copy it into the chat yourself.", bg=C["panel"],
                 fg=C["muted"], font=(FONT, 9)).pack(side="left")

        def copy():
            self.root.clipboard_clear()
            self.root.clipboard_append(t.get("1.0", "end").strip())
        ttk.Button(bar, text="Copy", command=copy).pack(side="right")

        def done(res):
            if not w.winfo_exists():
                return
            t.delete("1.0", "end")
            t.insert("end", res if isinstance(res, str) else
                     "Couldn't write a draft: " + ("model not installed" if not find_model() else str(res or self.engine.last_error)))
        self.bg(lambda: assist.draft_follow_up(self.engine, m), done)

    # ── Today ──────────────────────────────────────────────────────────

    def build_today(self, page):
        h = self.header(page, greeting(), datetime.now().strftime("%A, %d %B"))
        self.today_title = h.winfo_children()[0]
        right = tk.Frame(h, bg=C["bg"])
        right.place(relx=1.0, rely=0.0, anchor="ne")
        ttk.Button(right, text="+ Add task", command=self.add_task).pack(side="right", padx=(8, 0))
        self.read_btn = ttk.Button(right, text="Read now", style="Accent.TButton", command=self.read_now)
        self.read_btn.pack(side="right")
        self.progress_label = tk.Label(page, text="", bg=C["bg"], fg=C["muted"], font=(FONT, 9))
        self.progress_label.pack(anchor="w", padx=30)
        self.progress_bar = ttk.Progressbar(page, mode="determinate", length=300)
        self.today_scroll = Scroll(page)
        self.today_scroll.pack(fill="both", expand=True, padx=24, pady=(4, 12))

    def refresh_today(self):
        self.today_title.configure(text=greeting())
        sc = self.today_scroll
        sc.clear()
        p = sc.inner
        start, end = day_bounds()
        todays = self.store.captured_between(start, end)
        open_ = self.store.open_all()
        due_today = self.store.due_between(start, end)
        kept = self.store.kept_between(start, end)

        # Brief card
        brief = tk.Frame(p, bg=C["side"])
        brief.pack(fill="x", padx=4, pady=(4, 6))
        tk.Label(brief, text="YOUR DAY · GEMMA · ON THIS PC", bg=C["side"], fg=C["side_text"],
                 font=(FONT + " Semibold", 8)).pack(anchor="w", padx=18, pady=(14, 2))
        # Gemma's brief once per app open, and only when the engine is already
        # loaded - opening Today must not trigger a multi-gigabyte load. Until
        # then, the factual line from the counts (TodayComponents.fallbackBrief).
        text = self.brief or self.fallback_brief(len(todays), len(open_))
        self.brief_label = tk.Label(brief, text=text, bg=C["side"], fg="white", font=(FONT, 13),
                                    justify="left", anchor="w", wraplength=760)
        self.brief_label.pack(fill="x", padx=18)
        tk.Label(brief, text=f"{len(todays)} noted · {len(open_)} open · {len(due_today)} due today",
                 bg=C["side"], fg=C["side_text"], font=(FONT, 9)).pack(anchor="e", padx=18, pady=(8, 12))
        self.generate_brief_if_needed(todays)

        self.section(p, "Open commitments", f"{len(open_)} open")
        if not open_:
            self.empty(p, "Nothing open. Promises you make and things people ask of you appear here.")
        for m in open_[:30]:
            self.card(p, m)

        self.section(p, "Today", f"{len(todays)} noted")
        if not todays:
            c = self.store.counts()
            hint = ("Nothing understood from today yet. "
                    + (f"{c['pending']} captured screen(s) are waiting - press Read now." if c["pending"]
                       else "Start capture on the Capture page, then use your apps as normal."))
            self.empty(p, hint)
        for m in reversed(todays):
            self.card(p, m)

    def fallback_brief(self, today: int, open_: int) -> str:
        moments = "One moment" if today == 1 else f"{today} moments"
        if self.store.pending_count() > 0 and today == 0:
            return "Still reading your day."
        if today == 0 and open_ == 0:
            return "Nothing recorded for today yet."
        if open_ == 0:
            return f"{moments} from today, and nothing left open."
        if open_ == 1:
            return f"{moments} from today, with one still open."
        return f"{moments} from today, with {open_} still open."

    def generate_brief_if_needed(self, todays):
        if self.brief or self._brief_running or not todays or self.engine.state != "ready":
            return
        self._brief_running = True

        def done(res):
            self._brief_running = False
            if isinstance(res, str) and res:
                self.brief = res
                if self.current == "Today":
                    self.brief_label.configure(text=res)
        self.bg(lambda: assist.morning_brief(self.engine, todays), done)

    def read_now(self):
        if self.pipeline.progress.get("running"):
            self.pipeline.cancel()
            return
        if not find_model():
            if messagebox.askyesno("Model not installed", "Gemma isn't downloaded yet. Open Model & settings?",
                                   parent=self.root):
                self.show("Model & settings")
            return
        self.pipeline.run_in_background()

    def on_progress(self, p):
        running = p.get("running")
        self.read_btn.configure(text="Stop reading" if running else "Read now")
        msg = p.get("message") or ""
        if running and p.get("total"):
            msg = f"{msg}   ·   {p['done']} of {p['total']} done"
            self.progress_bar.configure(maximum=p["total"], value=p["done"])
            if not self.progress_bar.winfo_ismapped():
                self.progress_bar.pack(anchor="w", padx=30, after=self.progress_label)
        elif self.progress_bar.winfo_ismapped():
            self.progress_bar.pack_forget()
        self.progress_label.configure(text=msg)
        self.update_status()
        if not running:
            self.schedule_refresh()

    def add_task(self):
        w = tk.Toplevel(self.root)
        w.title("Add a task")
        w.configure(bg=C["panel"])
        w.geometry("460x300")
        w.transient(self.root)
        fields = {}
        for i, (key, label, hint) in enumerate((("title", "What", ""), ("due", "Due", "e.g. 2026-10-12 17:00, or blank"),
                                                ("person", "Person", "optional"), ("category", "Category", "Work / Personal / Home / Other"))):
            tk.Label(w, text=label, bg=C["panel"], font=(FONT + " Semibold", 10)).grid(row=i * 2, column=0, sticky="w", padx=16, pady=(10, 0))
            e = ttk.Entry(w, width=48)
            e.grid(row=i * 2 + 1, column=0, sticky="w", padx=16)
            if hint:
                e.insert(0, "")
                tk.Label(w, text=hint, bg=C["panel"], fg=C["muted"], font=(FONT, 8)).grid(row=i * 2, column=0, sticky="e", padx=16)
            fields[key] = e
        fields["title"].focus_set()

        def save():
            title = fields["title"].get().strip()
            if not title:
                return
            due = None
            raw = fields["due"].get().strip()
            if raw:
                for fmt, explicit in (("%Y-%m-%d %H:%M", True), ("%Y-%m-%d", False)):
                    try:
                        d = datetime.strptime(raw, fmt)
                        if not explicit:
                            d = d.replace(hour=9)
                        due = int(d.timestamp() * 1000)
                        break
                    except ValueError:
                        continue
                if due is None:
                    messagebox.showerror("Due", "Use YYYY-MM-DD or YYYY-MM-DD HH:MM", parent=w)
                    return
            cat = fields["category"].get().strip().capitalize() or None
            self.store.add_manual(title, due, cat if cat in extraction.CATEGORIES else None,
                                  fields["person"].get().strip() or None)
            w.destroy()
        ttk.Button(w, text="Add", style="Accent.TButton", command=save).grid(row=9, column=0, sticky="e", padx=16, pady=14)
        w.bind("<Return>", lambda e: save())

    # ── Calendar ───────────────────────────────────────────────────────

    def build_calendar(self, page):
        h = self.header(page, "Calendar", "Dated memories sit on their deadline; the rest on the day they were seen.")
        nav = tk.Frame(h, bg=C["bg"])
        nav.place(relx=1.0, rely=0.0, anchor="ne")
        ttk.Button(nav, text="<", width=3, command=lambda: self.cal_move(-1)).pack(side="left")
        ttk.Button(nav, text="Today", command=lambda: self.cal_move(0)).pack(side="left", padx=4)
        ttk.Button(nav, text=">", width=3, command=lambda: self.cal_move(1)).pack(side="left")
        self.cal_month = datetime.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        self.cal_day = datetime.now().date()
        body = tk.Frame(page, bg=C["bg"])
        body.pack(fill="both", expand=True, padx=28, pady=(0, 12))
        self.cal_grid = tk.Frame(body, bg=C["bg"])
        self.cal_grid.pack(side="left", fill="y", anchor="n")
        self.cal_list = Scroll(body)
        self.cal_list.pack(side="left", fill="both", expand=True, padx=(18, 0))

    def cal_move(self, delta):
        if delta == 0:
            self.cal_month = datetime.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0)
            self.cal_day = datetime.now().date()
        else:
            y, mth = self.cal_month.year, self.cal_month.month + delta
            y, mth = y + (mth - 1) // 12, (mth - 1) % 12 + 1
            self.cal_month = self.cal_month.replace(year=y, month=mth)
        self.refresh_calendar()

    def refresh_calendar(self):
        g = self.cal_grid
        for w in g.winfo_children():
            w.destroy()
        first = self.cal_month
        nxt = (first + timedelta(days=32)).replace(day=1)
        items = self.store.month(int(first.timestamp() * 1000), int(nxt.timestamp() * 1000))
        by_day: dict = {}
        for m in items:
            d = datetime.fromtimestamp((m.dueAt or m.timestamp) / 1000).date()
            by_day.setdefault(d, []).append(m)
        tk.Label(g, text=first.strftime("%B %Y"), bg=C["bg"], font=(FONT + " Semibold", 13)).grid(
            row=0, column=0, columnspan=7, sticky="w", pady=(0, 8))
        for i, wd in enumerate(("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")):
            tk.Label(g, text=wd, bg=C["bg"], fg=C["muted"], font=(FONT, 9)).grid(row=1, column=i)
        day = first - timedelta(days=first.weekday())
        today = datetime.now().date()
        for r in range(6):
            for c in range(7):
                d = day.date()
                inside = d.month == first.month
                ms = by_day.get(d, [])
                open_n = sum(1 for m in ms if m.direction in extraction.OBLIGATION_DIRECTIONS and not m.commitmentDone)
                bgc = C["accent"] if d == self.cal_day else C["panel"] if inside else C["bg"]
                fgc = "white" if d == self.cal_day else C["text"] if inside else "#b8b8b8"
                cell = tk.Frame(g, bg=bgc, width=62, height=54, highlightthickness=1,
                                highlightbackground=C["orange"] if d == today else C["line"])
                cell.grid(row=r + 2, column=c, padx=2, pady=2)
                cell.pack_propagate(False)
                tk.Label(cell, text=str(d.day), bg=bgc, fg=fgc, font=(FONT + " Semibold", 10)).pack(anchor="nw", padx=5, pady=(3, 0))
                if ms:
                    dots = ("●" * min(len(ms), 4))
                    tk.Label(cell, text=dots, bg=bgc, font=(FONT, 7),
                             fg="white" if d == self.cal_day else (C["orange"] if open_n else C["accent"])).pack(anchor="w", padx=5)
                for w in [cell] + list(cell.winfo_children()):
                    w.bind("<Button-1>", lambda e, dd=d: self.cal_pick(dd))
                day += timedelta(days=1)
        # Day list
        sc = self.cal_list
        sc.clear()
        ms = by_day.get(self.cal_day, []) if self.cal_day.month == first.month else \
            self.store.month(*day_bounds(around_ms=int(datetime.combine(self.cal_day, datetime.min.time()).timestamp() * 1000)))
        self.section(sc.inner, self.cal_day.strftime("%A, %d %B"), f"{len(ms)} item(s)")
        if not ms:
            self.empty(sc.inner, "Nothing on this day.")
        for m in ms:
            self.card(sc.inner, m, show_date=True)

    def cal_pick(self, d):
        self.cal_day = d
        if d.month != self.cal_month.month:
            self.cal_month = datetime(d.year, d.month, 1)
        self.refresh_calendar()

    # ── Ask ────────────────────────────────────────────────────────────

    def build_ask(self, page):
        self.header(page, "Ask", "Answers come only from what DailyFlow has read on this PC - it says so when it doesn't know.")
        box = tk.Frame(page, bg=C["bg"])
        box.pack(fill="x", padx=28)
        self.ask_entry = ttk.Entry(box, font=(FONT, 12))
        self.ask_entry.pack(side="left", fill="x", expand=True, ipady=5)
        self.ask_entry.bind("<Return>", lambda e: self.ask())
        self.ask_btn = ttk.Button(box, text="Ask", style="Accent.TButton", command=self.ask)
        self.ask_btn.pack(side="left", padx=(8, 0))
        sug = tk.Frame(page, bg=C["bg"])
        sug.pack(fill="x", padx=28, pady=(8, 0))
        for q in ("What's still open?", "What did I promise this week?", "What is due tomorrow?"):
            b = tk.Label(sug, text=q, bg=C["grey_tint"], fg=C["text"], font=(FONT, 9), padx=10, pady=3, cursor="hand2")
            b.pack(side="left", padx=(0, 6))
            b.bind("<Button-1>", lambda e, q=q: (self.ask_entry.delete(0, "end"), self.ask_entry.insert(0, q), self.ask()))
        ans = tk.Frame(page, bg=C["panel"], highlightbackground=C["line"], highlightthickness=1)
        ans.pack(fill="x", padx=28, pady=14)
        self.ask_answer = tk.Label(ans, text="Ask about your day, a person, or what's due.", bg=C["panel"],
                                   fg=C["muted"], font=(FONT, 12), justify="left", anchor="w", wraplength=820)
        self.ask_answer.pack(fill="x", padx=16, pady=(14, 4))
        self.ask_cite = tk.Label(ans, text="", bg=C["panel"], fg=C["accent"], font=(FONT + " Semibold", 9), anchor="w")
        self.ask_cite.pack(fill="x", padx=16, pady=(0, 12))
        self.ask_hits = Scroll(page)
        self.ask_hits.pack(fill="both", expand=True, padx=24, pady=(0, 12))
        self._ask_buf = []

    def ask(self):
        q = self.ask_entry.get().strip()
        if not q or str(self.ask_btn.cget("state")) == "disabled":
            return
        if not find_model():
            self.ask_answer.configure(text="Gemma isn't downloaded yet - see Model & settings.", fg=C["red"])
            return
        hits = assist.search(self.store, q)
        self.ask_btn.configure(state="disabled")
        busy = self.pipeline.progress.get("running")
        self.ask_answer.configure(text="Waiting for the reading pass to finish…" if busy else "Thinking…", fg=C["muted"])
        self.ask_cite.configure(text="")
        sc = self.ask_hits
        sc.clear()
        used = [m for m, s in hits if s > 0]
        if used:
            self.section(sc.inner, "Memories used")
            for m in used:
                self.card(sc.inner, m, show_date=True)
        self._ask_buf = []

        def delta(t):
            self._ask_buf.append(t)
            self.call(lambda: self.ask_answer.configure(text="".join(self._ask_buf), fg=C["text"]))

        def done(res):
            self.ask_btn.configure(state="normal")
            if isinstance(res, str) and res:
                self.ask_answer.configure(text=res, fg=C["text"])
                self.ask_cite.configure(text=assist.citation(hits) or "")
            else:
                self.ask_answer.configure(text="Couldn't answer: " + str(self.engine.last_error or res), fg=C["red"])
        self.bg(lambda: self.engine.complete(assist.ask_prompt(q, hits), on_delta=delta), done)

    # ── People ─────────────────────────────────────────────────────────

    def build_people(self, page):
        self.header(page, "People", "Everyone who has asked you something or been promised something.")
        body = tk.Frame(page, bg=C["bg"])
        body.pack(fill="both", expand=True, padx=28, pady=(0, 12))
        left = tk.Frame(body, bg=C["panel"], highlightbackground=C["line"], highlightthickness=1, width=240)
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        self.people_list = tk.Listbox(left, font=(FONT, 11), relief="flat", bd=0, highlightthickness=0,
                                      activestyle="none", selectbackground="#dbe6fd", selectforeground=C["text"])
        self.people_list.pack(fill="both", expand=True, padx=6, pady=6)
        self.people_list.bind("<<ListboxSelect>>", lambda e: self.refresh_person())
        self.person_view = Scroll(body)
        self.person_view.pack(side="left", fill="both", expand=True, padx=(16, 0))
        self.person = None

    def refresh_people(self):
        names = self.store.people()
        self.people_list.delete(0, "end")
        for n in names:
            self.people_list.insert("end", n)
        if self.person in names:
            i = names.index(self.person)
            self.people_list.selection_set(i)
            self.people_list.see(i)
        elif names:
            self.person = None
        self.refresh_person(keep=True)

    def open_person(self, name):
        self.person = name
        self.show("People")

    def refresh_person(self, keep=False):
        sel = self.people_list.curselection()
        if sel and not keep:
            self.person = self.people_list.get(sel[0])
        sc = self.person_view
        sc.clear()
        p = sc.inner
        if not self.person:
            self.empty(p, "Pick a person." if self.people_list.size() else
                       "No people yet. Names appear once Gemma has read messages from them.")
            return
        st = self.store.person_stats(self.person)
        tk.Label(p, text=self.person, bg=C["bg"], font=(FONT + " Semibold", 18)).pack(anchor="w", padx=4)
        tk.Label(p, text=f"{st['open']} open · {st['kept']} kept · {st['total']} memories · last {assist.relative_short(st['last_at'])}",
                 bg=C["bg"], fg=C["muted"]).pack(anchor="w", padx=4)
        rel = tk.Frame(p, bg=C["side"])
        rel.pack(fill="x", padx=4, pady=10)
        tk.Label(rel, text="GEMMA · WHERE THINGS STAND", bg=C["side"], fg=C["side_text"],
                 font=(FONT + " Semibold", 8)).pack(anchor="w", padx=16, pady=(12, 2))
        ms = self.store.by_person(self.person)
        person = self.person
        # PersonScreen: the line from the counts (fallbackLine) until Gemma's
        # own is written - once per person, and only when the engine is
        # already loaded; opening a page never triggers a model load.
        line = tk.Label(rel, text=self.person_lines.get(person) or self.fallback_line(person, st),
                        bg=C["side"], fg="white", font=(FONT, 12), wraplength=700, justify="left", anchor="w")
        line.pack(fill="x", padx=16, pady=(0, 12))
        if person not in self.person_lines and self.engine.state == "ready":
            self.person_lines[person] = None        # claimed, so it runs once
            # commitments first, then the rest - the order PersonViewModel passes
            ordered = ([m for m in ms if m.direction in extraction.OBLIGATION_DIRECTIONS]
                       + [m for m in ms if m.direction not in extraction.OBLIGATION_DIRECTIONS])

            def done(res):
                if isinstance(res, str) and res:
                    self.person_lines[person] = res
                    if line.winfo_exists() and self.person == person:
                        line.configure(text=res)
            self.bg(lambda: assist.relationship_line(self.engine, person, ordered), done)
        self.section(p, "History")
        for m in ms:
            self.card(p, m, show_date=True)

    @staticmethod
    def fallback_line(name, st) -> str:
        open_, kept = st["open"], st["kept"]
        if open_ == 0 and kept == 0:
            return f"Nothing outstanding with {name}."
        if open_ == 0:
            return f"Nothing open with {name}. {kept} kept so far."
        if open_ == 1:
            return f"One thing is still open with {name}."
        return f"{open_} things are still open with {name}."

    # ── Memories ───────────────────────────────────────────────────────

    def build_memories(self, page):
        h = self.header(page, "Memories", "Everything understood, newest first.")
        f = tk.Frame(h, bg=C["bg"])
        f.place(relx=1.0, rely=0.0, anchor="ne")
        self.mem_filter = ttk.Entry(f, width=28)
        self.mem_filter.pack(side="left")
        self.mem_filter.bind("<KeyRelease>", lambda e: self.refresh_memories())
        tk.Label(f, text="filter", bg=C["bg"], fg=C["muted"]).pack(side="left", padx=6)
        self.mem_scroll = Scroll(page)
        self.mem_scroll.pack(fill="both", expand=True, padx=24, pady=(4, 12))

    def refresh_memories(self):
        sc = self.mem_scroll
        sc.clear()
        q = self.mem_filter.get().strip().lower()
        ms = self.store.all_memories(400)
        if q:
            ms = [m for m in ms if q in " ".join(x for x in (m.summary, m.personName, m.sourceQuote, source_label(m)) if x).lower()]
        c = self.store.counts()
        self.section(sc.inner, f"{len(ms)} shown", f"{c['captured']} screens captured · {c['pending']} waiting to be read")
        if not ms:
            self.empty(sc.inner, "Nothing yet.")
        last_day = None
        for m in ms[:200]:
            d = datetime.fromtimestamp(m.timestamp / 1000).strftime("%A, %d %B")
            if d != last_day:
                tk.Label(sc.inner, text=d, bg=C["bg"], fg=C["muted"], font=(FONT + " Semibold", 10)).pack(anchor="w", padx=6, pady=(12, 2))
                last_day = d
            self.card(sc.inner, m)

    # ── Voice ──────────────────────────────────────────────────────────

    def build_voice(self, page):
        self.header(page, "Voice note", "Say what you need to remember. Gemma listens to the audio itself - no transcription service.")
        mid = tk.Frame(page, bg=C["bg"])
        mid.pack(fill="x", pady=20)
        self.rec_btn = tk.Label(mid, text="●  Record", bg=C["orange"], fg="white", font=(FONT + " Semibold", 16),
                                padx=34, pady=16, cursor="hand2")
        self.rec_btn.pack()
        self.rec_btn.bind("<Button-1>", lambda e: self.toggle_record())
        self.voice_timer = tk.Label(mid, text=f"Up to {MAX_SECONDS} seconds", bg=C["bg"], fg=C["muted"])
        self.voice_timer.pack(pady=8)
        self.voice_status = tk.Label(mid, text="", bg=C["bg"], fg=C["text"], font=(FONT, 11), wraplength=700)
        self.voice_status.pack()
        self.voice_results = Scroll(page)
        self.voice_results.pack(fill="both", expand=True, padx=24, pady=(4, 12))

    def toggle_record(self):
        if not self.recorder.recording:
            if not find_model():
                self.voice_status.configure(text="Gemma isn't downloaded yet - see Model & settings.", fg=C["red"])
                return
            try:
                self.recorder.start()
            except Exception as e:
                self.voice_status.configure(text=f"Couldn't start the microphone: {e}", fg=C["red"])
                return
            self.rec_btn.configure(text="■  Stop", bg=C["red"])
            self.voice_status.configure(text="Listening…", fg=C["text"])
            return
        path = self.recorder.stop()
        self.rec_btn.configure(text="●  Record", bg=C["orange"])
        self.voice_timer.configure(text=f"Up to {MAX_SECONDS} seconds")
        if not path:
            self.voice_status.configure(text="Too short - hold on a little longer.", fg=C["muted"])
            return
        self.voice_status.configure(text="Gemma is listening to your note…", fg=C["muted"])
        recorded = now_ms()

        def work():
            reply = self.engine.complete(extraction.build_voice_prompt(recorded), audio_path=path)
            if reply is None:
                return None
            # The same block parser as screens, against no text: a spoken note
            # has nothing to verify a quote against.
            items = [ex for ex in extraction.parse_all(reply, "") if ex.is_memory]
            self.log("voice", f"found {len(items)} action items")
            return items

        def done(res):
            sc = self.voice_results
            sc.clear()
            if res is None or isinstance(res, Exception):
                self.voice_status.configure(text="The model isn't loaded, so this can't be read yet"
                                            + (f" ({self.engine.last_error})" if self.engine.last_error else ""),
                                            fg=C["red"])
                return
            if not res:
                self.voice_status.configure(text="Nothing to act on in that note", fg=C["muted"])
                return
            self.voice_status.configure(text=f"Found {len(res)} - save them to your day?", fg=C["text"])
            for ex in res:
                row = tk.Frame(sc.inner, bg=C["panel"], highlightbackground=C["line"], highlightthickness=1)
                row.pack(fill="x", padx=4, pady=4)
                due = ""
                if ex.due_at:
                    due = "  ·  " + datetime.fromtimestamp(ex.due_at / 1000).strftime("%a %d %b, %I:%M %p")
                tk.Label(row, text=ex.summary, bg=C["panel"], font=(FONT, 11), wraplength=760,
                         justify="left", anchor="w").pack(fill="x", padx=14, pady=(10, 0))
                tk.Label(row, text=(ex.person or "") + due, bg=C["panel"], fg=C["muted"],
                         anchor="w").pack(fill="x", padx=14, pady=(0, 10))
            bar = tk.Frame(sc.inner, bg=C["bg"])
            bar.pack(fill="x", padx=4, pady=8)

            def save():
                # Saved as manual memories: the owner said these out loud, so
                # they are theirs rather than overheard (VoiceViewModel.save).
                for ex in res:
                    self.store.add_manual(ex.summary, ex.due_at, None, ex.person)
                self.log("voice", f"saved {len(res)} items")
                sc.clear()
                self.voice_status.configure(text=f"Saved {len(res)} to your day", fg=C["green"])

            def discard():
                sc.clear()
                self.voice_status.configure(text="", fg=C["muted"])
            ttk.Button(bar, text="Save to my day", style="Accent.TButton", command=save).pack(side="left")
            ttk.Button(bar, text="Discard", command=discard).pack(side="left", padx=8)
        self.bg(work, done)

    # ── Capture (Level 1 UI, embedded) ─────────────────────────────────

    def build_capture(self, page):
        self.cap_state = "stopped"

        def on_state(state):
            self.cap_state = {"running": "on", "paused": "paused", "stopped": "off"}[state]
            self.update_status()
        self.capture = capture_ui.App(self.root, host=page, on_engine_state=on_state)

    # ── Model & settings ───────────────────────────────────────────────

    def build_settings(self, page):
        self.header(page, "Model & settings")
        sc = Scroll(page)
        sc.pack(fill="both", expand=True, padx=24, pady=(0, 12))
        p = sc.inner
        self.settings_inner = p

        self.section(p, "Gemma")
        box = tk.Frame(p, bg=C["panel"], highlightbackground=C["line"], highlightthickness=1)
        box.pack(fill="x", padx=4)
        self.model_status = tk.Label(box, text="", bg=C["panel"], justify="left", anchor="w", font=(FONT, 10))
        self.model_status.pack(fill="x", padx=14, pady=(12, 6))
        self.model_rows = tk.Frame(box, bg=C["panel"])
        self.model_rows.pack(fill="x", padx=14)
        self.dl_label = tk.Label(box, text="", bg=C["panel"], fg=C["muted"], anchor="w")
        self.dl_label.pack(fill="x", padx=14)
        self.dl_bar = ttk.Progressbar(box, mode="determinate", maximum=1000)
        self.dl_bar.pack(fill="x", padx=14, pady=(2, 8))
        row = tk.Frame(box, bg=C["panel"])
        row.pack(fill="x", padx=14, pady=(0, 12))
        tk.Label(row, text="Backend:", bg=C["panel"]).pack(side="left")
        self.backend_var = tk.StringVar(value=load_settings()["backend"])
        for v, t in (("cpu", "CPU"), ("gpu", "GPU (faster here - ~35 s a screen vs ~50-80 s)")):
            ttk.Radiobutton(row, text=t, value=v, variable=self.backend_var,
                            command=self.set_backend).pack(side="left", padx=6)
        ttk.Button(row, text="Test speed", command=self.benchmark).pack(side="right")
        ttk.Button(row, text="Open model folder", command=lambda: (model_dir().mkdir(parents=True, exist_ok=True), os.startfile(model_dir()))).pack(side="right", padx=6)

        self.section(p, "Reading and reminders")
        box2 = tk.Frame(p, bg=C["panel"], highlightbackground=C["line"], highlightthickness=1)
        box2.pack(fill="x", padx=4)
        s = load_settings()
        self.auto_var = tk.BooleanVar(value=s["auto_extract"])
        self.rem_var = tk.BooleanVar(value=s["reminders"])
        self.every_var = tk.StringVar(value=str(s["extract_every_min"]))
        r1 = tk.Frame(box2, bg=C["panel"])
        r1.pack(fill="x", padx=14, pady=(12, 4))
        ttk.Checkbutton(r1, text="Read new captures automatically every", variable=self.auto_var,
                        command=self.save_prefs).pack(side="left")
        sp = ttk.Spinbox(r1, from_=5, to=240, width=5, textvariable=self.every_var, command=self.save_prefs)
        sp.pack(side="left", padx=4)
        sp.bind("<FocusOut>", lambda e: self.save_prefs())
        tk.Label(r1, text="minutes (the phone uses 15)", bg=C["panel"], fg=C["muted"]).pack(side="left")
        r2 = tk.Frame(box2, bg=C["panel"])
        r2.pack(fill="x", padx=14, pady=(4, 12))
        ttk.Checkbutton(r2, text="Windows notification when a commitment is due", variable=self.rem_var,
                        command=self.save_prefs).pack(side="left")
        ttk.Button(r2, text="Test reminders", command=lambda: self.pipeline.test_reminders()).pack(side="right")
        self.extra_var = tk.BooleanVar(value=s.get("extra_checks", False))
        r4 = tk.Frame(box2, bg=C["panel"])
        r4.pack(fill="x", padx=14, pady=(0, 12))
        ttk.Checkbutton(r4, text="Extra checks for the smaller model (not in the phone app)",
                        variable=self.extra_var, command=self.save_prefs).pack(side="left")
        tk.Label(r4, text="off = the phone's logic exactly. On adds: weekday fix, advert rule, "
                          "label-quote rule, same-pass tick-off.",
                 bg=C["panel"], fg=C["muted"], font=(FONT, 9)).pack(side="left", padx=6)

        self.section(p, "Phones", "sync over your own Wi-Fi - no server, no account")
        self.phones_box = tk.Frame(p, bg=C["panel"], highlightbackground=C["line"], highlightthickness=1)
        self.phones_box.pack(fill="x", padx=4)

        self.section(p, "Your data")
        box3 = tk.Frame(p, bg=C["panel"], highlightbackground=C["line"], highlightthickness=1)
        box3.pack(fill="x", padx=4)
        tk.Label(box3, text=f"Database, captures and voice notes: {DATA_DIR}\n"
                            "Nothing leaves this PC except memories sent to a phone you paired, "
                            "encrypted, over your own Wi-Fi. Raw screen text never leaves.",
                 bg=C["panel"], justify="left", anchor="w").pack(fill="x", padx=14, pady=(12, 6))
        r3 = tk.Frame(box3, bg=C["panel"])
        r3.pack(fill="x", padx=14, pady=(0, 12))
        ttk.Button(r3, text="Open data folder", command=lambda: os.startfile(DATA_DIR)).pack(side="left")
        ttk.Button(r3, text="Import archived captures…", command=self.import_archive).pack(side="left", padx=8)
        ttk.Button(r3, text="Erase all memories", command=self.erase).pack(side="right")

        self.section(p, "Pipeline log", "app.jsonl - newest last")
        self.log_text = tk.Text(p, height=14, font=("Consolas", 9), relief="flat", bg="#fbfbfa", wrap="none")
        self.log_text.pack(fill="x", padx=4, pady=(0, 10))
        try:
            lines = APP_LOG_PATH.read_text(encoding="utf-8").splitlines()[-60:]
            import json
            for ln in lines:
                r = json.loads(ln)
                self.log_text.insert("end", f"{r['ts'][11:19]}  {r['tag']:<9} {r['msg']}\n")
        except (OSError, ValueError):
            pass
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    # ── phones ─────────────────────────────────────────────────────────

    def refresh_phones(self):
        box = self.phones_box
        for w in box.winfo_children():
            w.destroy()
        top = tk.Frame(box, bg=C["panel"])
        top.pack(fill="x", padx=14, pady=(12, 6))
        if self.sync.listening:
            ips = ", ".join(self.sync.addresses()) or "no network"
            status = f"This PC is visible to your phone as “{self.sync.pc_name}” ({ips})."
        else:
            status = self.sync.error or "Not listening."
        tk.Label(top, text=status, bg=C["panel"], fg=C["text"] if self.sync.listening else C["red"],
                 justify="left", anchor="w").pack(side="left")
        ttk.Button(top, text="Pair a phone", style="Accent.TButton",
                   command=self.pair_phone).pack(side="right")
        peers = self.store.peers()
        if not peers:
            tk.Label(box, text="No phone paired. On the phone: You → Add computer, then type the code "
                               "this PC shows.", bg=C["panel"], fg=C["muted"], anchor="w",
                     justify="left").pack(fill="x", padx=14, pady=(0, 12))
        for peer in peers:
            row = tk.Frame(box, bg=C["panel"])
            row.pack(fill="x", padx=14, pady=(0, 10))
            when = (f"last synced {assist.relative_time(peer['lastSyncAt'])}" if peer["lastSyncAt"]
                    else "not synced yet")
            tk.Label(row, text=f"📱 {peer['name']}", bg=C["panel"], font=(FONT + " Semibold", 10)).pack(side="left")
            tk.Label(row, text=f"  {when}" + (f" · {peer['lastAddress']}" if peer["lastAddress"] else ""),
                     bg=C["panel"], fg=C["muted"]).pack(side="left")
            ttk.Button(row, text="Forget", style="Small.TButton",
                       command=lambda d=peer["deviceId"], n=peer["name"]: self.forget_phone(d, n)).pack(side="right")

    def pair_phone(self):
        if not self.sync.listening:
            messagebox.showerror("Pair a phone", self.sync.error or "Not listening.", parent=self.root)
            return
        code = self.sync.begin_pairing()
        w = tk.Toplevel(self.root)
        w.title("Pair a phone")
        w.configure(bg=C["panel"])
        w.geometry("460x330")
        w.transient(self.root)
        self._pair_window = w
        tk.Label(w, text="On your phone: You → Add computer", bg=C["panel"],
                 font=(FONT + " Semibold", 12)).pack(pady=(20, 4))
        tk.Label(w, text=f"Pick “{self.sync.pc_name}”, then type this code:", bg=C["panel"],
                 fg=C["muted"]).pack()
        tk.Label(w, text=f"{code[:3]} {code[3:]}", bg=C["panel"], fg=C["accent"],
                 font=("Consolas", 40, "bold")).pack(pady=14)
        timer = tk.Label(w, text="", bg=C["panel"], fg=C["muted"])
        timer.pack()
        tk.Label(w, text="Both devices must be on the same Wi-Fi. Not listed on the phone? "
                         f"Type {', '.join(self.sync.addresses()[:1]) or 'this PC’s address'} there.",
                 bg=C["panel"], fg=C["muted"], font=(FONT, 9), wraplength=420).pack(pady=(10, 0))

        def tick():
            if not w.winfo_exists():
                return
            if not self.sync.pairing_active():
                timer.configure(text="Code expired - close and press Pair a phone again.")
                return
            s = self.sync.seconds_left()
            timer.configure(text=f"Expires in {s // 60}:{s % 60:02d} · {self.sync.attempts} tries left")
            w.after(1000, tick)
        tick()

        def close():
            self.sync.cancel_pairing()
            w.destroy()
        w.protocol("WM_DELETE_WINDOW", close)

    def on_paired(self, name):
        w = getattr(self, "_pair_window", None)
        if w is not None and w.winfo_exists():
            w.destroy()
        messagebox.showinfo("Paired", f"Paired with {name}.\n\nMemories now sync whenever the phone "
                                      "and this PC are on the same Wi-Fi.", parent=self.root)
        if self.current == "Model & settings":
            self.refresh_phones()

    def forget_phone(self, device_id, name):
        if messagebox.askyesno("Forget phone", f"Stop syncing with {name}?\n\nMemories already "
                               "synced stay on both devices.", parent=self.root):
            self.store.remove_peer(device_id)
            self.refresh_phones()

    def on_log(self, rec):
        self.log_text.configure(state="normal")
        self.log_text.insert("end", f"{rec['ts'][11:19]}  {rec['tag']:<9} {rec['msg']}\n")
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    def refresh_model(self):
        self.refresh_phones()
        m = find_model()
        s = load_settings()
        txt = (f"Installed: {m.name}  ({m.stat().st_size / 1e9:.2f} GB, {m.parent})" if m
               else f"No model installed yet. It will be saved to {s['model_dir']}.")
        txt += f"\nEngine: {self.engine.state}"
        if self.engine.last_load_s:
            txt += f" · last load {self.engine.last_load_s:.1f}s"
        lc = self.engine.last_call
        if lc and lc.get("total_s"):
            txt += f" · last call {lc['total_s']:.1f}s for {lc['prompt_chars']} chars in"
        if self.engine.last_error:
            txt += f"\nLast error: {self.engine.last_error}"
        self.model_status.configure(text=txt)
        for w in self.model_rows.winfo_children():
            w.destroy()
        for spec in CATALOG:
            r = tk.Frame(self.model_rows, bg=C["panel"])
            r.pack(fill="x", pady=3)
            target = model_dir() / spec.file_name
            have = target.is_file() and target.stat().st_size == spec.size_bytes
            part = part_file(spec)
            chosen = s["model_file"] == spec.file_name
            tk.Label(r, text=("● " if chosen else "○ ") + spec.display_name, bg=C["panel"],
                     font=(FONT + " Semibold", 10)).pack(side="left")
            tk.Label(r, text=f"  {spec.size_bytes / 1e9:.2f} GB · {spec.note}", bg=C["panel"],
                     fg=C["muted"], font=(FONT, 9)).pack(side="left")
            if have:
                if not chosen:
                    ttk.Button(r, text="Use", style="Small.TButton",
                               command=lambda f=spec.file_name: (save_settings(model_file=f), self.engine.unload(force=True), self.refresh_model())).pack(side="right")
                else:
                    tk.Label(r, text="in use", bg=C["panel"], fg=C["green"]).pack(side="right")
            elif self.downloading:
                pass
            else:
                label = f"Resume ({part.stat().st_size / spec.size_bytes:.0%})" if part.exists() else "Download"
                ttk.Button(r, text=label, style="Small.TButton",
                           command=lambda sp=spec: self.start_download(sp)).pack(side="right")

    def start_download(self, spec):
        if self.downloading:
            return
        self.downloading = True
        self._download_stop = False
        save_settings(model_file=spec.file_name)
        self.refresh_model()
        self.dl_label.configure(text=f"Downloading {spec.file_name}…")

        def prog(done, total, rate):
            self.call(lambda: (self.dl_bar.configure(value=1000 * done / total),
                               self.dl_label.configure(text=f"{done / 1e9:.2f} of {total / 1e9:.2f} GB · {rate / 1e6:.1f} MB/s")))

        def done(res):
            self.downloading = False
            ok, msg = res if isinstance(res, tuple) else (False, str(res))
            self.dl_label.configure(text=("Downloaded: " if ok else "") + msg, fg=C["green"] if ok else C["red"])
            self.refresh_model()
        self.bg(lambda: download(spec, prog, lambda: self._download_stop), done)

    def set_backend(self):
        save_settings(backend=self.backend_var.get())
        # Takes effect on the next load.
        self.engine.unload(force=True)
        self.refresh_model()

    def save_prefs(self):
        try:
            every = max(5, int(self.every_var.get()))
        except ValueError:
            every = 15
        save_settings(auto_extract=self.auto_var.get(), reminders=self.rem_var.get(), extract_every_min=every,
                      extra_checks=self.extra_var.get())
        self.pipeline.next_pass_at = time.time() + 60 * every

    def benchmark(self):
        """Runs the real extraction prompt on the newest capture and reports
        load and inference time - the Tuning screen's job, cut down."""
        if not find_model():
            return
        rows = self.store._q("SELECT * FROM memories WHERE eventType NOT IN ('MANUAL','VOICE') "
                             "AND captureType='screen' ORDER BY timestamp DESC LIMIT 1")
        text = rows[0].rawText if rows else "Rahul: can you send me the quotation by Friday 5pm?"
        src = source_label(rows[0]) if rows else "WhatsApp"
        self.model_status.configure(text="Testing… (loads the model, then reads one screen)")

        def work():
            self.engine.unload(force=True)
            t0 = time.time()
            reply = self.engine.complete(extraction.build_prompt(src, now_ms(), text, [], []))
            total = time.time() - t0
            return reply, total, self.engine.last_load_s, self.engine.last_call

        def done(res):
            if isinstance(res, Exception) or res[0] is None:
                messagebox.showerror("Test", f"Failed: {self.engine.last_error or res}", parent=self.root)
            else:
                reply, total, load_s, call = res
                blocks = extraction.parse_all(reply, text)
                messagebox.showinfo("Speed test", (
                    f"Backend: {load_settings()['backend'].upper()}\n"
                    f"Model load: {load_s:.1f}s\n"
                    f"Read one screen ({call['prompt_chars']} chars in): {call['total_s']:.1f}s\n"
                    f"First token after: {call['first_token_s'] or 0:.1f}s\n"
                    f"Reply: {call['reply_chars']} chars, {sum(b.is_memory for b in blocks)} memory block(s)\n\n"
                    f"The phone measured ~9.7 s per screen on E4B."), parent=self.root)
            self.engine.unload(force=True)
            self.refresh_model()
        self.bg(work, done)

    def import_archive(self):
        """Loads an archived captures.jsonl (logs\\archive\\<time>\\) into the
        store, through today's dedup - see dailyflow/importer.py."""
        from tkinter import filedialog
        from dailyflow.importer import import_archive
        path = filedialog.askopenfilename(
            parent=self.root, title="Choose an archived captures.jsonl",
            initialdir=str(DATA_DIR / "logs" / "archive"),
            filetypes=[("Capture log", "captures.jsonl*"), ("JSONL", "*.jsonl")])
        if not path:
            return

        def done(res):
            if isinstance(res, Exception):
                messagebox.showerror("Import", f"Couldn't import: {res}", parent=self.root)
                return
            self.log("import", f"{Path(path).parent.name}: {res}")
            messagebox.showinfo("Import", (
                f"Read {res['read']} captures.\n"
                f"Kept {res['screen']} screens and {res['update']} updates; "
                f"skipped {res['exact-dup'] + res['near-dup']} repeats.\n"
                f"Added {res['added']} new (anything imported before is not added twice).\n\n"
                "Press Read now on Today to have Gemma read them."), parent=self.root)
        self.bg(lambda: import_archive(self.store, Path(path)), done)

    def erase(self):
        if messagebox.askyesno("Erase all", "Delete every memory and capture from the database?\n\n"
                               "The JSONL capture logs are separate files and stay until you delete them.",
                               icon="warning", parent=self.root):
            self.store.erase_all()

    def on_close(self):
        if self.recorder.recording:
            self.recorder.cancel()
        self._download_stop = True
        self.pipeline.stop()
        self.sync.stop()
        if self.capture.engine:
            self.capture.engine.stop()
        try:
            self.engine.unload(force=True)
        except Exception:
            pass
        self.root.destroy()


def main():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass
    try:
        # Its own taskbar identity, rather than grouping under python.exe.
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("DailyFlow.Desktop")
    except (AttributeError, OSError):
        pass
    root = tk.Tk()
    DailyFlowApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
