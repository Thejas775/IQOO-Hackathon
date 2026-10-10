"""Loads an archived captures.jsonl into the store.

Archived sessions were recorded by earlier versions of the logger, before
the Android dedup gates and update records existed (2026-10-10: the 10:55
session stored 99 screens, 46 of them WhatsApp re-reads of one open chat).
Each one costs Gemma ~10-20 s to read, so the records are replayed through
today's Dedup first - the same decision live capture makes now - and only
what it would keep is imported. Each record's own time is used as "now", so
the 24 h window and update bases come out as they would have live.

The archive file is only read, never changed.
"""

import datetime
import json
import sys
from pathlib import Path

from .store import Store

CAPTURE_DIR = Path(__file__).resolve().parent.parent / "capture"


def _dedup_tools():
    if str(CAPTURE_DIR) not in sys.path:
        sys.path.insert(0, str(CAPTURE_DIR))
    from capture_logger import Dedup, source_key
    return Dedup, source_key


def replay(path: Path) -> tuple[list[dict], dict]:
    """Returns (records to import, counts by decision)."""
    Dedup, source_key = _dedup_tools()
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            r = json.loads(line)
        except ValueError:
            continue
        if r.get("text") and r.get("exe") and r.get("epoch_ms"):
            records.append(r)
    records.sort(key=lambda r: r["epoch_ms"])

    dedup = Dedup(CAPTURE_DIR / "__no_history__.jsonl")   # missing file: empty history
    counts = {"read": len(records), "screen": 0, "update": 0, "exact-dup": 0, "near-dup": 0}
    out = []
    for r in records:
        t = r["epoch_ms"] / 1000
        d = dedup.check(source_key(r["exe"], r.get("site")), r["text"], now=t)
        counts[d["action"]] += 1
        if d["action"] in ("exact-dup", "near-dup"):
            continue
        is_update = d["action"] == "update"
        out.append({
            **r,
            "type": "update" if is_update else "screen",
            "text": d["new_text"] if is_update else r["text"],
            "base_ts": (datetime.datetime.fromtimestamp(d["base_ts"]).astimezone()
                        .isoformat(timespec="milliseconds") if is_update else None),
        })
    return out, counts


def import_archive(store: Store, path: Path) -> dict:
    records, counts = replay(path)
    counts["added"] = store.import_records(records)
    return counts
