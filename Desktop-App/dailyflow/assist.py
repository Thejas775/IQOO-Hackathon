"""Morning brief, relationship line, draft follow-up, and Ask.

Ports BriefGenerator.kt, AskPrompt.kt and MemorySearch.kt. All of them write
only from memories already understood, and are told to say so rather than
guess: on a memory app a confident wrong answer is worse than a blank one.

MemorySearch's vector path is not ported: it only ran with a sideloaded
retrieval embedder, and the bundled MobileBERT could not rank (0.9975 on
unrelated summaries). The keyword path is the one that actually answered.
"""

import re
from datetime import datetime

from .store import Memory, Store
from .pipeline import source_label

TOP_K = 8
RECENT_FALLBACK = 4
STOPWORDS = {"the", "a", "an", "is", "are", "was", "were", "do", "did", "does", "i",
             "me", "my", "you", "your", "what", "when", "who", "whom", "which",
             "and", "or", "to", "for", "of", "in", "on", "at", "it", "that", "this",
             "any", "have", "has", "had", "about", "with", "still", "there"}


def relative_short(ms: int | None) -> str:
    """DateLabels.relativeShort: 'today', '3d', '2w'."""
    if not ms:
        return "—"
    a = datetime.fromtimestamp(ms / 1000).date()
    days = (datetime.now().date() - a).days
    if days <= 0:
        return "today"
    if days < 7:
        return f"{days}d"
    if days < 30:
        return f"{days // 7}w"
    if days < 365:
        return f"{days // 30}mo"
    return f"{days // 365}y"


def relative_time(ms: int | None) -> str:
    if not ms:
        return "never"
    secs = max(0, (now_ms_() - ms) // 1000)
    if secs < 60:
        return "just now"
    if secs < 3600:
        return f"{secs // 60} min ago"
    if secs < 86400:
        return f"{secs // 3600} h ago"
    return f"{secs // 86400} d ago"


def now_ms_() -> int:
    import time
    return int(time.time() * 1000)


def _trim_to_summary(raw: str) -> str:
    cleaned = raw.strip().strip('"').strip()
    parts = re.split(r"(?<=[.!?])\s+", cleaned)
    return " ".join(parts[:3]).strip()


def morning_brief(engine, todays: list[Memory]) -> str | None:
    if not todays:
        return None
    lines = [
        "You are DailyFlow, a private memory on one person's PC. Below is everything "
        "from their day. Write a short summary of the day, addressed to \"you\".",
        "",
        "Rules:",
        "- Use only what is listed. Never invent a person, task or deadline.",
        "- Two or three sentences. Cover the day as a whole.",
        "- Mention what is still open, but do not make the whole summary about it.",
        "- No greeting, no preamble, no list. Just the summary.",
        "- Plain and specific. Name the person or the thing rather than saying \"a few items\".",
        "",
        "TODAY:",
    ]
    for m in todays[:15]:
        state = "done" if m.commitmentDone else "open"
        who = f" (with {m.personName})" if m.personName else ""
        lines.append(f"- [{state}]{who} {m.summary}")
    lines += ["", "The summary:"]
    out = engine.complete("\n".join(lines))
    return _trim_to_summary(out) if out else None


def relationship_line(engine, person: str, memories: list[Memory]) -> str | None:
    if not memories:
        return None
    lines = [
        "You are DailyFlow, a private memory on one person's PC. Below is everything "
        f"the owner's PC has recorded involving {person}. Write ONE sentence, "
        "addressed to \"you\", describing where things stand between them.",
        "",
        "Rules:",
        "- Use only what is listed. Never invent a conversation or a date.",
        "- Say what is outstanding, or that nothing is.",
        "- No greeting, no preamble, no list. Just the sentence.",
        "",
        f"WITH {person}:",
    ]
    for m in memories[:12]:
        lines.append(f"- [{'done' if m.commitmentDone else 'open'}] {m.summary}")
    lines += ["", "The sentence:"]
    out = engine.complete("\n".join(lines))
    return _trim_to_summary(out) if out else None


def draft_follow_up(engine, m: Memory) -> str | None:
    """Shown for copying, never sent. The gap between drafted and sent is
    the whole safety margin."""
    if not m.summary:
        return None
    lines = ["Write a short, friendly message the PC's owner can send about this "
             "outstanding commitment.", "", f"COMMITMENT: {m.summary}"]
    if m.personName:
        lines.append(f"TO: {m.personName}")
    if m.sourceQuote:
        lines.append(f"ORIGINALLY SAID: \"{m.sourceQuote}\"")
    lines += ["", "Rules:",
              "- Two sentences at most. Write it as the owner, ready to send.",
              "- Warm and direct. No apologising at length, no corporate phrasing.",
              "- Do not invent a new date or promise anything that isn't above.",
              "- Output only the message itself, with no quotes or labels around it.",
              "", "The message:"]
    out = engine.complete("\n".join(lines))
    return out.strip().strip('"') if out else None


# ── Ask ──────────────────────────────────────────────────────────────────

def _tokenise(text: str) -> set[str]:
    return {t for t in re.split(r"[^\w]+|_", text.lower()) if len(t) > 2 and t not in STOPWORDS}


def _matches(term: str, hay: set[str]) -> bool:
    """Prefix match either way, for words of 4+ chars: 'asked' finds 'ask',
    'promise' finds 'promised' - questions are in a different tense."""
    if term in hay:
        return True
    if len(term) < 4:
        return False
    return any(len(h) >= 4 and (h.startswith(term) or term.startswith(h)) for h in hay)


def search(store: Store, question: str) -> list[tuple[Memory, float]]:
    candidates = store.retrieval_candidates()
    if not candidates:
        return []
    terms = _tokenise(question)
    ranked = []
    if terms:
        for m in candidates:
            hay = _tokenise(" ".join(x for x in (m.summary, m.personName, m.sourceQuote) if x))
            score = sum(1 for t in terms if _matches(t, hay)) / len(terms)
            if score > 0:
                ranked.append((m, score))
        ranked.sort(key=lambda h: -h[1])
        ranked = ranked[:TOP_K]
    # Nothing matched: recent memories as context, scored 0 so they are not
    # cited as sources.
    return ranked or [(m, 0.0) for m in candidates[:RECENT_FALLBACK]]


def ask_prompt(question: str, hits: list[tuple[Memory, float]]) -> str:
    matched = any(s > 0 for _, s in hits)
    L = ["You are DailyFlow, a private memory running on one person's PC. Answer their "
         "question using only the memories listed below. Address them as \"you\".",
         "", "Rules:",
         "- Use only what the memories say. Do not add facts, names or dates that are not there.",
         "- If the memories do not answer the question, say so plainly in one sentence. Do not "
         "guess, and do not offer a maybe.",
         "- Answer in one to three sentences. No lists, no headings, no preamble.",
         "- Refer to people by the names used in the memories."]
    if not matched and hits:
        L.append("- If they are greeting you or making small talk rather than asking about their "
                 "day, reply warmly in one sentence and offer to tell them what is open.")
    L.append("")
    if not hits:
        L.append("MEMORIES: nothing has been read from this PC yet.")
    else:
        L.append("MEMORIES:" if matched else "MEMORIES (recent, may be unrelated):")
        for i, (m, _) in enumerate(hits):
            who = f", with {m.personName}" if m.personName else ""
            L.append(f"{i + 1}. [{relative_short(m.timestamp)}{who}] {m.summary}")
            if m.sourceQuote:
                L.append(f"   exact words: \"{m.sourceQuote}\"")
    L += ["", f"QUESTION: {question}"]
    return "\n".join(L)


def citation(hits: list[tuple[Memory, float]]) -> str | None:
    """Built from the rows actually used, never asked of the model."""
    used = [m for m, s in hits if s > 0]
    if not used:
        return None
    apps = []
    for m in used:
        label = source_label(m)
        if label not in apps:
            apps.append(label)
    newest = max(m.timestamp for m in used)
    return f"Source · {', '.join(apps[:3])}, {relative_short(newest)}"
