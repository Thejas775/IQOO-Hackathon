"""Phone <-> PC sync protocol, v1. The Android side
(Mobile-App/.../sync/SyncProtocol.kt, PcPairing.kt) implements the same
bytes; change one, change both.

Same Wi-Fi only, no server: the PC listens, the phone connects.

Discovery  UDP 47812. Phone broadcasts b"DAILYFLOW_DISCOVER_1"; every PC
           running DailyFlow replies with JSON
           {"app":"dailyflow","v":1,"pcId","name","port":47811,"pairing":bool}.
Transport  TCP 47811. Frames are a 4-byte big-endian length then that many
           bytes of UTF-8 JSON. One request frame, one reply frame, close.

Pairing (SPAKE2 over the RFC 3526 2048-bit MODP group, g = 2)
  The PC shows a 6-digit code for 5 minutes, 5 attempts. The code never
  crosses the network. Someone on the same Wi-Fi who records the exchange
  learns nothing they can brute-force offline; an active attacker gets one
  guess per attempt. (A plain "send the code, get a key" exchange would hand
  a listener the key; an HMAC-of-the-code exchange lets a man in the middle
  try all 10^6 codes offline in milliseconds.)

  p  = MODP-2048 prime, q = (p-1)/2, g = 2
  M  = int(SHA256("DailyFlow SPAKE2 M v1"))^2 mod p      (dlog unknown)
  N  = int(SHA256("DailyFlow SPAKE2 N v1"))^2 mod p
  w  = int(SHA256("DailyFlow pairing code v1|" + code)) mod q
  phone: x random in [1, q)   X = g^x * M^w mod p
  PC:    y random in [1, q)   Y = g^y * N^w mod p
  PC:    Z = (X * (M^w)^-1)^y mod p      phone: Z = (Y * (N^w)^-1)^x mod p
  K  = SHA256("DailyFlow pair v1" || lp(phoneId) || lp(pcId) || I(X) || I(Y) || I(Z))
       lp(s) = 4-byte big-endian length + UTF-8;  I(n) = 256-byte big-endian
  confirmPc    = HMAC-SHA256(K, "pc confirm")
  confirmPhone = HMAC-SHA256(K, "phone confirm")
  syncKey      = HMAC-SHA256(K, "sync key")          (32 bytes, AES-256)

  1. phone -> PC  {"type":"pair1","deviceId","name","X":hex}
  2. PC -> phone  {"type":"pair2","pcId","pcName","Y":hex,"confirm":hex}
                  or {"type":"error","message"}
  3. phone -> PC  {"type":"pair3","deviceId","confirm":hex}   (new connection)
  4. PC -> phone  {"type":"paired"} or {"type":"error","message"}
  The phone checks confirmPc before sending its own, so a wrong code is
  caught on the phone too.

Sync (encrypted with AES-256-GCM, 12-byte random nonce, AAD = deviceId)
  phone -> PC  {"type":"sync","deviceId","nonce":b64,"ct":b64}
      plaintext {"name","cursor":int,"rows":[...],"tombstones":[...]}
  PC -> phone  {"type":"sync","nonce":b64,"ct":b64}
      plaintext {"pcName","rows":[...],"tombstones":[...],"cursor":int,"more":bool,
                 "accepted":int}
  "cursor" from the phone = the highest PC change number it has applied; the
  PC answers with its changes after that (not ones the phone itself made) and
  its new cursor. The phone repeats while either side still has more.

  Row:  {"syncId","origin":{"id","name","kind"},"timestamp","source","eventType",
         "summary","sourceQuote","direction","dueAt","dueAtExplicit","personName",
         "category","projectName","commitmentDone","updatedAt"}
        kind is "pc" or "phone"; source is the app (exe / package) or site.
        Raw screen text is never sent (user decision, 2026-10-10).
  Tombstone: {"syncId","deletedAt"}
  Conflicts: the newer updatedAt wins; a deletion wins over older edits.
"""

import base64
import hashlib
import hmac
import json
import os
import secrets
import socket
import struct

DISCOVERY_PORT = 47812
SYNC_PORT = 47811
DISCOVER_MAGIC = b"DAILYFLOW_DISCOVER_1"
MAX_FRAME = 16 * 1024 * 1024

P = int(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
    "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
    "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16)
Q = (P - 1) // 2
G = 2
ELEMENT_BYTES = 256


def _h(*parts: bytes) -> bytes:
    d = hashlib.sha256()
    for part in parts:
        d.update(part)
    return d.digest()


def _int(b: bytes) -> int:
    return int.from_bytes(b, "big")


M = pow(_int(_h(b"DailyFlow SPAKE2 M v1")), 2, P)
N = pow(_int(_h(b"DailyFlow SPAKE2 N v1")), 2, P)


def code_scalar(code: str) -> int:
    return _int(_h(("DailyFlow pairing code v1|" + code).encode())) % Q


def element_bytes(n: int) -> bytes:
    return n.to_bytes(ELEMENT_BYTES, "big")


def _lp(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">I", len(b)) + b


def valid_element(n: int) -> bool:
    return 1 < n < P - 1


def new_secret() -> int:
    return secrets.randbelow(Q - 1) + 1


def phone_start(code: str) -> tuple[int, int]:
    """(x, X) - used by the test phone; the real phone is PcPairing.kt."""
    x = new_secret()
    return x, pow(G, x, P) * pow(M, code_scalar(code), P) % P


def pc_respond(code: str, X: int) -> tuple[int, int]:
    """(Y, Z) for the PC."""
    w = code_scalar(code)
    y = new_secret()
    Y = pow(G, y, P) * pow(N, w, P) % P
    Z = pow(X * pow(pow(M, w, P), -1, P) % P, y, P)
    return Y, Z


def phone_finish(code: str, x: int, Y: int) -> int:
    w = code_scalar(code)
    return pow(Y * pow(pow(N, w, P), -1, P) % P, x, P)


def derive(phone_id: str, pc_id: str, X: int, Y: int, Z: int) -> dict:
    k = _h(b"DailyFlow pair v1", _lp(phone_id), _lp(pc_id),
           element_bytes(X), element_bytes(Y), element_bytes(Z))
    mac = lambda label: hmac.new(k, label, hashlib.sha256).digest()
    return {"confirm_pc": mac(b"pc confirm"), "confirm_phone": mac(b"phone confirm"),
            "sync_key": mac(b"sync key")}


# ── encryption ───────────────────────────────────────────────────────────

def seal(key: bytes, aad: str, obj: dict) -> dict:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    nonce = os.urandom(12)
    ct = AESGCM(key).encrypt(nonce, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                             aad.encode("utf-8"))
    return {"nonce": base64.b64encode(nonce).decode(), "ct": base64.b64encode(ct).decode()}


def open_(key: bytes, aad: str, msg: dict) -> dict:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    pt = AESGCM(key).decrypt(base64.b64decode(msg["nonce"]), base64.b64decode(msg["ct"]),
                             aad.encode("utf-8"))
    return json.loads(pt.decode("utf-8"))


# ── framing ──────────────────────────────────────────────────────────────

def send_frame(sock: socket.socket, obj: dict):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    sock.sendall(struct.pack(">I", len(data)) + data)


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed mid-frame")
        buf += chunk
    return bytes(buf)


def recv_frame(sock: socket.socket) -> dict:
    (n,) = struct.unpack(">I", _recv_exact(sock, 4))
    if n > MAX_FRAME:
        raise ValueError(f"frame too large ({n} bytes)")
    return json.loads(_recv_exact(sock, n).decode("utf-8"))


def request(host: str, obj: dict, port: int = SYNC_PORT, timeout: float = 20) -> dict:
    with socket.create_connection((host, port), timeout=timeout) as s:
        send_frame(s, obj)
        return recv_frame(s)
