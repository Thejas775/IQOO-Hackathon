# DailyFlow — Features

A private memory layer for Android. It reads what crosses your screen, understands it
with a language model running **on the phone**, and surfaces the promises, requests and
deadlines you would otherwise lose.

**The claim that defines the product:** nothing you capture is ever uploaded. The
profile screen shows a `0 cloud calls` counter, and it is literally true — there is no
network call anywhere in the capture or understanding path.

---

## 1. Capture — what the phone sees

| | |
|---|---|
| **Screen text** | An Accessibility service reads the on-screen text of allowed apps |
| **Notifications** | A Notification Listener catches messages that arrive and are *never opened* — the ones most likely to be forgotten, and invisible to screen capture |
| **Manual** | Type a task directly |
| **Voice** | Speak a note (see §7) |

### Filtering, in four stages
Capture is mostly about **throwing things away**. A day of real use produces thousands
of near-identical screens, and every survivor costs a model inference.

1. **Deny-list** — payment apps, launchers, keyboards, system overlays. Blocked *by
   default*, opt-in rather than opt-out: a memory app has no business reading a banking
   screen, and the first run must not capture a payment screen before anyone has chosen.
2. **Lock screen** — nothing is captured while the phone is locked.
3. **Window mismatch** — an event naming one app while a different app is actually on
   screen is dropped, so one app's content is never filed under another's name.
4. **Debounce (1.2s)** — a scroll burst collapses to the frame the screen settled on.

### Deduplication
- **Exact text** — the same screen revisited unchanged.
- **Word overlap (Jaccard ≥ 0.80)** — the same screen with a badge or ticking timestamp
  changed.
- **Summary overlap (≥ 0.55)** — after understanding, two different screens that produced
  the same meaning collapse to one memory.

---

## 2. Understanding — Gemma, on the device

**Model:** Gemma 4 E4B via LiteRT-LM, GPU backend, running entirely on the phone.

Each captured screen becomes one inference. The model returns a fixed block of fields:
summary, verbatim quote, direction, person, category, deadline, project, and whether the
screen shows an existing commitment was completed.

**One screen can produce many memories.** A WhatsApp inbox holds ten people's messages at
once; the extractor returns one block per person, each with its own deadline and category.

### Three guarantees built into the parse
- **Quotes are verified.** A quote is only shown if it genuinely appears in the captured
  text. Small models paraphrase when asked to copy, and a paraphrase in quotation marks
  is the app putting words in someone's mouth.
- **Deadlines are bounded.** A mangled year is rejected rather than scheduled.
- **The owner is never a correspondent.** A name marked "(You)" on screen is the phone's
  owner, not the person who messaged you.



## 3. Today

- **Greeting, date, and three counts** — yesterday, today, tomorrow
- **Morning brief** — Gemma synthesises the day into two or three sentences, and can read
  it aloud
- **Commitments** — open obligations, each with its verbatim quote as evidence, a due
  date, and *Draft follow-up* / *Mark done*
- **Today's timeline** — everything understood today, in a scrollable window so the people
  row stays on screen
- **People in your day** — tap through to any person's history

---

## 4. Calendar

A month grid with up to three coloured dots per day (Work / Personal / Promise). Dated
memories sit on their deadline; undated ones on the day they were captured. Tapping a day
opens a sheet that **merges captured commitments with real device calendar events**, so
the day reads as one timeline rather than two lists to reconcile.

---

## 5. Ask

A chat over your own memory, answered offline. Retrieval finds the relevant memories, and
the answer carries a citation naming the source app and date. Citations are built from the
records actually used, never asked of the model — a model-written citation could be
invented.

---

## 6. Person pages

Every person gets a page: how many moments are stored, what is still open, what has been
kept, and when you last spoke. **Commitments and Interactions** are separated — what is
owed versus what merely happened. A Gemma-written line describes where things stand.

---

## 7. Voice notes *(new)*

Speak a note; get action items. The recording goes **straight to Gemma's audio encoder**,
not through a speech recogniser — Android's default recogniser sends audio to Google,
which would make the `0 cloud calls` claim untrue. The audio never leaves the phone.

Parsed by the same code as screen captures, so a spoken note and a read message produce
identical structure. Items can be saved into your day with one tap.

---

## 8. Reminders

One notification per commitment, at its deadline. The row is **re-checked before firing** —
in the gap between scheduling and firing you may have done the thing, and reminding you
then is worse than silence. Date-only deadlines are anchored to 9 AM rather than midnight.

**Auto-tick:** when a later screen shows a commitment was completed — paid, sent,
delivered, confirmed — it ticks itself off. Merely discussing it does not count.

---

## 9. Control and privacy

- **Manage app access** — see every app that has produced captures, with counts, and
  switch any of them off
- **Pause** — stop capture entirely
- **Erase all** — remove every memory from the device
- **No account** — the app has no login and no cloud copy by default
- **Trace log** — every capture decision and extraction verdict, with its reason, readable
  on-device

---



## Deferred

- **MCP connector** — lets an assistant query your memory. Client ported; server runs
  separately.
- **Cloud sync** — optional, off by default. Summaries are **encrypted server-side** before
  storage; plaintext never lands in a plain column.
- **Proactive** — acting on memories rather than only surfacing them.
