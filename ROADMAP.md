# Roadmap

What is built, what proves it, and what is left. ikigai-python (`src/ikigai/`, `tests/`) is
the fullest sibling and ikigai-deno (`src/`, `tests/`) the second opinion; where a Rust file
is named, it is the normative source and every face follows it.

Every run of `bb test` or `clojure -X:test` ends with two counts: the vector cases still
waiting on a stub (zero now), and the cases skipped on this runtime, each with its reason.

## Done

| Step | Namespaces | What proves it |
|---|---|---|
| 1. Postcard and the wire | `ikigai.postcard`, `ikigai.wire` | `wire_vectors.json`: every postcard type, hello payload and complete IPC exchange on both runtimes (`wire_vectors_test.clj`); additional refusals and compatibility cases in `postcard_test.clj` and `wire_test.clj` |
| 2. The contract and its digest | `ikigai.contract`, `ikigai.check` | `contract_vectors.json` (`contracts`, `matches`, `parses`: 103 cases) and `validation_vectors.json` (39) |
| 3. Typed errors and the helpers | `ikigai.wire`, `ikigai.capability`, `ikigai.html`, `ikigai.auth`, `ikigai.base64`, `ikigai.template`, `ikigai.iri`, `ikigai.text` | `failure_class_vectors.json` (19), `capability_vectors.json` (226), `html_vectors.json` (141), `auth_vectors.json` (62), core's URI-template cases |
| The template filler | `ikigai.compose` | `ttt_template_cases.txt` (38) |
| 4. The client | `ikigai.client` | `test/ikigai/interop_test.clj` against a real `ikigai`; fake servers for the hello (`client_test.clj`) |
| 5. Serving | `ikigai.serve` | `contract_vectors.json` `doors` (26, Meta Turtle byte for byte); a real host mounting it (`serve_interop_test.clj`) |
| Exploring from a REPL | `ikigai.explore` | `examples/explore.clj`, and the same session as assertions in `explore_test.clj` |

CI runs the suite on babashka and on the JVM, and an `interop` job builds ikigai-cli (the
version ikigai-python and ikigai-deno pin) and runs it again with `:require-interop true`,
so a missing binary fails rather than skips.

## Left

### On babashka: contract digests

A contract IRI ends in a BLAKE3 digest. The JVM has one through Apache Commons Codec
(`commons-codec` in `deps.edn`); babashka has none built in, cannot load that jar, and no
pod in the babashka pod registry offers one (as of 2026-11-05; the `buddy` pod ships a
buddy-core that predates its BLAKE3). So on babashka `ikigai.contract/contract-id`,
`contract-iri`, `ikigai.serve/action-specs`, `description->turtle` and a served door's
Turtle Meta face refuse, naming the JVM path, and the 73 vector cases that need a digest are
reported as skipped with that reason. Serving from babashka still works with a Rust host:
the host renders a mounted door's Turtle itself, from its JSON description.

Ways forward, each a decision rather than a task: a BLAKE3 pod (the reference `blake3`
crate wrapped as a pod would be vetted code), a buddy pod built on a newer buddy-core, or a
BLAKE3 in Clojure, which the ecosystem's rule (never reimplement a standard where a vetted
implementation exists) asks the maintainers to decide first.

### QUIC

A remote door over QUIC, as ikigai-python's optional `src/ikigai/quic.py`. The JDK has no
QUIC, so the JVM needs a library (the choice is made then), and babashka would need a pod.

### Smaller gaps

- **No read timeout.** A Unix-domain `SocketChannel` has no socket timeout, so a hung
  kernel hangs a client call. A watchdog that closes the channel would bound it.
- **No peer uid check when serving.** The socket's mode (`0600` in a `0700` directory) is
  the access control; the JDK's peer-credential option is not available on babashka, nor on
  JDK 21 for macOS.
- **Wire v9's `IssueWith`** is decoded and served, but the client never sends it (no retry
  pool or deadline API yet), and a served handler cannot read the run's time left.
- **Typed arguments.** A served handler receives every argument as a string; ikigai-python's
  typed coercion (an `int` binding in its one plain spelling) is not ported.
- **Turtle as data.** `ikigai.explore/meta` with `:graph` asks the kernel for JSON-LD and
  parses that; a Source whose only face is Turtle comes back as text, since this face writes
  no Turtle parser of its own.

## The first demo: tic-tac-toe from Clojure

Not built, on purpose: it is the first thing to write with the face, not part of it.

- **The store.** `ikigai.example.tictactoe-store` (under `examples/`): serve the ikigai
  Book's stored cell, `urn:iki:tutorial:ttt:stored:{x}:{y}`, with `ikigai.serve` (or
  `ikigai.explore/resource`), and pass the store checks ikigai-python
  `tests/test_tictactoe_store.py` makes, every message word for word the Rust original's
  (ikigai-tutorial `crates/tic-tac-toe/src/lib.rs`, `stored_cell`). Then a Rust host mounts
  it: `ikigai --override urn:iki:tutorial:ttt:stored:=/tmp/ttt.sock -c 'sink
  urn:iki:tutorial:ttt:stored:1:1 X' -c 'source urn:iki:tutorial:ttt:stored:1:1'` answers
  `X`.
- **The views.** `ikigai.example.tictactoe-template`: the game's views on top of
  `ikigai.compose/fill`, which already passes the template language's 38 cases. ikigai-python
  `examples/tictactoe_app.py` (`VIEWS`, `Game.source`) is the model.
- **The rules.** The Book's rule resources, read through a host from Clojure.
