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

- **The typed errors a kernel sends**, and the ones this face raises as a server: an
  `ex-info` whose message is the text the Rust kernel's `Display` prints for that error
  (`no endpoint resolved for urn:x`, `denied: capability does not grant …`), word for word,
  and whose data names the kind (`:ikigai.error/kind`).
- **Refusals of malformed input**, each an `ex-info` with a sentence and a stable code or
  kind in its data: the codec and the wire (`truncated message: …`, `unknown Reply variant
  …: this side speaks ikigai wire protocol v9 …`), URI templates (`invalid URI template:
  …`), percent decoding (`percent_decode: malformed percent-escape at byte 3`), the template
  filler (`compose: …`), and the header and base64 helpers, whose message is only the code
  (`basic-user-id-colon`, `base64-invalid`).
- **Connection messages** from the client (`cannot reach a kernel server at … ; is
  `ikigai serve` (or the daemon) running?`, `the kernel server speaks wire v10, this client
  speaks v9 …; update the older side`) and from the exploring layer (`not connected to a
  kernel: call (connect! "/path/to/kernel.sock") first`).
- **A served endpoint's plain-text Meta face**, which a person reads through `describe … text/plain`.
- **Terminal text from the tests**: `clojure.test`'s report, then the count of vector cases
  waiting on a stub and of cases skipped on this runtime, each skip with its reason
  (`test/ikigai/test_runner.clj`).
- **The BLAKE3 refusal on babashka**: a sentence naming what is missing and the JVM path.

Not this repository's: the text of a failure a remote kernel sends, which this face carries
unchanged, and the output of babashka, the Clojure CLI, the JVM, clj-kondo and `ikigai`.

## The rules that output follows

- **Meaning is in words.** No output here uses color, position or symbols alone to carry
  meaning; counts and reasons are stated as text.
- **A refusal reads as the Rust kernel prints it.** `ikigai.wire/display` is pinned against
  the `display` field of `test/vectors/failure_class_vectors.json`, word for word, and the
  floor's denials against `capability_vectors.json`.
- **A failure says what to do** where there is something to do: start a kernel, connect
  first, update the older side, compute on the JVM.
- **No credential reaches an error.** The auth and base64 refusals carry a code, never the
  user id, password, token or input; a test asserts it.

## How it is tested

- `test/ikigai/wire_test.clj` pins every typed error's display; `helpers_test.clj` the
  auth, base64, percent-decoding and capability sentences; `client_test.clj` and
  `interop_test.clj` the connection messages, against fake servers and a real kernel.
- **No assistive technology or automated accessibility checker has been used on this
  output.** Nothing measures the terminal text beyond reading it.

## Known limitations

Found by reading the output on 2026-11-05, not by testing with people or assistive
technology:

- The test runner's summary is a fixed-width table: a screen reader reads it line by line,
  and a line wider than the terminal wraps.
- An `ex-info` printed at a REPL shows its stack trace after the message; the message is
  the first line.

## Contributing

Read this file before changing any output listed above. Keep meaning in words, keep
`display` equal to the shared vectors, and say in the pull request what was tested, with
what, and what was not.

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
| 2026-11-05 | The face is built: the typed errors, refusals and connection messages it emits. |
