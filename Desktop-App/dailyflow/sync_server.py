"""The PC end of phone sync: answers discovery, pairs, and syncs.

The PC only ever listens; the phone starts every exchange (it knows when it
is on Wi-Fi and awake, and a phone cannot be reached reliably from outside).
So changes made on the PC reach the phone the next time the phone syncs -
on opening the app, after its reading pass, every 15 minutes, or "Sync now".

Protocol and the reasons for its shape: sync_protocol.py.
"""

import hmac
import secrets
import socket
import socketserver
import threading
import time
import uuid

from . import sync_protocol as SP
from .store import Memory, Store, now_ms

PAIR_CODE_TTL_S = 5 * 60
PAIR_ATTEMPTS = 5
PAIR_PENDING_TTL_S = 60
BATCH = 200


class SyncServer:
    def __init__(self, store: Store, log=lambda tag, msg: None):
        self.store, self.log = store, log
        self.pc_id = store.meta("pc_id") or uuid.uuid4().hex
        store.set_meta("pc_id", self.pc_id)
        self.pc_name = store.meta("pc_name") or socket.gethostname()
        self._lock = threading.Lock()
        self.code: str | None = None
        self.code_expires = 0.0
        self.attempts = 0
        self._pending: dict[str, dict] = {}     # deviceId -> keys awaiting pair3
        self.listening = False
        self.error: str | None = None
        self.on_paired = lambda device_id, name: None
        self.on_synced = lambda name, summary: None
        self._tcp = None
        self._udp = None

    # ── lifecycle ──────────────────────────────────────────────────────

    def start(self):
        try:
            server = self

            class Handler(socketserver.BaseRequestHandler):
                def handle(self):
                    server._handle(self.request, self.client_address[0])

            socketserver.ThreadingTCPServer.allow_reuse_address = True
            self._tcp = socketserver.ThreadingTCPServer(("0.0.0.0", SP.SYNC_PORT), Handler)
            self._tcp.daemon_threads = True
            threading.Thread(target=self._tcp.serve_forever, name="sync-tcp", daemon=True).start()

            self._udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._udp.bind(("0.0.0.0", SP.DISCOVERY_PORT))
            threading.Thread(target=self._discovery, name="sync-udp", daemon=True).start()
            self.listening = True
            self.log("sync", f"listening on TCP {SP.SYNC_PORT} / UDP {SP.DISCOVERY_PORT} as '{self.pc_name}'")
        except OSError as e:
            self.error = f"Couldn't listen for phones: {e}"
            self.log("sync", self.error)

    def stop(self):
        if self._tcp:
            self._tcp.shutdown()
            self._tcp.server_close()
        if self._udp:
            self._udp.close()
        self.listening = False

    def _discovery(self):
        while True:
            try:
                data, addr = self._udp.recvfrom(1024)
            except OSError:
                return
            if data.strip() != SP.DISCOVER_MAGIC:
                continue
            reply = {"app": "dailyflow", "v": 1, "pcId": self.pc_id, "name": self.pc_name,
                     "port": SP.SYNC_PORT, "pairing": self.pairing_active()}
            try:
                import json
                self._udp.sendto(json.dumps(reply).encode(), addr)
            except OSError:
                pass

    @staticmethod
    def addresses() -> list[str]:
        """This PC's IPv4 addresses on its networks, for typing into the phone
        when the Wi-Fi drops discovery broadcasts."""
        found = []
        try:
            # The address the PC would use to reach the internet - the Wi-Fi one.
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("10.255.255.255", 1))
                found.append(s.getsockname()[0])
        except OSError:
            pass
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
                ip = info[4][0]
                if ip not in found and not ip.startswith("127.") and not ip.startswith("169.254."):
                    found.append(ip)
        except OSError:
            pass
        return found

    # ── pairing ────────────────────────────────────────────────────────

    def begin_pairing(self) -> str:
        """A fresh 6-digit code, shown on the PC and typed on the phone."""
        with self._lock:
            self.code = f"{secrets.randbelow(10 ** 6):06d}"
            self.code_expires = time.time() + PAIR_CODE_TTL_S
            self.attempts = PAIR_ATTEMPTS
            self._pending.clear()
        self.log("sync", "pairing code shown")
        return self.code

    def cancel_pairing(self):
        with self._lock:
            self.code = None
            self._pending.clear()

    def pairing_active(self) -> bool:
        return self.code is not None and time.time() < self.code_expires and self.attempts > 0

    def seconds_left(self) -> int:
        return max(0, int(self.code_expires - time.time())) if self.code else 0

    def _pair1(self, m: dict) -> dict:
        with self._lock:
            if not self.pairing_active():
                return {"type": "error", "message": "This PC isn't pairing right now. On the PC, "
                                                    "open Model & settings and press Pair a phone."}
            # Every attempt costs one, right or wrong: with 5 tries at a
            # 6-digit code, guessing succeeds 5 times in a million.
            self.attempts -= 1
            code = self.code
        X = int(m["X"], 16)
        if not SP.valid_element(X):
            return {"type": "error", "message": "Bad pairing message."}
        Y, Z = SP.pc_respond(code, X)
        keys = SP.derive(m["deviceId"], self.pc_id, X, Y, Z)
        self._pending[m["deviceId"]] = {**keys, "name": str(m.get("name") or "Phone")[:60],
                                        "until": time.time() + PAIR_PENDING_TTL_S}
        return {"type": "pair2", "pcId": self.pc_id, "pcName": self.pc_name,
                "Y": f"{Y:x}", "confirm": keys["confirm_pc"].hex()}

    def _pair3(self, m: dict) -> dict:
        p = self._pending.pop(m.get("deviceId", ""), None)
        if not p or time.time() > p["until"]:
            return {"type": "error", "message": "Pairing timed out. Try again."}
        if not hmac.compare_digest(bytes.fromhex(m.get("confirm", "")), p["confirm_phone"]):
            self.log("sync", "pairing failed: wrong code")
            return {"type": "error", "message": "Wrong code. Check the code on the PC and try again."}
        self.store.add_peer(m["deviceId"], p["name"], p["sync_key"])
        with self._lock:
            self.code = None            # one phone per code
        self.log("sync", f"paired with {p['name']}")
        self.on_paired(m["deviceId"], p["name"])
        return {"type": "paired"}

    # ── sync ───────────────────────────────────────────────────────────

    def _wire(self, m: Memory) -> dict:
        origin = ({"id": m.originDevice, "name": m.originName, "kind": m.originKind}
                  if m.originDevice else {"id": self.pc_id, "name": self.pc_name, "kind": "pc"})
        return {"syncId": m.syncId, "origin": origin, "timestamp": m.timestamp,
                "source": m.site or m.packageName or "", "eventType": m.eventType,
                "summary": m.summary, "sourceQuote": m.sourceQuote, "direction": m.direction,
                "dueAt": m.dueAt, "dueAtExplicit": bool(m.dueAtExplicit),
                "personName": m.personName, "category": m.category, "projectName": m.projectName,
                "commitmentDone": bool(m.commitmentDone), "updatedAt": m.updatedAt}

    def _sync(self, m: dict, address: str) -> dict:
        device = m.get("deviceId", "")
        key = self.store.peer_key(device)
        if key is None:
            return {"type": "error", "code": "unpaired",
                    "message": "This PC no longer knows this phone. Pair it again."}
        req = SP.open_(key, device, m)
        added = updated = removed = 0
        for w in req.get("rows", []):
            r = self.store.apply_remote(w, device)
            added += r == "inserted"
            updated += r == "updated"
        for t in req.get("tombstones", []):
            removed += self.store.apply_tombstone(t, device)

        cursor = int(req.get("cursor", 0))
        snapshot = self.store.max_seq()
        rows = [(r.changeSeq, "r", r) for r in self.store.changes_since(cursor, device, BATCH + 1)]
        tombs = [(t["changeSeq"], "t", t) for t in self.store.tombstones_since(cursor, device, BATCH + 1)]
        merged = sorted(rows + tombs, key=lambda x: x[0])
        more = len(merged) > BATCH
        merged = merged[:BATCH]
        new_cursor = merged[-1][0] if more else max(snapshot, merged[-1][0] if merged else 0)

        name = str(req.get("name") or "Phone")[:60]
        self.store.peer_synced(device, name, address)
        summary = f"{added} new, {updated} updated, {removed} removed from phone; {len(merged)} sent"
        if added or updated or removed or merged:
            self.log("sync", f"{name}: {summary}")
        self.on_synced(name, summary)
        reply = {"pcName": self.pc_name, "cursor": new_cursor, "more": more,
                 "accepted": added + updated + removed,
                 "rows": [self._wire(x) for _, k, x in merged if k == "r"],
                 "tombstones": [{"syncId": x["syncId"], "deletedAt": x["deletedAt"]}
                                for _, k, x in merged if k == "t"]}
        return {"type": "sync", **SP.seal(key, device, reply)}

    def _handle(self, sock: socket.socket, address: str):
        sock.settimeout(30)
        try:
            m = SP.recv_frame(sock)
            kind = m.get("type")
            if kind == "pair1":
                out = self._pair1(m)
            elif kind == "pair3":
                out = self._pair3(m)
            elif kind == "sync":
                out = self._sync(m, address)
            else:
                out = {"type": "error", "message": f"unknown request {kind!r}"}
        except Exception as e:
            self.log("sync", f"request from {address} failed: {e!r}")
            out = {"type": "error", "message": "The PC couldn't read that request."}
        try:
            SP.send_frame(sock, out)
        except OSError:
            pass
