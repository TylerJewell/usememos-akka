# usememos-akka

Decides what a captured note may contain, finds its tags inside its own text, and decides
who is allowed to read it.

A port of [usememos/memos](https://github.com/usememos/memos) onto **Akka**, built with
**Akka Specify**.

---

## Where it came from

usememos/memos is a personal note-taking application: someone writes a short note in
Markdown, the application finds hashtag-style tags inside what they wrote, and the note is
shown to whoever the writer allowed to see it. It was ported to derive a specification
format precise enough to regenerate a system on a different stack — the port is the
vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `usememos-port/`.

---

## usememos/memos → this port

📉 304 Go lines → **223 Java lines**<br>
📁 5 files → **2 files**<br>
🖥️ 2 processes → **1 process**<br>
🎯 matching answers 27 of 27 → **27 of 27**<br>
⚡ 20,558 → **812** nanoseconds to find a note's tags

Full method and the numbers that did *not* make this list:
[`../usememos-port/bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/usememos-port/bench/REPORT.md).

---

## What it took to build

⏱️ **1.8 hours** from the first command to the published repository, **1.8** of them active<br>
💬 **732** exchanges with the model<br>
✍️ **439,381** tokens written by the model, **229,882,616** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **30** tests

```bash
python toolkit/tokens.py --port usememos    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

- **A note's tags come only from its own text, never from what a caller says they are.**
  Writing `#travel/japan` inside a note is what puts it in that note's tag set; sending a
  tag list alongside the note does nothing.
- **A tag introduced inside a hierarchy is also its own ancestors.** `#travel/japan` gives a
  note three tags at once — `travel`, `travel/japan`, and nothing beyond what was written.
- **A hashtag inside a code block or a link is not a tag.** `` `#travel` `` and
  `[travel](url)` never produce one.
- **Two tags spelled with different letter case are two different tags.** `#Travel` and
  `#travel` do not merge.
- **A new note is private to whoever wrote it unless they say otherwise.**
- **A note can be shown to nobody but its writer, to anyone signed in, to anyone at all, or
  to the members of one named group** — four separate answers, chosen once per note.
- **A note that has been put away is shown to nobody but the person who wrote it**,
  whatever audience it was otherwise set to reach.
- **Only a note's writer may change what it says, who may read it, or whether it is put
  away.** Nobody else, however the note is currently shared.
- **The same question — may this person read this note — is answered the same way whether
  one note or a whole list of them is being shown.** There is exactly one place in this
  port that answers it.

---

## Design decisions

**One decision, used everywhere a note might be read.** usememos answers "may this person
read this note" with two separate pieces of code — one for showing a single note, another
for showing a list of them — and its own comments describe guards added after the two had
already disagreed once. This port answers it in exactly one place, called from both paths,
so the two cannot drift apart the way two separate answers can.

**Tags are read out of the words, not stored as a separate list.** A note's text is the one
place its tags live; asking for them re-reads the text instead of trusting a copy that
could go stale the moment the text changes.

**Every field a note might not have is spelled out as absent, not left blank.** A row
holding no group tried to leave that value out entirely and the storage underneath refused
it outright — the missing case has to be named, not skipped, or the whole row is rejected.

**A narrower alphabet for tags than the original's.** The original recognises accented
letters, joined words like `don't`, and pictures used as tags, built from very large lookup
tables of what counts as a letter. This port recognises ordinary letters, digits, and a
short list of connecting marks, and leaves the picture-tag and accent-table work undone —
see "Where it differs", below.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/usememos-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9076/ui/memos?as=alice.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9076**.

### Try it

```bash
# Write a note. Nothing is passed for who may read it, so only alice can.
curl -X POST localhost:9076/memos -H 'X-User-Id: alice' -H 'Content-Type: application/json' \
  -d '{"content":"packing list for #travel/japan"}'

# Read it back as alice
curl localhost:9076/memos/<memoId> -H 'X-User-Id: alice'

# Somebody else is turned away
curl -i localhost:9076/memos/<memoId> -H 'X-User-Id: bob'

# A plain page showing everything alice may read
open http://localhost:9076/ui/memos?as=alice
```

---

## Configuration

Everything that is not a model provider — and this service calls no model provider at all.

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9076` | set in `application.conf`, not an environment variable |

Who is asking is a plain `X-User-Id` header on every request; there is no sign-in flow in
this port (see "Where it differs", below).

---

## Where it differs from usememos

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **Tags recognise ordinary letters, digits, and a short list of connecting marks — not the
  original's full alphabet.** usememos recognises accented and non-Latin letters, joined
  words such as `don't`, and pictures used as tags, all read from very large lookup tables
  pinned to a specific version of those tables. This port recognises the structure of the
  rule — where a tag starts, where a hierarchy splits, which punctuation ends one — over a
  smaller alphabet, because reproducing those lookup tables by hand is disproportionate to
  what this port set out to show. Every worked example this port's own tests check was run
  against the original first; every example that depends on the larger alphabet was not
  ported, and is not claimed to work.
- **Sharing a note by a separate link is not here.** The original lets a note's writer hand
  out a link that opens one note regardless of who else may read it. This port has no such
  link, and its read-decision has no path around the ordinary rule the way the original's
  does — not a smaller version of the same feature, a decision not to build it at all.
  Because of that, this port's private notes have no back door the original's do.
  `not measured` beyond what the benchmark's twenty-seven cases cover.
- **A workspace-wide switch exists for one purpose only: whether a visitor who has not
  signed in may read a note left open to anyone.** It has no effect on what a writer is
  allowed to choose, and none on a signed-in reader, on either side. This port's own copy
  of that switch does exactly the same and nothing more.
- **A note's group membership is a plain yes-or-no per person.** The original gives a group
  roles — owner, administrator, ordinary member — and a separate ability for someone to
  keep moving a note they belong to even after they have otherwise lost access to the
  group. This port asks only "does this person currently belong," with no such exception
  for someone who no longer does.
- **The screen is this port's own, not the original's.** A person watching the original's
  screen sees a note's audience and pinned state change in front of them — a small icon for
  each — which is exactly the state this port's rules govern, so a screen belongs in this
  port too. What is here is a plain page showing the same facts in plain words, not a copy
  of the original's design.

---

## Licence

usememos/memos is MIT licensed, © 2025 Memos. This port reimplements its behaviour; one
short error message and two conformance examples are carried over unchanged. See
[`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
