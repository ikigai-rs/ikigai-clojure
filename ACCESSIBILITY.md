# Accessibility

What `ikigai-clojure` emits that people read (error messages and terminal text), the rules
that output follows, how it is tested, and how to report a barrier. It is not a conformance
claim. Adapted from the [ACCESSIBILITY.md template](https://github.com/mgifford/ACCESSIBILITY.md)
(MIT).

| Field | Value |
|---|---|
| Target | WCAG 2.2 Level AA, applied to text output; this repository writes no HTML |
| Status | Evaluation in progress. No conformance claim. |
| Owner | the maintainers of this repository |
| Report a barrier | [a GitHub issue](https://github.com/ikigai-rs/ikigai-clojure/issues/new?labels=accessibility) with the `accessibility` label; anything security-sensitive through [private vulnerability reporting](https://github.com/ikigai-rs/ikigai-clojure/security/advisories/new) |
| Last reviewed | 2026-11-05 |

## What it emits that people read

The repository is a scaffold: no function of the face is implemented yet. Today it emits:

- **One error.** Every unimplemented function throws an `ex-info` whose message is
  `not implemented yet` and whose data names the function (`:ikigai/not-implemented`) and
  what will prove it (`:ikigai/proved-by`), in `src/ikigai/scaffold.clj`.
- **Terminal text from the tests.** `bb test` and `clojure -X:test` print `clojure.test`'s
  report, then one line per vector file and section with the number of pending cases and
  the functions they wait on (`test/ikigai/test_runner.clj`). A failing test prints a
  sentence that says what is wrong and what to do, for example that a vector file differs
  from its recorded copy and must be re-copied from its source, or that a function is no
  longer a stub and its pending cases need assertions.
- **Terminal text from lint.** `bb lint` prints clj-kondo's own findings, unchanged.

As the face is built (ROADMAP.md), it will emit what the other faces emit: the typed errors
the kernel sends, displayed as the Rust kernel's `Display` prints them; refusals for
malformed input from the codec, the template parser and the header and base64 helpers; and
the plain-text Meta face of a served endpoint.

Not this repository's: the text of a failure a remote kernel sends, which this face will
carry unchanged, and the output of babashka, the Clojure CLI, the JVM and clj-kondo.

## The rules that output follows

- **Meaning is in words.** No output here uses color, position or symbols alone to carry
  meaning; the pending summary states counts and function names as text.
- **A failure says what to do.** Each failing test's message names the file or function and
  the action that fixes it.
- **A refusal will read as the Rust kernel prints it.** Once `ikigai.wire/display` is
  written, its text for every typed error is pinned against the `display` field of
  `test/vectors/failure_class_vectors.json`, word for word, as in the other faces.
- **No credential reaches an error.** The stubs in `src/ikigai/auth.clj` record a
  password or token in their exception data as `:redacted`, never the value.

## How it is tested

- `test/ikigai/scaffold_test.clj` pins the stub exception's message and data, and that every
  public var has a docstring.
- `test/ikigai/pending_test.clj` and `test/ikigai/vectors_test.clj` carry the failure
  messages quoted above; both were made to fail on purpose (a function implemented, a vector
  file changed by one byte) and printed those messages, on 2026-11-05.
- **No assistive technology or automated accessibility checker has been used on this
  output.** Nothing measures the terminal text beyond reading it.

## Known limitations

Found by reading the output on 2026-11-05, not by testing with people or assistive
technology:

- The pending summary is a fixed-width table: a screen reader reads it line by line, and a
  line wider than the terminal wraps.
- `not implemented yet` does not say what to do; its exception data does (the function and
  what proves it), but a caller sees the data only when it prints the exception.

## Contributing

Read this file before changing any output listed above. Keep meaning in words, keep
`display` equal to the shared vectors once it exists, and say in the pull request what was
tested, with what, and what was not.

## Reporting a barrier

[Open an issue](https://github.com/ikigai-rs/ikigai-clojure/issues/new?labels=accessibility)
with the `accessibility` label (the link sets it when GitHub lets you add labels; otherwise a
maintainer adds it): what you were doing, what happened, and your terminal and any
assistive technology, if you are comfortable sharing them. Anything security-sensitive goes
through
[private vulnerability reporting](https://github.com/ikigai-rs/ikigai-clojure/security/advisories/new).
**We acknowledge a report within 7 days**, and promise nothing further.

## Change history

| Date | Change |
|---|---|
| 2026-11-05 | First version. |
