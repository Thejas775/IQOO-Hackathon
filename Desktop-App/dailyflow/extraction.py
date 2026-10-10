"""One captured screen -> zero or more memories.

A line-for-line port of ExtractionPrompt.kt, ExtractionParser.kt, Direction.kt
and VoicePrompt.kt: same prompt, same 3000-char cap, same UTC timestamp, same
parser rules. Every rule exists because of a failure measured on the phone;
the Kotlin comments carry the evidence and are kept here in short form.

Only what Windows forces differs (marked DESKTOP):
  * the prompt says "PC" and "Windows UI Automation" instead of phone/Android;
  * an "update" capture (Level 1 dedup) holds only new lines, so the head of
    the screen it extends is given as context - inside the same 3000-char
    budget, never on top of it;
  * UI_LABELS also lists desktop chrome (WhatsApp Desktop / Gmail labels):
    same rule, the platform's own button names.

Four extra checks measured on the laptop's smaller model (Gemma 4 E2B, the
phone runs E4B) are kept but OFF unless the "extra_checks" setting is on -
see extra_checks() and PROJECT_CONTEXT.md §9.2. With it off, this file
behaves as the phone does.
"""

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from .paths import load_settings

MAX_SNIPPET_CHARS = 3000            # ExtractionPrompt.MAX_SNIPPET_CHARS
CONTEXT_CHARS = 700                 # DESKTOP: update context, taken out of the 3000
PAST_TOLERANCE_MS = 90 * 24 * 60 * 60 * 1000
FUTURE_TOLERANCE_MS = 3 * 365 * 24 * 60 * 60 * 1000


def extra_checks() -> bool:
    """The E2B-specific checks (weekday fix, advert rule, label-quote rule,
    same-pass tick-off). Off by default so behaviour matches the phone."""
    return bool(load_settings().get("extra_checks"))


# ── Direction ────────────────────────────────────────────────────────────

OBLIGATION_DIRECTIONS = ("SELF_PROMISE", "THEY_ASKED")


def parse_direction(raw: str | None) -> str:
    """Unknown or missing falls back to FROM_THEM: inventing an obligation
    the model did not assert is the worse failure."""
    v = (raw or "").strip().lower()
    if v in ("self_promise", "self promise", "promise"):
        return "SELF_PROMISE"
    if v in ("they_asked", "they asked", "asked"):
        return "THEY_ASKED"
    if v == "manual":
        return "MANUAL"
    return "FROM_THEM"


def direction_label(direction: str | None, person: str | None) -> str:
    return {
        "SELF_PROMISE": "You promised",
        "THEY_ASKED": f"{person} asked you" if person else "Asked of you",
        "MANUAL": "Added by you",
    }.get(direction or "", f"From {person}" if person else "Noted")


# ── prompt ───────────────────────────────────────────────────────────────

def iso_utc(ms: int) -> str:
    """ExtractionPrompt.isoUtcStamp."""
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _stamp_lines(label: str, ms: int) -> list[str]:
    if not extra_checks():
        return [f"{label}: {iso_utc(ms)}"]
    # EXTRA CHECK: local time, the weekday and the coming week's dates.
    # Measured 2026-10-10 (a Saturday) on E2B: from the bare UTC stamp, "by
    # Friday 5pm" came back as Saturday the 10th.
    d = datetime.fromtimestamp(ms / 1000)
    week = ", ".join(f"{x:%A} = {x:%Y-%m-%d}" for x in (d + timedelta(days=i) for i in range(1, 8)))
    return [f"{label}: {d:%Y-%m-%dT%H:%M:%S} local time, {d:%A %d %B %Y}",
            f"THE NEXT SEVEN DAYS: {week}"]


def build_prompt(source: str, captured_at: int, raw_text: str, known_people: list[str],
                 open_commitments: list[str] = (), context_text: str | None = None) -> str:
    L = []
    a = L.append
    a("You are DailyFlow, a private memory that runs entirely on one person's PC. "
      "Call that person \"you\". Below is the text visible on their screen at one "
      "moment, flattened from the Windows UI Automation tree — it has no layout, no "
      "left/right alignment and no speaker labels, only words in roughly reading order.")
    a("")
    a(f"SCREEN CAME FROM: {source}")
    L.extend(_stamp_lines("CAPTURED AT", captured_at))
    if known_people:
        # Context, not a constraint: steadies spelling of recurring names
        # without stopping the model recognising someone new.
        a(f"PEOPLE ALREADY KNOWN: {', '.join(known_people[:20])}")
    if open_commitments:
        # Numbered, not by database id: a small model echoing a six-digit id
        # gets a digit wrong often enough to tick off the wrong thing.
        a("")
        a("STILL OPEN (the owner has not finished these yet):")
        for i, s in enumerate(open_commitments):
            a(f"{i + 1}. {s}")
    a("")
    a("Decide whether this screen contains something worth remembering later.")
    a("Worth remembering: a promise either side made, a request, a decision, a bill or "
      "payment due, an order or booking with its date, a work item someone asked for, "
      "a question awaiting an answer, or a concrete plan.")
    a("NOT worth remembering: adverts and offers, promotions, donation appeals, app "
      "chrome, navigation, notification spam, someone reacting to a message, a photo "
      "or sticker with no words, and small talk.")
    a("An event with nothing in it is not a memory: \"Utsav just messaged you\" or "
      "\"message from Amma with a photo\" say only that something arrived. But an "
      "event that names what arrived is worth keeping — \"Shreya sent the "
      "DailyFlow Redesign zip\" is a memory. Record the thing, not the notification.")
    a("When genuinely unsure, prefer to record it. What you must not do is invent content "
      "for a screen that has none.")
    a("")
    a("A screen may hold SEVERAL things worth remembering, from several different people. "
      "A messages list or an inbox usually does. Give one block for EACH of them, "
      "separated by a line containing only ---, and repeat the nine lines in every "
      "block. Do not merge two people into one block.")
    a("One block per person or per item. If the screen holds only one, give one block. If "
      "it holds nothing, give one block with ISLOOP: no.")
    a("")
    a("Each block is EXACTLY these nine lines, in this order, and nothing else:")
    a("")
    a("ISLOOP: yes or no")
    a("SUMMARY: one sentence addressed to \"you\", or empty if ISLOOP is no")
    a("QUOTE: the exact sentence from the screen that this is based on, copied word for word, or empty")
    a("DIRECTION: self_promise, they_asked, or from_them")
    a("PERSON: who it came from or who asked, or empty")
    a("CATEGORY: Work, Personal, Home, or Other")
    a("DUE: yyyy-MM-ddTHH:mm:ss if there is a real deadline, or empty")
    a("PROJECT: a named ongoing effort this belongs to, or empty")
    a("COMPLETES: the number of one STILL OPEN item this screen shows is now finished, or empty")
    a("")
    a("Rules:")
    a("- QUOTE must be copied character for character from the screen text. Do not tidy, "
      "shorten or rephrase it. If no single sentence supports the summary, leave QUOTE empty.")
    a("- DIRECTION: self_promise when the owner committed to something; they_asked when "
      "someone asked the owner for something; from_them for information with no "
      "obligation attached.")
    a("- PERSON is whoever sent it or asked — a person's name, or the company or service "
      "it came from. It is never a pronoun like \"me\" or \"you\", never a button or "
      "label on the screen such as Compose, Inbox or Search, and never the PC's "
      "owner: a name marked \"(You)\" is the owner, not a correspondent.")
    a("- If the screen shows who a message or request came from, PERSON must not be empty. "
      "Leave it empty only when the source genuinely is not named.")
    a("- DUE: resolve relative wording like \"tomorrow\" or \"by Friday\" against CAPTURED "
      "AT above. If only a date is stated with no time, use 00:00:00 and the app will "
      "apply a sensible hour — never invent a time that was not said. Leave empty when "
      "there is no real deadline.")
    a("- If SUMMARY mentions a date or a deadline, DUE must contain it. \"due on Sep 2\", "
      "\"by Friday\", \"tomorrow at 8:45\" and \"within 25 days\" are all dates: "
      "resolve each against CAPTURED AT. A summary naming a deadline with an empty "
      "DUE is wrong.")
    a("- PROJECT: only when the screen names one or clearly continues one. An invented "
      "project name is worse than none.")
    a("- COMPLETES: only when this screen is direct evidence the item is finished — it was "
      "paid, sent, delivered, confirmed or explicitly called done. Someone merely "
      "discussing it, promising it again, or asking about it is not completion. Leave "
      "empty unless you are sure, and never give more than one number.")
    a("- If ISLOOP is no, leave SUMMARY, QUOTE, PERSON, DUE and PROJECT empty. COMPLETES is "
      "independent: a screen can be worth remembering or not, and still show something was finished.")
    a("")
    budget = MAX_SNIPPET_CHARS
    if context_text:
        # DESKTOP: an update record is only the lines that changed on a screen
        # read earlier. Alone, "Sure, by 5" has no sender and no chat; the
        # head of the earlier screen carries both. Marked as already read so
        # the model does not extract it again, and paid for out of the same
        # 3000 chars so a prompt is never larger than the phone's.
        ctx = context_text[:CONTEXT_CHARS]
        budget -= len(ctx)
        a("EARLIER ON THE SAME SCREEN (already read — use only to tell who is talking "
          "and where; do not make blocks from it):")
        a(ctx)
        a("")
        a("NEW ON THE SCREEN SINCE THEN:")
    else:
        a("SCREEN TEXT:")
    L.append(raw_text[:budget])
    return "\n".join(L)


def source_text(raw_text: str, context_text: str | None) -> str:
    """What the quote and owner checks run against. Android passes the row's
    whole rawText (ExtractionRunner: parseAll(reply, row.rawText)); for a
    DESKTOP update, the context it was shown with is added, so a "(You)"
    marker or a quoted line from the context is recognised."""
    if not context_text:
        return raw_text
    return context_text[:CONTEXT_CHARS] + "\n" + raw_text


def parse_due(raw: str | None) -> int | None:
    """ExtractionPrompt.parseDue: yyyy-MM-ddTHH:mm:ss in local time, bounded."""
    v = (raw or "").strip().strip('"')
    if not v:
        return None
    try:
        # Local on purpose, as on Android. Like SimpleDateFormat.parse, any
        # trailing text after the seconds ("Z", a zone) is ignored.
        dt = datetime.strptime(v[:19], "%Y-%m-%dT%H:%M:%S")
        ms = int(dt.timestamp() * 1000)
    except (ValueError, OverflowError, OSError):
        return None
    # "05-09-205" parses into the year 205; a reminder for it would fire
    # instantly and look like a bug in the reminder.
    now = int(datetime.now().timestamp() * 1000)
    if ms < now - PAST_TOLERANCE_MS or ms > now + FUTURE_TOLERANCE_MS:
        return None
    return ms


# ── parser ───────────────────────────────────────────────────────────────

@dataclass
class Extraction:
    is_memory: bool
    summary: str | None = None
    source_quote: str | None = None
    direction: str = "FROM_THEM"
    person: str | None = None
    category: str | None = None
    due_at: int | None = None
    due_at_explicit: bool = False
    project: str | None = None
    completes_index: int | None = None


NULLISH = {"", "-", "none", "n/a", "na", "null", "nil", "empty", "unknown",
           "not specified", "not applicable", "no", "nothing"}
CATEGORIES = {"Work", "Personal", "Home", "Other"}
KNOWN_KEYS = {"ISLOOP", "SUMMARY", "QUOTE", "DIRECTION", "PERSON", "CATEGORY", "DUE",
              "PROJECT", "COMPLETES"}
BLOCK_SEPARATOR = re.compile(r"(?m)^\s*-{3,}\s*$")
WHITESPACE = re.compile(r"\s+")

OWNER_MARKERS = ["(you)", "(owner)", "· you", "- you"]
NOT_A_PERSON = {"me", "you", "yourself", "myself", "my self", "self", "i", "us", "we",
                "them", "they", "sender", "recipient", "user", "owner", "the owner",
                "the sender", "unknown sender", "no one", "nobody", "someone", "anyone"}
UI_LABELS = {"compose", "inbox", "search", "notification", "notifications", "mail",
             "email", "message", "messages", "chat", "chats", "group", "groups",
             "settings", "menu", "home", "back", "more options", "navigation",
             "status", "updates", "calls", "community", "channel", "channels",
             # DESKTOP: the same rule's desktop chrome - WhatsApp Desktop and
             # Gmail labels seen in real captures. ("Google" left out: it is a
             # real sender of security mail.)
             "whatsapp", "gmail", "starred", "snoozed", "sent", "drafts",
             "archive", "spam", "trash", "meet", "calendar", "new chat", "filter",
             "type a message", "search or start a new chat", "open chat details",
             "attach", "emoji", "voice message", "reply", "forward"}

# EXTRA CHECK labels (advert chrome), only consulted when extra_checks() is on.
AD_LABELS = {"sponsored", "promotions", "ad"}
AD_SUMMARY = re.compile(r"\b(advertis\w*|an ad|promotional|sponsored)\b", re.IGNORECASE)


def _clean(v: str | None) -> str | None:
    if v is None:
        return None
    v = v.strip().strip("\"'`").strip()
    return None if v.lower() in NULLISH else v


def _norm(s: str) -> str:
    return WHITESPACE.sub(" ", s.lower()).strip()


def parse_all(raw: str | None, source_text: str, captured_at: int | None = None) -> list[Extraction]:
    """ExtractionParser.parseAll: every block, not just the first. The first
    result is always returned, even empty: the capture row needs a verdict."""
    if not raw or not raw.strip():
        return [Extraction(False)]
    blocks = [b.strip() for b in BLOCK_SEPARATOR.split(raw) if b.strip()]
    parsed = [parse(b, source_text, captured_at) for b in blocks]
    memories = [p for p in parsed if p.is_memory or p.completes_index is not None]
    seen, unique = set(), []
    for m in memories:
        k = (m.summary or "").lower()
        if k not in seen:
            seen.add(k)
            unique.append(m)
    return unique or [parsed[0] if parsed else Extraction(False)]


def parse(raw: str | None, source_text: str, captured_at: int | None = None) -> Extraction:
    if not raw or not raw.strip():
        return Extraction(False)
    f: dict[str, str] = {}
    for line in raw.splitlines():
        # Markdown emphasis and bullets stripped; split on the first colon
        # only, since values contain colons ("meet at 3:00").
        cleaned = line.strip().removeprefix("-").removeprefix("*").strip().replace("**", "")
        idx = cleaned.find(":")
        if idx <= 0:
            continue
        key = cleaned[:idx].strip().upper()
        if key not in KNOWN_KEYS:
            continue
        f[key] = cleaned[idx + 1:].strip().strip("\"'`")

    summary = _clean(f.get("SUMMARY"))
    comp = _clean(f.get("COMPLETES"))
    digits = "".join(ch for ch in comp or "" if ch.isdigit())
    completes = int(digits) if digits and int(digits) > 0 else None

    # isMemory follows from a summary being written, not from ISLOOP: small
    # models write a good summary and still answer "no" on the flag.
    if summary is None:
        return Extraction(False, completes_index=completes)

    if extra_checks():
        # EXTRA CHECK (E2B, 2026-10-10): the model calling its own block an
        # advert ("You are seeing an advertisement for a Diwali sale"), and a
        # block resting only on a screen label ("Type a message" -> "You need
        # to type a message").
        quote_raw = _clean(f.get("QUOTE"))
        if AD_SUMMARY.search(summary):
            return Extraction(False, completes_index=completes)
        if quote_raw and _norm(quote_raw).strip(".!?:… ") in (UI_LABELS | AD_LABELS):
            return Extraction(False, completes_index=completes)

    due = parse_due(_clean(f.get("DUE")))
    if due is not None and captured_at is not None and extra_checks():
        due = _match_named_weekday(due, f"{summary} {_clean(f.get('QUOTE')) or ''}", captured_at)
    # Midnight means "no time given"; captured before the 9 AM anchor erases it.
    stated_time = due is not None and not _is_midnight(due)
    cat = _clean(f.get("CATEGORY"))
    cat = cat[:1].upper() + cat[1:] if cat else None
    person = _clean(f.get("PERSON"))
    project = _clean(f.get("PROJECT"))
    return Extraction(
        is_memory=True,
        summary=summary,
        source_quote=_verify_quote(_clean(f.get("QUOTE")), source_text),
        direction=parse_direction(_clean(f.get("DIRECTION"))),
        person=_reject_owner(person[:40] if person else None, source_text),
        category=cat if cat in CATEGORIES else None,
        due_at=_nine_am_if_midnight(due) if due is not None else None,
        due_at_explicit=stated_time,
        project=project[:60] if project else None,
        completes_index=completes,
    )


def _reject_owner(person: str | None, source_text: str) -> str | None:
    """Drops "me"/"you", screen furniture, and the owner - detected from the
    "(You)" marker on the screen, since the app is never told who owns it."""
    if person is None:
        return None
    needle = _norm(person)
    if not needle:
        return None
    if needle in NOT_A_PERSON or needle in UI_LABELS:
        return None
    if extra_checks() and needle in AD_LABELS:
        return None
    if any(f"{needle} {m}" in _norm(source_text) for m in OWNER_MARKERS):
        return None
    return person


def _verify_quote(quote: str | None, source_text: str) -> str | None:
    """Kept only if it really appears in the captured text: a paraphrase in
    quotation marks is the app putting words in someone's mouth."""
    if quote is None:
        return None
    cand = quote.strip().strip('"“”')
    if len(cand) < 8:
        return None
    needle = _norm(cand)
    return cand if needle and needle in _norm(source_text) else None


WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
WEEKDAY_WORD = re.compile(r"\b(" + "|".join(WEEKDAYS) + r")\b", re.IGNORECASE)


def _match_named_weekday(due: int, text: str, captured_at: int) -> int:
    """EXTRA CHECK: puts DUE on the weekday the text names, when it disagrees.

    Measured 2026-10-10 (a Saturday) on E2B: "by Friday 5pm" came back as
    Saturday the 17th even with the coming week's dates listed in the
    prompt. Only acts when exactly one weekday is named and DUE falls within
    the coming week.
    """
    named = {m.lower() for m in WEEKDAY_WORD.findall(text)}
    if len(named) != 1:
        return due
    target = WEEKDAYS.index(named.pop())
    d = datetime.fromtimestamp(due / 1000)
    cap = datetime.fromtimestamp(captured_at / 1000)
    if d.weekday() == target or not (-1 <= (d.date() - cap.date()).days <= 8):
        return due
    ahead = (target - cap.weekday()) % 7
    fixed = datetime.combine(cap.date() + timedelta(days=ahead), d.time())
    return int(fixed.timestamp() * 1000)


def _is_midnight(ms: int) -> bool:
    d = datetime.fromtimestamp(ms / 1000)
    return d.hour == 0 and d.minute == 0 and d.second == 0


def _nine_am_if_midnight(ms: int) -> int:
    """A date with no time is anchored to 9 AM local, so it never rings at
    midnight. dueAtExplicit records that the hour was not said."""
    if not _is_midnight(ms):
        return ms
    d = datetime.fromtimestamp(ms / 1000).replace(hour=9)
    return int(d.timestamp() * 1000)


# ── voice (VoicePrompt.kt) ───────────────────────────────────────────────

def build_voice_prompt(now_ms: int) -> str:
    """Same block format as screens so one parser serves both; but a note the
    owner chose to record defaults to worth keeping."""
    return "\n".join([
        "You are DailyFlow, a private memory that runs entirely on one person's PC. "
        "The audio is a note the owner recorded for themselves. Call them \"you\".",
        "",
        *_stamp_lines("RECORDED AT", now_ms),
        "",
        "Listen to it and pull out every distinct thing they need to do, remember, or "
        "follow up on. They chose to record this, so assume it matters — do not "
        "discard something merely because it sounds small.",
        "Give one block for EACH item, separated by a line containing only ---. If the "
        "note contains nothing to act on, give one block with ISLOOP: no.",
        "",
        "Each block is EXACTLY these lines, in this order, and nothing else:",
        "",
        "ISLOOP: yes or no",
        "SUMMARY: one sentence addressed to \"you\", or empty if ISLOOP is no",
        "QUOTE: empty",
        "DIRECTION: self_promise, they_asked, or from_them",
        "PERSON: the person it involves, or empty",
        "CATEGORY: Work, Personal, Home, or Other",
        "DUE: yyyy-MM-ddTHH:mm:ss if a time was said, or empty",
        "PROJECT: a named ongoing effort, or empty",
        "COMPLETES: empty",
        "",
        "Rules:",
        "- Leave QUOTE empty. There is no written source to copy from, and an invented "
        "quotation is worse than none.",
        "- DIRECTION: self_promise when they said they would do something; they_asked when "
        "they are recording that someone asked them; from_them for anything else.",
        "- DUE: resolve \"tomorrow\", \"Friday\" or \"in an hour\" against RECORDED AT. If "
        "only a day was said, use 00:00:00 and the app applies a sensible hour. Never "
        "invent a time that was not spoken.",
        "- One block per thing. \"Call the bank and email Priya\" is two items, not one.",
        "- Write what they meant, not what they said. Filler, false starts and repetition "
        "should not appear in the summary.",
        "",
        "The blocks:",
    ])
