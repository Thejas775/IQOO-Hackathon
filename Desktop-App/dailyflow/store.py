"""The memory store: Android's Room table `memories`, in SQLite.

Same columns and the same meaning as MemoryEntity.kt, so the queries port
one-for-one from MemoryDao.kt. Desktop changes:

  * packageName holds the exe ("whatsapp.exe"); `site` holds the host for
    browser pages ("mail.google.com"). Together they are the source.
  * captureType is "screen" or "update". An update holds only the lines that
    were new on a screen already stored (see capture_logger.Dedup); baseId
    points at that screen so extraction can give the model its context.
  * captureKey makes ingest idempotent: the JSONL capture log is the source
    of truth and this table is built from it, so re-reading a line must not
    insert it twice.
  * No embedding columns. Android stores MobileBERT vectors that nothing reads
    (it scores unrelated summaries at 0.9975), so the port leaves them out.
  * Sync with the phone (sync_server.py): syncId is the row's id on every
    device; originDevice/originName/originKind say where it was made (NULL =
    this PC); changeSeq orders local changes so a sync sends only what is new;
    lastChangedBy names the peer a change came from, so it is not echoed back;
    deleted synced rows leave a tombstone so the deletion travels too.

Writes are column-scoped UPDATEs, never whole-row replaces, for the reason
MemoryDao gives: extraction and the UI can touch the same row concurrently.
"""

import json
import re
import sqlite3
import threading
import time
from dataclasses import dataclass, fields
from datetime import datetime, timedelta
from pathlib import Path

from .paths import DB_PATH, LOG_DIR

# MemoryRepository.kt constants, unchanged.
DEDUP_WINDOW_MS = 24 * 60 * 60 * 1000
RECENT_SUMMARY_SCAN = 60
SUMMARY_OVERLAP_THRESHOLD = 0.55
OPEN_COMMITMENT_PROMPT_LIMIT = 12
RETRIEVAL_POOL = 500

SCHEMA = """
CREATE TABLE IF NOT EXISTS memories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    packageName TEXT NOT NULL,
    site TEXT,
    title TEXT,
    rawText TEXT NOT NULL,
    eventType TEXT,
    captureType TEXT NOT NULL DEFAULT 'screen',
    baseId INTEGER,
    captureKey TEXT UNIQUE,
    extracted INTEGER NOT NULL DEFAULT 0,
    summary TEXT,
    isLoop INTEGER,
    sourceQuote TEXT,
    direction TEXT,
    dueAt INTEGER,
    dueAtExplicit INTEGER NOT NULL DEFAULT 0,
    reminderSent INTEGER NOT NULL DEFAULT 0,
    personName TEXT,
    category TEXT,
    projectName TEXT,
    commitmentDone INTEGER NOT NULL DEFAULT 0,
    parentId INTEGER
);
CREATE INDEX IF NOT EXISTS idx_pkg_ts ON memories(packageName, timestamp);
CREATE INDEX IF NOT EXISTS idx_due ON memories(dueAt);
CREATE INDEX IF NOT EXISTS idx_person ON memories(personName);
CREATE INDEX IF NOT EXISTS idx_extracted ON memories(extracted);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS sync_tombstones (
    syncId TEXT PRIMARY KEY,
    deletedAt INTEGER NOT NULL,
    changeSeq INTEGER NOT NULL,
    lastChangedBy TEXT
);
CREATE TABLE IF NOT EXISTS sync_peers (
    deviceId TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    syncKey BLOB NOT NULL,
    pairedAt INTEGER NOT NULL,
    lastSyncAt INTEGER,
    lastAddress TEXT
);
"""

# Added after the first release of the table, so applied by migrate() to a
# database that already has rows rather than only by CREATE TABLE.
SYNC_COLUMNS = [
    ("syncId", "TEXT"),
    ("originDevice", "TEXT"),
    ("originName", "TEXT"),
    ("originKind", "TEXT"),
    ("updatedAt", "INTEGER NOT NULL DEFAULT 0"),
    ("changeSeq", "INTEGER NOT NULL DEFAULT 0"),
    ("lastChangedBy", "TEXT"),
]

# The next change number, shared by rows and tombstones so one cursor covers both.
NEXT_SEQ = ("(SELECT MAX(s) FROM (SELECT IFNULL(MAX(changeSeq),0) AS s FROM memories "
            "UNION ALL SELECT IFNULL(MAX(changeSeq),0) FROM sync_tombstones)) + 1")


@dataclass
class Memory:
    id: int
    timestamp: int
    packageName: str
    site: str | None
    title: str | None
    rawText: str
    eventType: str | None
    captureType: str
    baseId: int | None
    captureKey: str | None
    extracted: int
    summary: str | None
    isLoop: int | None
    sourceQuote: str | None
    direction: str | None
    dueAt: int | None
    dueAtExplicit: int
    reminderSent: int
    personName: str | None
    category: str | None
    projectName: str | None
    commitmentDone: int
    parentId: int | None
    syncId: str | None = None
    originDevice: str | None = None
    originName: str | None = None
    originKind: str | None = None
    updatedAt: int = 0
    changeSeq: int = 0
    lastChangedBy: str | None = None

    @property
    def source(self) -> str:
        """What the badge shows: the site for a browser page, else the app."""
        return self.site or self.packageName


_COLS = [f.name for f in fields(Memory)]


def _row(r) -> Memory | None:
    return Memory(*[r[c] for c in _COLS]) if r else None


# Android's tokenSet and jaccard (MemoryRepository.kt), unchanged.
_NON_WORD = re.compile(r"[^\w]+|_", re.UNICODE)


def token_set(text: str) -> set[str]:
    return {t for t in _NON_WORD.split(text.lower()) if len(t) >= 3}


def jaccard(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    shared = len(a & b)
    return shared / (len(a) + len(b) - shared)


def now_ms() -> int:
    return int(time.time() * 1000)


def day_bounds(offset_days: int = 0, around_ms: int | None = None) -> tuple[int, int]:
    """Local midnight to midnight, as MemoryRepository.dayBounds."""
    base = datetime.fromtimestamp((around_ms or now_ms()) / 1000)
    start = base.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=offset_days)
    end = start + timedelta(days=1)
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


OBLIGATIONS = "('SELF_PROMISE','THEY_ASKED')"


class Store:
    def __init__(self, path: Path = DB_PATH):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self._local = threading.local()
        self._ingest_lock = threading.Lock()
        with self._conn() as c:
            c.executescript(SCHEMA)
        self._migrate()
        # Listeners are told after any write, so open screens can refresh.
        self.listeners: list = []

    def _migrate(self):
        c = self._conn()
        have = {r[1] for r in c.execute("PRAGMA table_info(memories)")}
        with c:
            for name, decl in SYNC_COLUMNS:
                if name not in have:
                    c.execute(f"ALTER TABLE memories ADD COLUMN {name} {decl}")
            c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_syncid ON memories(syncId)")
            # Memories that existed before sync get an id and a change number,
            # so the first sync sends them. id is unique and positive, which
            # is all a change number has to be.
            c.execute("UPDATE memories SET syncId = lower(hex(randomblob(16))), "
                      "updatedAt = timestamp, changeSeq = id "
                      "WHERE isLoop = 1 AND summary IS NOT NULL AND syncId IS NULL")

    def touch(self, id_):
        """Marks a memory changed on this PC, so the next sync sends it.
        Only understood memories travel; a capture still waiting for Gemma, or
        judged not worth keeping, stays on this PC."""
        c = self._conn()
        with c:
            c.execute(f"UPDATE memories SET syncId = IFNULL(syncId, lower(hex(randomblob(16)))), "
                      f"updatedAt = ?, changeSeq = {NEXT_SEQ}, lastChangedBy = NULL "
                      f"WHERE id = ? AND isLoop = 1 AND summary IS NOT NULL", (now_ms(), id_))

    def _conn(self) -> sqlite3.Connection:
        c = getattr(self._local, "conn", None)
        if c is None:
            c = sqlite3.connect(self.path, timeout=15)
            c.row_factory = sqlite3.Row
            c.execute("PRAGMA journal_mode=WAL")
            self._local.conn = c
        return c

    def _changed(self):
        for fn in list(self.listeners):
            try:
                fn()
            except Exception:
                pass

    def _q(self, sql, args=()) -> list[Memory]:
        return [_row(r) for r in self._conn().execute(sql, args).fetchall()]

    def _one(self, sql, args=()):
        r = self._conn().execute(sql, args).fetchone()
        return r[0] if r else None

    def _w(self, sql, args=()) -> int:
        c = self._conn()
        with c:
            cur = c.execute(sql, args)
        self._changed()
        return cur.lastrowid

    def meta(self, key, default=None):
        v = self._one("SELECT value FROM meta WHERE key=?", (key,))
        return default if v is None else v

    def set_meta(self, key, value):
        c = self._conn()
        with c:
            c.execute("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)", (key, str(value)))

    # ── ingest: captures.jsonl -> memories ──────────────────────────────

    def ingest(self, log_dir: Path = LOG_DIR) -> int:
        """Reads capture records added since the last call. Returns rows added.

        The capture engine writes JSONL and knows nothing of this database;
        keeping it that way means the logger, the CLI and this app can each
        run alone, and the log stays the readable record of what was seen.
        """
        with self._ingest_lock:
            path = log_dir / "captures.jsonl"
            offset = int(self.meta("ingest_offset", 0))
            size = path.stat().st_size if path.exists() else 0
            added = 0
            if size < offset:
                # Rotated (renamed to .1) or archived since the last read: finish
                # the old generation from where we stopped, then start the new
                # file from the top.
                rotated = path.with_suffix(".jsonl.1")
                if rotated.exists():
                    added += self._ingest_file(rotated, offset)[0]
                offset = 0
            if path.exists():
                n, offset = self._ingest_file(path, offset)
                added += n
            self.set_meta("ingest_offset", offset)
        if added:
            self._changed()
        return added

    def _ingest_file(self, path: Path, offset: int) -> tuple[int, int]:
        added = 0
        c = self._conn()
        with path.open("rb") as f:
            f.seek(offset)
            with c:
                while True:
                    line = f.readline()
                    if not line or not line.endswith(b"\n"):
                        break       # a half-written last line waits for next time
                    offset = f.tell()
                    try:
                        rec = json.loads(line)
                    except ValueError:
                        continue
                    added += self._insert(c, rec)
        return added, offset

    def _insert(self, c: sqlite3.Connection, rec: dict) -> int:
        """One capture-log record -> one row. 0 when skipped or already there."""
        text = rec.get("text")
        if not text or not rec.get("exe"):
            return 0
        ctype = rec.get("type", "screen")
        base_id = None
        if ctype == "update" and rec.get("base_ts"):
            base_ms = int(datetime.fromisoformat(rec["base_ts"]).timestamp() * 1000)
            r = c.execute(
                "SELECT id FROM memories WHERE packageName=? AND IFNULL(site,'')=? "
                "AND captureType='screen' AND ABS(timestamp-?) < 1000 "
                "ORDER BY ABS(timestamp-?) LIMIT 1",
                (rec["exe"], rec.get("site") or "", base_ms, base_ms)).fetchone()
            base_id = r[0] if r else None
        cur = c.execute(
            "INSERT OR IGNORE INTO memories(timestamp, packageName, site, title, rawText, "
            "eventType, captureType, baseId, captureKey) VALUES(?,?,?,?,?,?,?,?,?)",
            (rec["epoch_ms"], rec["exe"], rec.get("site"), rec.get("title"), text,
             rec.get("trigger"), ctype, base_id,
             f"{rec['exe']}|{rec.get('site') or ''}|{rec['epoch_ms']}"))
        return cur.rowcount

    def import_records(self, records: list[dict]) -> int:
        """Inserts already-deduplicated capture records (see importer.py).
        Idempotent through captureKey, so importing a file twice adds nothing."""
        c = self._conn()
        with self._ingest_lock, c:
            added = sum(self._insert(c, r) for r in records)
        if added:
            self._changed()
        return added

    # ── manual, derived ─────────────────────────────────────────────────

    def add_manual(self, title, due_at=None, category=None, person=None) -> int:
        """A task typed by the owner. Skips every gate and is stored already
        understood, so it appears at once (MemoryRepository.addManual)."""
        id_ = self._insert_manual(title, due_at, category, person)
        self.touch(id_)
        return id_

    def _insert_manual(self, title, due_at, category, person) -> int:
        return self._w(
            "INSERT INTO memories(timestamp, packageName, rawText, eventType, extracted, isLoop, "
            "summary, direction, dueAt, dueAtExplicit, category, personName) "
            "VALUES(?, '', ?, 'MANUAL', 1, 1, ?, 'MANUAL', ?, ?, ?, ?)",
            (now_ms(), title, title, due_at, int(due_at is not None), category, person))

    def add_derived(self, parent: Memory, ex) -> int:
        """A further memory found on one screen (MemoryRepository.addDerived).
        The raw text is copied so quote checks still run against the screen
        it came from."""
        id_ = self._w(
            "INSERT INTO memories(timestamp, packageName, site, title, rawText, eventType, "
            "captureType, baseId, extracted, isLoop, summary, sourceQuote, direction, dueAt, "
            "dueAtExplicit, personName, category, projectName, parentId) "
            "VALUES(?,?,?,?,?,?,?,?,1,1,?,?,?,?,?,?,?,?,?)",
            (parent.timestamp, parent.packageName, parent.site, parent.title, parent.rawText,
             parent.eventType, parent.captureType, parent.baseId, ex.summary, ex.source_quote,
             ex.direction, ex.due_at, int(ex.due_at_explicit), ex.person, ex.category,
             ex.project, parent.id))
        self.touch(id_)
        return id_

    # ── pipeline ─────────────────────────────────────────────────────────

    def pending_extraction(self) -> list[Memory]:
        # Oldest first: a backlog drains in the order things happened.
        return self._q("SELECT * FROM memories WHERE extracted = 0 ORDER BY timestamp ASC")

    def pending_count(self) -> int:
        return self._one("SELECT COUNT(*) FROM memories WHERE extracted = 0")

    def save_extraction(self, id_, ex):
        if not ex.summary:
            self.mark_not_a_memory(id_)
            return
        self._w("UPDATE memories SET extracted=1, isLoop=1, summary=?, sourceQuote=?, direction=?, "
                "dueAt=?, dueAtExplicit=?, personName=?, category=?, projectName=? WHERE id=?",
                (ex.summary, ex.source_quote, ex.direction or "FROM_THEM", ex.due_at,
                 int(ex.due_at_explicit), ex.person, ex.category, ex.project, id_))
        self.touch(id_)

    def mark_not_a_memory(self, id_):
        self._w("UPDATE memories SET extracted=1, isLoop=0 WHERE id=?", (id_,))

    def has_similar_summary(self, summary: str) -> bool:
        """MemoryRepository.hasSimilarSummary: the capture gates compare screen
        text, so the same request scrolled differently survives them; the
        duplication only shows once both are summarised."""
        cand = token_set(summary)
        if len(cand) < 3:
            return False
        since = now_ms() - DEDUP_WINDOW_MS
        rows = self._conn().execute(
            "SELECT summary FROM memories WHERE summary IS NOT NULL AND timestamp > ? "
            "ORDER BY timestamp DESC LIMIT ?", (since, RECENT_SUMMARY_SCAN)).fetchall()
        return any(jaccard(cand, token_set(r[0])) >= SUMMARY_OVERLAP_THRESHOLD for r in rows)

    def recent_people(self, limit=20) -> list[str]:
        return [r[0] for r in self._conn().execute(
            "SELECT personName FROM memories WHERE isLoop=1 AND personName IS NOT NULL "
            "GROUP BY personName ORDER BY MAX(timestamp) DESC LIMIT ?", (limit,))]

    def open_commitments(self, limit=OPEN_COMMITMENT_PROMPT_LIMIT) -> list[Memory]:
        return self._q(
            f"SELECT * FROM memories WHERE isLoop=1 AND commitmentDone=0 AND direction IN {OBLIGATIONS} "
            "AND summary IS NOT NULL ORDER BY dueAt ASC LIMIT ?", (limit,))

    def by_id(self, id_) -> Memory | None:
        r = self._conn().execute("SELECT * FROM memories WHERE id=?", (id_,)).fetchone()
        return _row(r)

    def set_done(self, id_, done: bool):
        self._w("UPDATE memories SET commitmentDone=? WHERE id=?", (int(done), id_))
        self.touch(id_)

    def delete(self, id_):
        """Removes one memory; a synced one leaves a tombstone so the phone
        removes it too."""
        c = self._conn()
        with c:
            sid = c.execute("SELECT syncId FROM memories WHERE id=?", (id_,)).fetchone()
            if sid and sid[0]:
                c.execute(f"INSERT OR REPLACE INTO sync_tombstones(syncId, deletedAt, changeSeq, "
                          f"lastChangedBy) VALUES(?, ?, {NEXT_SEQ}, NULL)", (sid[0], now_ms()))
            c.execute("DELETE FROM memories WHERE id=?", (id_,))
        self._changed()

    # ── screens ──────────────────────────────────────────────────────────

    def captured_between(self, start, end) -> list[Memory]:
        return self._q("SELECT * FROM memories WHERE isLoop=1 AND summary IS NOT NULL "
                       "AND timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC", (start, end))

    def due_between(self, start, end) -> list[Memory]:
        return self._q("SELECT * FROM memories WHERE isLoop=1 AND dueAt >= ? AND dueAt < ? "
                       "ORDER BY dueAt ASC", (start, end))

    def open_all(self) -> list[Memory]:
        return self._q(
            f"SELECT * FROM memories WHERE isLoop=1 AND commitmentDone=0 AND direction IN {OBLIGATIONS} "
            "ORDER BY dueAt ASC")

    def kept_between(self, start, end) -> int:
        return self._one("SELECT COUNT(*) FROM memories WHERE isLoop=1 AND commitmentDone=1 "
                         "AND dueAt >= ? AND dueAt < ?", (start, end))

    def all_memories(self, limit=300) -> list[Memory]:
        return self._q("SELECT * FROM memories WHERE isLoop=1 AND summary IS NOT NULL "
                       "ORDER BY timestamp DESC LIMIT ?", (limit,))

    def month(self, start, end) -> list[Memory]:
        """Dated memories on their deadline, undated ones on their capture day
        (MemoryDao.observeMonth - the query that fixed 2-of-31 showing)."""
        return self._q(
            "SELECT * FROM memories WHERE isLoop=1 AND summary IS NOT NULL AND ("
            "(dueAt IS NOT NULL AND dueAt >= ? AND dueAt < ?) OR "
            "(dueAt IS NULL AND timestamp >= ? AND timestamp < ?)) "
            "ORDER BY IFNULL(dueAt, timestamp) ASC", (start, end, start, end))

    def people(self) -> list[str]:
        return [r[0] for r in self._conn().execute(
            "SELECT personName FROM memories WHERE isLoop=1 AND personName IS NOT NULL "
            "GROUP BY personName ORDER BY MAX(timestamp) DESC")]

    def by_person(self, person) -> list[Memory]:
        return self._q("SELECT * FROM memories WHERE isLoop=1 AND personName=? "
                       "ORDER BY timestamp DESC", (person,))

    def person_stats(self, person) -> dict:
        r = self._conn().execute(
            "SELECT "
            f"IFNULL(SUM(CASE WHEN commitmentDone=0 AND direction IN {OBLIGATIONS} THEN 1 ELSE 0 END),0), "
            f"IFNULL(SUM(CASE WHEN commitmentDone=1 AND direction IN {OBLIGATIONS} THEN 1 ELSE 0 END),0), "
            "COUNT(*), MAX(timestamp) FROM memories WHERE isLoop=1 AND personName=?",
            (person,)).fetchone()
        return {"open": r[0], "kept": r[1], "total": r[2], "last_at": r[3]}

    def retrieval_candidates(self, limit=RETRIEVAL_POOL) -> list[Memory]:
        return self._q("SELECT * FROM memories WHERE isLoop=1 AND summary IS NOT NULL "
                       "ORDER BY timestamp DESC LIMIT ?", (limit,))

    def pending_reminders(self) -> list[Memory]:
        # MemoryDao.getPendingReminders - done rows included; the fire step
        # re-checks and skips them, as ReminderWorker does.
        return self._q("SELECT * FROM memories WHERE isLoop=1 AND dueAt IS NOT NULL "
                       "AND reminderSent=0 ORDER BY dueAt ASC")

    def mark_reminder_sent(self, id_):
        self._w("UPDATE memories SET reminderSent=1 WHERE id=?", (id_,))

    def counts(self) -> dict:
        r = self._conn().execute(
            "SELECT COUNT(*), IFNULL(SUM(isLoop=1),0), IFNULL(SUM(extracted=0),0), "
            "IFNULL(SUM(LENGTH(rawText)),0) FROM memories "
            "WHERE eventType IS NOT 'MANUAL' AND originDevice IS NULL").fetchone()
        return {"captured": r[0], "memories": r[1], "pending": r[2], "bytes": r[3]}

    def erase_all(self):
        """'Erase all'. The JSONL logs are separate files the owner can delete;
        the ingest offset is kept so erased captures are not re-imported."""
        self._w("DELETE FROM memories")

    # ── sync (sync_server.py) ────────────────────────────────────────────

    def changes_since(self, seq: int, peer: str, limit: int) -> list[Memory]:
        """Understood memories changed after [seq], oldest change first,
        except changes that came from [peer] itself."""
        return self._q("SELECT * FROM memories WHERE changeSeq > ? AND syncId IS NOT NULL "
                       "AND isLoop = 1 AND summary IS NOT NULL "
                       "AND IFNULL(lastChangedBy, '') != ? ORDER BY changeSeq ASC LIMIT ?",
                       (seq, peer, limit))

    def tombstones_since(self, seq: int, peer: str, limit: int) -> list[dict]:
        return [dict(r) for r in self._conn().execute(
            "SELECT * FROM sync_tombstones WHERE changeSeq > ? AND IFNULL(lastChangedBy, '') != ? "
            "ORDER BY changeSeq ASC LIMIT ?", (seq, peer, limit))]

    def max_seq(self) -> int:
        return self._one(f"SELECT {NEXT_SEQ} - 1")

    def apply_remote(self, w: dict, peer: str) -> str:
        """One memory from the phone. Last writer wins on updatedAt; a change
        that loses is dropped. Returns inserted / updated / skipped."""
        c = self._conn()
        now = now_ms()
        due = w.get("dueAt")
        # The other device already nudged about anything due before it got
        # here; a sync must not fire a burst of stale reminders on this one.
        sent = 1 if (due is not None and due <= now) or w.get("commitmentDone") else 0
        with c:
            if c.execute("SELECT 1 FROM sync_tombstones WHERE syncId=? AND deletedAt >= ?",
                         (w["syncId"], w["updatedAt"])).fetchone():
                return "skipped"        # deleted here after that change was made
            row = c.execute("SELECT id, updatedAt FROM memories WHERE syncId=?",
                            (w["syncId"],)).fetchone()
            vals = (w.get("summary"), w.get("sourceQuote"), w.get("direction"), due,
                    int(bool(w.get("dueAtExplicit"))), w.get("personName"), w.get("category"),
                    w.get("projectName"), int(bool(w.get("commitmentDone"))), w["updatedAt"])
            if row is None:
                o = w.get("origin") or {}
                c.execute(
                    "INSERT INTO memories(timestamp, packageName, rawText, eventType, extracted, isLoop, "
                    "summary, sourceQuote, direction, dueAt, dueAtExplicit, personName, category, "
                    "projectName, commitmentDone, updatedAt, reminderSent, syncId, originDevice, "
                    f"originName, originKind, changeSeq, lastChangedBy) "
                    f"VALUES(?, ?, '', ?, 1, 1, ?,?,?,?,?,?,?,?,?,?, ?, ?, ?, ?, ?, {NEXT_SEQ}, ?)",
                    (w["timestamp"], w.get("source") or "", w.get("eventType")) + vals
                    + (sent, w["syncId"], o.get("id"), o.get("name"), o.get("kind"), peer))
                result = "inserted"
            elif w["updatedAt"] > row["updatedAt"]:
                c.execute(
                    "UPDATE memories SET summary=?, sourceQuote=?, direction=?, dueAt=?, dueAtExplicit=?, "
                    "personName=?, category=?, projectName=?, commitmentDone=?, updatedAt=?, "
                    f"reminderSent = MAX(reminderSent, ?), changeSeq = {NEXT_SEQ}, lastChangedBy=? "
                    "WHERE id=?", vals + (sent, peer, row["id"]))
                result = "updated"
            else:
                result = "skipped"
        if result != "skipped":
            self._changed()
        return result

    def apply_tombstone(self, t: dict, peer: str) -> bool:
        c = self._conn()
        with c:
            row = c.execute("SELECT id, updatedAt FROM memories WHERE syncId=?",
                            (t["syncId"],)).fetchone()
            if row is not None and row["updatedAt"] > t["deletedAt"]:
                return False            # changed here after it was deleted there
            c.execute(f"INSERT OR REPLACE INTO sync_tombstones(syncId, deletedAt, changeSeq, "
                      f"lastChangedBy) VALUES(?, ?, {NEXT_SEQ}, ?)",
                      (t["syncId"], t["deletedAt"], peer))
            if row is not None:
                c.execute("DELETE FROM memories WHERE id=?", (row["id"],))
        if row is not None:
            self._changed()
        return row is not None

    # paired phones

    def peers(self) -> list[dict]:
        return [dict(r) for r in self._conn().execute(
            "SELECT deviceId, name, pairedAt, lastSyncAt, lastAddress FROM sync_peers "
            "ORDER BY pairedAt")]

    def peer_key(self, device_id: str) -> bytes | None:
        r = self._conn().execute("SELECT syncKey FROM sync_peers WHERE deviceId=?",
                                 (device_id,)).fetchone()
        return bytes(r[0]) if r else None

    def add_peer(self, device_id: str, name: str, key: bytes):
        c = self._conn()
        with c:
            c.execute("INSERT OR REPLACE INTO sync_peers(deviceId, name, syncKey, pairedAt) "
                      "VALUES(?, ?, ?, ?)", (device_id, name, key, now_ms()))
        self._changed()

    def peer_synced(self, device_id: str, name: str, address: str):
        c = self._conn()
        with c:
            c.execute("UPDATE sync_peers SET lastSyncAt=?, lastAddress=?, name=? WHERE deviceId=?",
                      (now_ms(), address, name, device_id))

    def remove_peer(self, device_id: str):
        c = self._conn()
        with c:
            c.execute("DELETE FROM sync_peers WHERE deviceId=?", (device_id,))
        self._changed()

