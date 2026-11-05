# Changelog

## Unreleased

- **The contract and the pre-flight check.** `ikigai.contract` (match and contract IRIs,
  both parsers, the canonical spelling, and the v1 and v2 canonical forms) agrees with all
  47 of core's contract digests; `ikigai.check` with all 39 validation cases. BLAKE3 is
  Apache Commons Codec's (`commons-codec` 1.22.0) on the JVM; babashka has no BLAKE3, so
  there a contract digest refuses with a sentence naming the JVM path and the test runner
  reports those cases as skipped, with that reason.

- **The codec, the typed errors and the helpers.** `ikigai.postcard` and `ikigai.wire`
  (calls, replies, the hello, framing, every typed error with its Display, failure class
  and HTTP status, and the per-peer downgrade), pinned by ikigai-python's byte literals
  and `failure_class_vectors.json`; `ikigai.capability` (coverage, principals, the
  floor and its denials), `ikigai.html`, `ikigai.auth`, `ikigai.base64`, `ikigai.template`
  (URI templates) and `ikigai.iri` (core's `Iri::parse`, `encode_name`), each against
  its shared vectors; and `ikigai.compose`, the template language's filler, against the
  38 tic-tac-toe template cases. 486 of the 654 pending vector cases are now assertions.

- **The scaffold.** The face's namespaces (`ikigai.postcard`, `ikigai.wire`,
  `ikigai.contract`, `ikigai.check`, `ikigai.capability`, `ikigai.template`,
  `ikigai.html`, `ikigai.auth`, `ikigai.base64`, `ikigai.client`, `ikigai.serve`) and
  the tic-tac-toe examples, every public function documented and stubbed; the shared
  vectors under `test/vectors/`, copied verbatim and recorded in `SHA256SUMS`; tests
  that check the vectors' digests and shapes and report 654 vector cases as pending;
  `bb.edn`, `deps.edn`, CI for babashka and the JVM, and `ROADMAP.md`.
