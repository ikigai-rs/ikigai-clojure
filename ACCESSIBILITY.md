# Accessibility

What `ikigai-clojure` emits that people read (error messages and terminal text), the rules
that output follows, how it is tested, and how to report a barrier. It is not a conformance
claim. Adapted from the [ACCESSIBILITY.md template](https://github.com/mgifford/ACCESSIBILITY.md)
(MIT).

| Field | Value |
|---|---|
| Target | WCAG 2.2 Level AA, applied to text output; this repository provides no web UI |
| Status | Evaluation in progress. No conformance claim. |
| Owner | the maintainers of this repository |
| Report a barrier | [a GitHub issue](https://github.com/ikigai-rs/ikigai-clojure/issues/new?labels=accessibility) with the `accessibility` label; anything security-sensitive through [private vulnerability reporting](https://github.com/ikigai-rs/ikigai-clojure/security/advisories/new) |
| Last reviewed | 2026-11-13 (UTC) |

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
- **Auth and base64 refusals carry a code**, rather than echoing the user id, password,
  token or input. The credential regression test checks the Basic-auth fixture secret;
  it is not a general audit of every error path.

## How it is tested

Every named check has a result: Pass, Fail or Blocked, with evidence. A Pass covers only
the stated check, not overall accessibility or WCAG conformance. Unrun checks are Blocked.

The checks below were run on 2026-11-13 (UTC), on main commit
[`925d56364d612dfec24ef7a4e1f147468ad949b8`](https://github.com/ikigai-rs/ikigai-clojure/commit/925d56364d612dfec24ef7a4e1f147468ad949b8),
before this documentation update. Runtime versions: babashka 1.13.229; Clojure 1.12.6,
Clojure CLI 1.12.6.1673, Temurin OpenJDK 21.0.13+9. Unless a row says otherwise, its
command is `bb test` and `clojure -X:test`, which CI repeats. These are functional tests,
not automated accessibility checkers or tests with assistive technology. With no `ikigai`
binary on PATH, these runs passed 107 tests each (3,881 assertions on babashka, 4,102 on
the JVM), skipping 18 host-backed cases on both runtimes and 73 additional digest cases
on babashka. A skipped case is not a Pass.

| Check | Result | Evidence |
|---|---|---|
| Typed error display and failure classification | Pass | `test/ikigai/wire_test.clj`: exact display text against shared failure vectors, typed error round trips and malformed-wire refusals. Does not measure comprehension or spoken presentation. |
| Capability denials and helper refusals | Pass | `test/ikigai/helpers_test.clj`: capability vectors, auth/base64 refusal codes and selected exact percent-decoding messages; `test/ikigai/template_test.clj` and `test/ikigai/compose_test.clj`: malformed-template refusals. Does not evaluate whether every refusal is actionable. |
| Client connection guidance | Pass | `test/ikigai/client_test.clj`: fake-server version mismatch and hang-up, and missing-server guidance. Does not test how a terminal or screen reader presents the exceptions. |
| Credential concealment regression | Pass | `test/ikigai/helpers_test.clj`, `basic-refusals`: checks that the Basic-auth fixture secret is absent from captured exception data and message. Does not audit all credentials or all error paths. |
| Served plain-text Meta face | Pass | `test/ikigai/serve_test.clj`, `meta-answers-json-text-and-the-alias`: checks the beginning of the plain-text description. Does not check its full presentation with assistive technology. |
| BLAKE3 refusal on babashka | Pass | `bb test`, `test/ikigai/contract_test.clj`: asserts the `:no-blake3` error kind; the runner prints the missing-library reason and JVM alternative. The message is not asserted word for word. JVM digest checks run with `clojure -X:test`; 73 digest-dependent vector cases are skipped on babashka. |
| Test-runner counts and skip reasons | Pass | Read captured output of `bb test` and `clojure -X:test`: zero pending cases, explicit skipped-case counts and reasons. This inspects text content, not wrapping, navigation or spoken output. |
| Meaning without color, position or symbols alone | Blocked | No comprehensive accessibility review of all emitted text was performed. Functional assertions above cover selected messages; they do not prove this rule for all output. |
| Actionable guidance across all refusals, including the exploring layer | Blocked | No complete message review was run. `test/ikigai/explore_test.clj` does not assert the not-connected guidance despite its test name; the client checks above cover only their stated cases. |
| Automated accessibility checker | Blocked | No automated accessibility checker has been run on this terminal/REPL output; no terminal-output checker was configured for this review. Lint and functional tests do not substitute for one. |
| Manual keyboard and terminal/REPL navigation | Blocked | No person's interactive keyboard pass was performed; this review used unattended command execution. |
| Screen reader | Blocked | No screen reader session was used on this output; none was available for this review. |
| Screen magnifier | Blocked | No magnifier session was used on this output; none was available for this review. |
| Speech input | Blocked | No speech-input session was used on this output; none was available for this review. |
| Switch access | Blocked | No switch-access session was used on this output; none was available for this review. |
| Braille display | Blocked | No braille display was used on this output; none was available for this review. |
| Historical output-layout and REPL presentation review | Blocked | The prior record says output was read on 2026-11-05, but gives no terminal, REPL version or command. CI does not repeat that manual review. It is context, not current verification. |

## Known limitations

The 2026-11-05 reading recorded a fixed-width test-summary layout that may wrap in a
narrow terminal, and stack traces following exception messages at a REPL. Neither terminal
wrapping nor REPL presentation has been verified in this review. Their impact on screen
readers and other assistive technology remains Blocked above.

## Contributing

Read this file before changing any output listed above. Keep meaning in words, keep
`display` equal to the shared vectors, and say in the pull request what was tested, with
what, and what was not. Give each check a row in a `Check | Result | Evidence` table,
with Pass, Fail or Blocked and the test or command, tool and version, or why it could not
run. Keep unrun assistive-technology checks as Blocked; a historical run CI does not repeat
is Blocked with its date.

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
| 2026-11-13 | Record per-check results, reproducible scope and blocked manual and assistive-technology checks. |
