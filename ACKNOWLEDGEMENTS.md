# Acknowledgements

This project is a port of **[usememos/memos](https://github.com/usememos/memos)**.

## Licence

`usememos/memos` is MIT licensed, copyright (c) 2025 Memos (read directly from its
`LICENSE` file at clone time, not assumed from the repository badge). MIT permits reuse
with attribution; this port carries that same MIT licence.

## What was copied, checked by running `toolkit/copied_strings.py usememos --source
usememos-src` rather than by memory

**Copied verbatim, on purpose:**

- `#book/fiction/history` and its expected tag set (`book`, `book/fiction`,
  `book/fiction/history`) in `TagExtractorTest.hierarchyExpandsToEveryAncestorLevel` is
  the source's own canonical worked example for hierarchy expansion —
  `docs/adr/0001-tag-syntax-and-recognition.md:430-432` and
  `internal/markdown/markdown_test.go:680` both use the identical string. Reused as a
  conformance vector deliberately, the same reason a spec's own worked examples are
  reused rather than reinvented.
- `[release #notes](https://example.com/releases#notes)` in
  `TagExtractorTest.markdownLinkIsExcludedLabelAndDestination` is ADR 0001's own
  conformance-table example for the link-exclusion rule (the ADR's table, "The complete
  link is excluded"). Same reason as above.
- The error message `"SPACE visibility requires a space"` in `MemoEntity.java` is
  copied verbatim from `server/router/api/v1/memo_create_helpers.go:56` and
  `server/router/api/v1/memo_service.go:376`. It was read while establishing
  question-log row 17 and carried over unchanged.

**Derived, not verbatim:** `MemoEntity`'s content-length error message,
`"content too long (max " + LIMIT + " bytes)"`, shares its prefix with the source's
`"content too long (max %d characters)"` (`memo_create_helpers.go:70`,
`memo_service.go:408`) but deliberately changes the unit word from `characters` to
`bytes` — SPEC-001 OD-3 records why: the source measures Go `len(string)`, which counts
UTF-8 bytes, while its own message claims characters. This port's message says what its
own check actually measures.

**Checked and not copied — coincidental phrasing**, the same natural English a second
implementation of a similar rule reaches for independently:

- `"Memo not found"` — no such literal string exists anywhere in `usememos-src`;
  the source returns a structured gRPC `NotFound` status with no fixed message text.
- `"Only the creator"` — the source's own comment "Only the creator can update the
  attachment" (`server/router/api/v1/attachment_service.go:308`) describes a *different*
  resource's rule (attachments, not memos); the overlap is the shared English phrase for
  a creator-only check, not a copied sentence.
- The event type names `"memo-created"` / `"memo-archived"` — `usememos-src` never uses
  these as identifiers; they surface only as substrings inside unrelated test fixture
  UIDs (`memo-archived-old`, `memo-archived-recent`, `memo-archived-personal` in
  `store/test/memo_filter_test.go`), an artifact of "memo" + an English past participle
  being an ordinary event-naming vocabulary, not a shared name.
- `"visibility"`, `"characters"` — single common English words, hit in every port that
  discusses either concept.
- `'{"error":"'` — a generic JSON error-wrapper shape this port's own
  `MemoEndpoint.jsonError` builds; not a string the source's own error responses use
  (the source returns structured gRPC-gateway JSON, not this shape).

**No fixtures, schemas, prompts, or test corpora were copied.** The 27-case
`bench/workloads.json` was authored by this port from scratch, informed by what the
source's own tests and probes showed under `go test -overlay` (question-log rows 3-9,
11), not copied from any `usememos-src` fixture file.

## Behaviour derived even where no text was copied

Plainly, yes — that is what a port is. `TagExtractor.java`'s lexical grammar (SPEC-001
R7-R9) and `MemoReadAccess.java`'s decision procedure (R13) are direct translations of
`internal/markdown/parser/tag.go` and `server/access/memo.go`'s logic into Java,
verified to produce identical answers on 27 driven cases (`bench/REPORT.md` §1) — the
behaviour is the source's, deliberately, even though neither file's *text* was copied.

## Also used

- Akka
