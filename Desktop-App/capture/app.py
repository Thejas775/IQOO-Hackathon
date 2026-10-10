"""
DailyFlow Desktop - Level 1 capture window.

A UI over capture_logger.CaptureEngine: choose which apps may be read, start,
pause or stop capture, and watch every decision and capture as it happens.
The logs it shows are the same JSONL files the command-line logger writes.

Run:  python app.py      (or pythonw app.py, for no console window)
"""

import ctypes
import ctypes.wintypes as wt
import json
import os
import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, simpledialog, ttk

from capture_logger import (ALWAYS_BLOCKED, BROWSERS, DEFAULT_ALLOWLIST, DEFAULT_LOG_DIR,
                            Allowlist, CaptureEngine, exe_of, normalise_site)

user32 = ctypes.WinDLL("user32", use_last_error=True)
dwmapi = ctypes.WinDLL("dwmapi")
GW_OWNER = 4
GWL_EXSTYLE = -20
WS_EX_TOOLWINDOW = 0x00000080
DWMWA_CLOAKED = 14

HISTORY_LINES = 200

# Listed in the Websites panel from the start, unticked until chosen: the
# places where requests, deadlines and plans usually turn up.
SUGGESTED_SITES = [
    "mail.google.com",
    "calendar.google.com",
    "classroom.google.com",
    "app.slack.com",
    "teams.microsoft.com",
    "discord.com",
    "web.whatsapp.com",
]
MAX_ACTIVITY_ROWS = 2000
QUEUE_DRAIN_PER_TICK = 300

COLORS = {
    "bg": "#f6f7f9",
    "panel": "#ffffff",
    "text": "#1d2330",
    "muted": "#6b7280",
    "accent": "#2f6fed",
    "capture": "#137a3a",
    "drop": "#6b7280",
    "blocked": "#b45309",
    "error": "#b91c1c",
    "config": "#2f6fed",
    "running": "#16a34a",
    "paused": "#d97706",
    "stopped": "#9ca3af",
}


# ------------------------------------------------------------ open apps --

def open_apps() -> dict[str, str]:
    """exe -> one window title, for every app with a real top-level window.

    Filters to what the taskbar would show: visible, titled, unowned, not a
    tool window, and not cloaked - suspended UWP apps keep visible-but-cloaked
    windows that would otherwise list apps that are not really open.
    """
    apps: dict[str, str] = {}

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def visit(hwnd, _):
        if not user32.IsWindowVisible(hwnd) or user32.GetWindow(hwnd, GW_OWNER):
            return True
        if user32.GetWindowLongW(hwnd, GWL_EXSTYLE) & WS_EX_TOOLWINDOW:
            return True
        cloaked = wt.DWORD()
        dwmapi.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, ctypes.byref(cloaked), ctypes.sizeof(cloaked))
        if cloaked.value:
            return True
        n = user32.GetWindowTextLengthW(hwnd)
        if not n:
            return True
        title = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(hwnd, title, n + 1)
        pid = wt.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        exe = exe_of(pid.value)
        if exe and exe not in apps:
            apps[exe] = title.value
        return True

    user32.EnumWindows(visit, 0)
    return apps


def tail_jsonl(path: Path, n: int) -> list[dict]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()[-n:]
    except OSError:
        return []
    out = []
    for line in lines:
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


# ------------------------------------------------------------------- app --

class App:
    def __init__(self, root: tk.Tk, host: tk.Misc | None = None, on_engine_state=None):
        # host: build inside this frame instead of the whole window, so the
        # full app (../dailyflow_app.py) can show this as its Capture page.
        self.root = root
        self.host = host or root
        self.embedded = host is not None
        self.on_engine_state = on_engine_state or (lambda state: None)
        self.allowlist_path = DEFAULT_ALLOWLIST
        self.log_dir = DEFAULT_LOG_DIR
        self.events: queue.Queue = queue.Queue()
        self.engine: CaptureEngine | None = None
        self.thread: threading.Thread | None = None
        self.captures: dict[str, dict] = {}     # tree iid -> capture record
        self.stats = dict(captured=0, not_allowed=0, blocked=0, duplicates=0, errors=0)
        self.last_read_ms = None

        if not self.embedded:
            root.title("DailyFlow Desktop - Capture")
            root.geometry("1180x720")
            root.minsize(900, 560)
            root.configure(bg=COLORS["bg"])
            self._style()
        self._build()

        self.refresh_apps()
        self._load_history()
        self._set_state("stopped")
        if not self.embedded:
            root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.after(150, self._drain)

    # ------------------------------------------------------------- style --

    def _style(self):
        s = ttk.Style(self.root)
        s.theme_use("clam")
        base = ("Segoe UI", 10)
        s.configure(".", font=base, background=COLORS["bg"], foreground=COLORS["text"])
        s.configure("Panel.TFrame", background=COLORS["panel"])
        s.configure("Panel.TLabel", background=COLORS["panel"])
        s.configure("Title.TLabel", font=("Segoe UI Semibold", 16))
        s.configure("Sub.TLabel", foreground=COLORS["muted"])
        s.configure("PanelSub.TLabel", background=COLORS["panel"], foreground=COLORS["muted"])
        s.configure("Head.TLabel", background=COLORS["panel"], font=("Segoe UI Semibold", 11))
        s.configure("Stat.TLabel", background=COLORS["panel"], font=("Segoe UI Semibold", 15))
        s.configure("StatSub.TLabel", background=COLORS["panel"], foreground=COLORS["muted"],
                    font=("Segoe UI", 9))
        s.configure("Treeview", rowheight=26, background=COLORS["panel"],
                    fieldbackground=COLORS["panel"], borderwidth=0)
        s.configure("Treeview.Heading", font=("Segoe UI Semibold", 9), background=COLORS["bg"],
                    foreground=COLORS["muted"], relief="flat")
        s.map("Treeview", background=[("selected", "#dbe6fd")], foreground=[("selected", COLORS["text"])])
        s.configure("Accent.TButton", background=COLORS["accent"], foreground="white",
                    font=("Segoe UI Semibold", 10), padding=(14, 6), borderwidth=0)
        s.map("Accent.TButton", background=[("active", "#2559c7"), ("disabled", "#a5b8e8")])
        s.configure("TButton", padding=(12, 6))
        s.configure("TNotebook", background=COLORS["bg"], borderwidth=0)
        s.configure("TNotebook.Tab", padding=(16, 6), font=("Segoe UI Semibold", 10),
                    background="#e5e7eb", foreground=COLORS["muted"])
        s.map("TNotebook.Tab", background=[("selected", COLORS["panel"])],
              foreground=[("selected", COLORS["accent"])])
        s.configure("TCheckbutton", background=COLORS["panel"])

    # ------------------------------------------------------------- build --

    def _build(self):
        top = ttk.Frame(self.host, padding=(18, 14, 18, 8))
        top.pack(fill="x")
        left = ttk.Frame(top)
        left.pack(side="left")
        ttk.Label(left, text="DailyFlow", style="Title.TLabel").pack(anchor="w")
        ttk.Label(left, text="Desktop capture - Level 1. Reads only the apps you tick; "
                             "everything stays on this PC.", style="Sub.TLabel").pack(anchor="w")

        right = ttk.Frame(top)
        right.pack(side="right")
        self.status_dot = tk.Canvas(right, width=12, height=12, highlightthickness=0, bg=COLORS["bg"])
        self.status_dot.pack(side="left", padx=(0, 6))
        self.status_label = ttk.Label(right, text="", font=("Segoe UI Semibold", 10))
        self.status_label.pack(side="left", padx=(0, 14))
        self.pause_btn = ttk.Button(right, text="Pause", command=self.toggle_pause)
        self.pause_btn.pack(side="left", padx=(0, 8))
        self.start_btn = ttk.Button(right, text="Start capture", style="Accent.TButton",
                                    command=self.toggle_run)
        self.start_btn.pack(side="left")

        # Packed before the body, which expands: otherwise the body takes the
        # whole height and the footer is pushed off the bottom of the window.
        foot = ttk.Frame(self.host, padding=(18, 0, 18, 12))
        foot.pack(side="bottom", fill="x")
        # Buttons first: pack hands out space in order, and a long path would
        # otherwise squeeze them off the edge.
        ttk.Button(foot, text="Open logs folder", command=self.open_logs).pack(side="right")
        ttk.Button(foot, text="Open allowlist.json", command=self.open_allowlist).pack(side="right", padx=8)
        ttk.Label(foot, text=f"Logs: {self.log_dir}", style="Sub.TLabel").pack(side="left")

        body = ttk.Frame(self.host, padding=(18, 4, 18, 8))
        body.pack(fill="both", expand=True)
        body.columnconfigure(1, weight=1)
        body.rowconfigure(0, weight=1)

        self._build_apps(body)
        self._build_tabs(body)

    def _build_apps(self, body):
        # Apps and Websites as two tabs. Stacked in one column they did not
        # both fit, and the apps list was squeezed to nothing.
        left = ttk.Notebook(body, width=360)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 14))

        self.apps_tree = self._tick_list(
            left, "Apps", "Apps DailyFlow can read",
            "Click to allow or block. Nothing is read\n"
            "unless ticked. Applies immediately.",
            "App", self.on_app_click,
            (("Refresh", self.refresh_apps), ("Add by name...", self.add_by_name)))
        self.apps_tree.tag_configure("blocked", foreground=COLORS["stopped"])

        # Websites: read only inside Chrome/Edge, only on these hosts, and
        # only when the browser itself is not ticked as an app.
        self.sites_tree = self._tick_list(
            left, "Websites", "Websites DailyFlow can read",
            "Click to allow or block. Read in Chrome\n"
            "or Edge; unticked sites are never read.",
            "Website", self.on_site_click,
            (("Add site...", self.add_site),))

    def _tick_list(self, notebook, tab, heading, hint, column, on_click, buttons):
        """One tab holding a heading, a hint, a tickable list and buttons."""
        panel = ttk.Frame(notebook, style="Panel.TFrame", padding=14)
        notebook.add(panel, text=tab)
        panel.rowconfigure(2, weight=1)
        panel.columnconfigure(0, weight=1)
        ttk.Label(panel, text=heading, style="Head.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(panel, text=hint, style="PanelSub.TLabel").grid(row=1, column=0, sticky="w",
                                                                  pady=(2, 10))
        frame = ttk.Frame(panel, style="Panel.TFrame")
        frame.grid(row=2, column=0, sticky="nsew")
        frame.rowconfigure(0, weight=1)
        frame.columnconfigure(0, weight=1)
        tree = ttk.Treeview(frame, columns=("check", "name", "state"), show="headings",
                            selectmode="none")
        for col, text, width, anchor in (("check", "", 34, "center"), ("name", column, 200, "w"),
                                         ("state", "", 90, "w")):
            tree.heading(col, text=text, anchor=anchor)
            tree.column(col, width=width, anchor=anchor, stretch=(col == "name"))
        sb = ttk.Scrollbar(frame, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=sb.set)
        tree.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        tree.tag_configure("allowed", foreground=COLORS["capture"])
        tree.bind("<Button-1>", on_click)
        btns = ttk.Frame(panel, style="Panel.TFrame")
        btns.grid(row=3, column=0, sticky="ew", pady=(10, 0))
        for i, (label, command) in enumerate(buttons):
            ttk.Button(btns, text=label, command=command).pack(side="left", padx=(0 if i == 0 else 8, 0))
        return tree

    def _build_tabs(self, body):
        nb = ttk.Notebook(body)
        nb.grid(row=0, column=1, sticky="nsew")

        # Activity
        act = ttk.Frame(nb, style="Panel.TFrame", padding=12)
        nb.add(act, text="Activity")
        act.rowconfigure(1, weight=1)
        act.columnconfigure(0, weight=1)

        stats = ttk.Frame(act, style="Panel.TFrame")
        stats.grid(row=0, column=0, sticky="ew", pady=(0, 10))
        self.stat_vars = {}
        for key, label in (("captured", "Captured"), ("not_allowed", "Not allowed"),
                           ("blocked", "Always blocked"), ("duplicates", "Duplicates"),
                           ("errors", "Errors"), ("last_read", "Last read")):
            cell = ttk.Frame(stats, style="Panel.TFrame")
            cell.pack(side="left", padx=(0, 26))
            var = tk.StringVar(value="0")
            ttk.Label(cell, textvariable=var, style="Stat.TLabel").pack(anchor="w")
            ttk.Label(cell, text=label, style="StatSub.TLabel").pack(anchor="w")
            self.stat_vars[key] = var
        self.stat_vars["last_read"].set("-")

        frame = ttk.Frame(act, style="Panel.TFrame")
        frame.grid(row=1, column=0, sticky="nsew")
        frame.rowconfigure(0, weight=1)
        frame.columnconfigure(0, weight=1)
        cols = (("time", "Time", 95), ("decision", "Decision", 80), ("reason", "Reason", 130),
                ("app", "App", 150), ("detail", "Window / detail", 380))
        self.act_tree = ttk.Treeview(frame, columns=[c[0] for c in cols], show="headings")
        for key, text, width in cols:
            self.act_tree.heading(key, text=text, anchor="w")
            self.act_tree.column(key, width=width, anchor="w", stretch=(key == "detail"))
        sb = ttk.Scrollbar(frame, orient="vertical", command=self.act_tree.yview)
        self.act_tree.configure(yscrollcommand=sb.set)
        self.act_tree.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        for tag in ("capture", "drop", "blocked", "error", "config"):
            self.act_tree.tag_configure(tag, foreground=COLORS[tag])

        opts = ttk.Frame(act, style="Panel.TFrame")
        opts.grid(row=2, column=0, sticky="ew", pady=(8, 0))
        self.show_dups = tk.BooleanVar(value=False)
        ttk.Checkbutton(opts, text="Show rescans that found nothing new",
                        variable=self.show_dups).pack(side="left")
        ttk.Button(opts, text="Clear view", command=lambda: self.act_tree.delete(*self.act_tree.get_children())
                   ).pack(side="right")

        # Captures
        cap = ttk.Frame(nb, style="Panel.TFrame", padding=12)
        nb.add(cap, text="Captures")
        pw = ttk.PanedWindow(cap, orient="vertical")
        pw.pack(fill="both", expand=True)

        top = ttk.Frame(pw, style="Panel.TFrame")
        top.rowconfigure(0, weight=1)
        top.columnconfigure(0, weight=1)
        cols = (("time", "Time", 140), ("app", "App", 150), ("title", "Window", 330),
                ("chars", "Chars", 70), ("ms", "Read ms", 70), ("trigger", "Trigger", 70))
        self.cap_tree = ttk.Treeview(top, columns=[c[0] for c in cols], show="headings", height=9)
        for key, text, width in cols:
            self.cap_tree.heading(key, text=text, anchor="w")
            self.cap_tree.column(key, width=width, anchor="w", stretch=(key == "title"))
        sb = ttk.Scrollbar(top, orient="vertical", command=self.cap_tree.yview)
        self.cap_tree.configure(yscrollcommand=sb.set)
        self.cap_tree.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        self.cap_tree.bind("<<TreeviewSelect>>", self.on_capture_select)
        pw.add(top, weight=1)

        bottom = ttk.Frame(pw, style="Panel.TFrame")
        bottom.rowconfigure(1, weight=1)
        bottom.columnconfigure(0, weight=1)
        self.cap_meta = ttk.Label(bottom, text="Select a capture to see exactly what was read.",
                                  style="PanelSub.TLabel")
        self.cap_meta.grid(row=0, column=0, sticky="w", pady=(8, 4))
        self.cap_text = tk.Text(bottom, wrap="word", font=("Consolas", 10), relief="flat",
                                bg="#fbfbfc", fg=COLORS["text"], padx=10, pady=8, state="disabled")
        tsb = ttk.Scrollbar(bottom, orient="vertical", command=self.cap_text.yview)
        self.cap_text.configure(yscrollcommand=tsb.set)
        self.cap_text.grid(row=1, column=0, sticky="nsew")
        tsb.grid(row=1, column=1, sticky="ns")
        pw.add(bottom, weight=2)

    # --------------------------------------------------------- apps list --

    def refresh_apps(self):
        allow = Allowlist(self.allowlist_path)
        running = open_apps()
        allowed_lower = {a.lower() for a in allow.names}

        rows = []
        for exe, title in running.items():
            rows.append((exe, title, True))
        for name in allow.names:
            if name.lower() not in {e.lower() for e in running}:
                rows.append((name, "", False))

        def order(r):
            exe = r[0].lower()
            blocked = exe in ALWAYS_BLOCKED
            return (blocked, exe not in allowed_lower, exe)

        self.apps_tree.delete(*self.apps_tree.get_children())
        for exe, title, is_running in sorted(rows, key=order):
            low = exe.lower()
            if low in ALWAYS_BLOCKED:
                check, state, tag = "-", "blocked", "blocked"
            elif low in allowed_lower:
                check, state, tag = "☑", "allowed" if is_running else "not open", "allowed"
            else:
                check, state, tag = "☐", "" if is_running else "not open", ""
            self.apps_tree.insert("", "end", iid=exe, values=(check, exe, state), tags=(tag,))

        # Suggested sites and any the user has added are always listed, ticked
        # or not, so a site can be switched off and back on without retyping.
        allowed_sites = set(allow.sites)
        listed = sorted(set(SUGGESTED_SITES) | set(allow.site_options) | allowed_sites,
                        key=lambda x: (x not in allowed_sites, x))
        self.sites_tree.delete(*self.sites_tree.get_children())
        for site in listed:
            if site in allowed_sites:
                self.sites_tree.insert("", "end", iid=site, values=("\u2611", site, "allowed"),
                                       tags=("allowed",))
            else:
                self.sites_tree.insert("", "end", iid=site, values=("\u2610", site, ""))

    def on_app_click(self, event):
        row = self.apps_tree.identify_row(event.y)
        if not row:
            return
        if row.lower() in ALWAYS_BLOCKED:
            messagebox.showinfo("Always blocked",
                                f"{row} is on the always-blocked list (shell, password managers, "
                                "remote sessions, DailyFlow itself) and can't be allowed.")
            return
        names = Allowlist(self.allowlist_path).names
        if row.lower() in {n.lower() for n in names}:
            names = [n for n in names if n.lower() != row.lower()]
        else:
            if row.lower() in BROWSERS and not messagebox.askyesno(
                    "Allow every website?",
                    f"Ticking {row} lets DailyFlow read every website you open in it, "
                    "including banking and anything private.\n\n"
                    "To read only certain sites (e.g. mail.google.com), leave it unticked and "
                    "add them under Websites instead.\n\nAllow every website anyway?"):
                return
            names.append(row)
        Allowlist.save(self.allowlist_path, names=names)
        self.refresh_apps()

    def add_by_name(self):
        name = simpledialog.askstring("Allow an app",
                                      "Program file name, e.g. Slack.exe or OUTLOOK.EXE:",
                                      parent=self.root)
        if not name:
            return
        name = name.strip()
        # A website typed here ("mail.google.com") would otherwise become a
        # program called mail.google.com.exe that never exists - which is
        # exactly what happened on 2026-10-10.
        stem = name[:-4] if name.lower().endswith(".exe") else name
        if "." in stem or "/" in stem:
            site = normalise_site(stem)
            if site and messagebox.askyesno(
                    "That looks like a website",
                    f"'{stem}' looks like a website, not a program.\n\n"
                    f"Allow {site} under Websites instead?"):
                allow = Allowlist(self.allowlist_path)
                Allowlist.save(self.allowlist_path, sites=sorted(set(allow.sites) | {site}),
                               site_options=sorted(set(allow.site_options) | {site}))
                self.refresh_apps()
            return
        if not name.lower().endswith(".exe"):
            name += ".exe"
        if name.lower() in ALWAYS_BLOCKED:
            messagebox.showinfo("Always blocked", f"{name} is always blocked and can't be allowed.")
            return
        names = Allowlist(self.allowlist_path).names
        if name.lower() not in {n.lower() for n in names}:
            Allowlist.save(self.allowlist_path, names=names + [name])
        self.refresh_apps()

    def on_site_click(self, event):
        site = self.sites_tree.identify_row(event.y)
        if not site:
            return
        allow = Allowlist(self.allowlist_path)
        if site in allow.sites:
            sites = [x for x in allow.sites if x != site]
        else:
            sites = allow.sites + [site]
        # Remembered as an option either way, so unticking keeps it listed.
        Allowlist.save(self.allowlist_path, sites=sites,
                       site_options=sorted(set(allow.site_options) | {site}))
        self.refresh_apps()

    def add_site(self):
        raw = simpledialog.askstring("Allow a website",
                                     "Website address, e.g. mail.google.com or app.slack.com:",
                                     parent=self.root)
        if not raw:
            return
        site = normalise_site(raw)
        if not site:
            messagebox.showinfo("Not a website", f"'{raw}' doesn't look like a website address.")
            return
        allow = Allowlist(self.allowlist_path)
        Allowlist.save(self.allowlist_path, sites=sorted(set(allow.sites) | {site}),
                       site_options=sorted(set(allow.site_options) | {site}))
        self.refresh_apps()

    # ----------------------------------------------------------- capture --

    def toggle_run(self):
        if self.thread and self.thread.is_alive():
            self.engine.stop()
            self.start_btn.configure(state="disabled", text="Stopping...")
            self._wait_for_stop()
            return
        allow = Allowlist(self.allowlist_path)
        if not allow.names and not allow.sites:
            if not messagebox.askyesno("Nothing allowed",
                                       "No apps or websites are allowed, so nothing will be "
                                       "captured.\n\nStart anyway?"):
                return
        self.engine = CaptureEngine(self.allowlist_path, self.log_dir, self.events.put)
        self.thread = threading.Thread(target=self.engine.run, name="capture", daemon=True)
        self.thread.start()
        self._set_state("running")

    def _wait_for_stop(self):
        # A read in progress can take several seconds (WhatsApp ~6 s), and the
        # engine only notices the stop between reads.
        if self.thread and self.thread.is_alive():
            self.root.after(200, self._wait_for_stop)
        else:
            self._set_state("stopped")

    def toggle_pause(self):
        if not self.engine or not self.thread or not self.thread.is_alive():
            return
        self.engine.paused = not self.engine.paused
        self._set_state("paused" if self.engine.paused else "running")

    def _set_state(self, state):
        color = COLORS[state]
        self.status_dot.delete("all")
        self.status_dot.create_oval(1, 1, 11, 11, fill=color, outline=color)
        self.status_label.configure(text={"running": "Capturing", "paused": "Paused",
                                          "stopped": "Stopped"}[state], foreground=color)
        self.on_engine_state(state)
        running = state != "stopped"
        self.start_btn.configure(state="normal", text="Stop capture" if running else "Start capture")
        self.pause_btn.configure(state="normal" if running else "disabled",
                                 text="Resume" if state == "paused" else "Pause")

    # ------------------------------------------------------------ events --

    def _drain(self):
        try:
            for _ in range(QUEUE_DRAIN_PER_TICK):
                rec = self.events.get_nowait()
                if rec["kind"] == "capture":
                    self._add_capture(rec, select_if_none=True)
                else:
                    self._add_activity(rec)
        except queue.Empty:
            pass
        self.root.after(150, self._drain)

    def _add_activity(self, rec, count=True):
        decision, reason = rec.get("decision", ""), rec.get("reason", "")
        if count:
            if decision == "capture":
                self.stats["captured"] += 1
            elif reason in ("not-allowed", "site-not-allowed"):
                self.stats["not_allowed"] += 1
            elif reason == "always-blocked":
                self.stats["blocked"] += 1
            elif reason in ("exact-dup", "no-new-lines", "near-dup"):
                self.stats["duplicates"] += 1
            elif decision == "error":
                self.stats["errors"] += 1
            if rec.get("read_ms") is not None:
                self.stat_vars["last_read"].set(f"{rec['read_ms'] / 1000:.1f} s")
            for k, v in self.stats.items():
                self.stat_vars[k].set(str(v))
            if reason == "allowlist-reloaded":
                self.refresh_apps()

        if rec.get("quiet") and not self.show_dups.get():
            return

        if decision == "capture":
            detail = f"{rec.get('text_chars', 0):,} chars from {rec.get('elements', 0):,} elements" \
                     f" in {rec.get('read_ms', 0) / 1000:.1f} s - {rec.get('site') or rec.get('title', '')}"
        elif decision == "error":
            detail = rec.get("error", "")
        elif decision == "read":
            detail = f"reading {rec.get('site') or rec.get('title', '')}..."
        elif reason in ("allowlist-reloaded", "logger-started"):
            detail = "allowed: " + (", ".join(rec.get("allowlist", []) + rec.get("sites", [])) or "(none)")
        else:
            detail = rec.get("title", "")
        tag = {"capture": "capture", "error": "error", "config": "config",
               "start": "config", "stop": "config", "read": "config"}.get(decision, "drop")
        if reason == "always-blocked":
            tag = "blocked"

        self.act_tree.insert("", 0, values=(rec.get("ts", "")[11:19], decision, reason,
                                            rec.get("exe", ""), detail), tags=(tag,))
        children = self.act_tree.get_children()
        if len(children) > MAX_ACTIVITY_ROWS:
            self.act_tree.delete(*children[MAX_ACTIVITY_ROWS:])

    def _add_capture(self, rec, select_if_none=False):
        iid = self.cap_tree.insert("", 0, values=(rec.get("ts", "")[:19].replace("T", " "),
                                                  rec.get("site") or rec.get("exe", ""),
                                                  rec.get("title", ""),
                                                  f"{rec.get('text_chars', 0):,}", rec.get("read_ms", ""),
                                                  "update" if rec.get("type") == "update"
                                                  else rec.get("trigger", "")))
        self.captures[iid] = rec
        if select_if_none and not self.cap_tree.selection():
            self.cap_tree.selection_set(iid)

    def on_capture_select(self, _):
        sel = self.cap_tree.selection()
        if not sel:
            return
        rec = self.captures.get(sel[0])
        if not rec:
            return
        if rec.get("type") == "update":
            # Only the new lines are stored; the rest of the screen is the
            # earlier capture this one extends.
            kind = (f"UPDATE - only the {rec.get('new_lines', 0)} new line(s) since the screen "
                    f"captured at {str(rec.get('base_ts', ''))[11:19]}")
        else:
            kind = (f"{rec.get('elements', 0):,} elements, "
                    f"{rec.get('skipped_password', 0)} password fields skipped"
                    f"{'  |  truncated' if rec.get('truncated') else ''}")
        self.cap_meta.configure(
            text=f"{rec.get('site') or rec.get('exe')}  |  {rec.get('title')}  |  {kind}")
        self.cap_text.configure(state="normal")
        self.cap_text.delete("1.0", "end")
        self.cap_text.insert("1.0", rec.get("text", ""))
        self.cap_text.configure(state="disabled")

    def _load_history(self):
        for rec in tail_jsonl(self.log_dir / "captures.jsonl", HISTORY_LINES):
            self._add_capture(rec)
        for rec in tail_jsonl(self.log_dir / "trace.jsonl", HISTORY_LINES):
            rec["quiet"] = rec.get("reason") in ("exact-dup", "near-dup", "no-new-lines")
            self._add_activity(rec, count=False)

    # ----------------------------------------------------------- footer --

    def open_logs(self):
        self.log_dir.mkdir(parents=True, exist_ok=True)
        os.startfile(self.log_dir)

    def open_allowlist(self):
        if not self.allowlist_path.exists():
            Allowlist.save(self.allowlist_path, names=[], sites=[])
        os.startfile(self.allowlist_path)

    def on_close(self):
        if self.engine:
            self.engine.stop()
        # The capture thread is a daemon: if it is mid-read it is abandoned
        # rather than holding the window open for several seconds.
        self.root.destroy()


def main():
    try:
        # Without this, Windows bitmap-scales the window on high-DPI screens
        # and every label comes out blurred.
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
