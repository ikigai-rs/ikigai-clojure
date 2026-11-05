# Roadmap

The order to build this face in. Each step names the namespaces to fill in, the vectors or
tests that prove them, and the files in the other faces to read alongside. ikigai-python
(`src/ikigai/`, `tests/`) is the fullest reference; ikigai-deno (`src/`, `tests/`) is the
second opinion. Where a Rust file is named, it is the normative source and the faces
follow it.

Progress is visible in every test run: `bb test` ends with the number of vector cases
still waiting on a stub, per file and section. When you implement a function, its pending
test fails and tells you to replace those cases with assertions; write them over the same
rows, as the matching test in ikigai-python asserts them, and the count drops.

## 1. Postcard: encode and decode

- **Namespaces**: `ikigai.postcard`, then the codec half of `ikigai.wire` (`encode-call`,
  `decode-call`, `encode-reply`, `decode-reply`, `encode-hello`, `decode-hello`,
  `write-frame`, `read-frame`).
- **Proof**: there is no shared vector file for the codec yet. The byte fixtures live in
  the other faces' tests, hand-derived from the Rust declarations:
  - ikigai-python `tests/test_postcard.py`: varint edges (0, 127, 128, 300, 2^64 - 1),
    strict bool and Option tags, invalid UTF-8, truncation, trailing bytes.
  - ikigai-python `tests/test_wire.py`: the `Issue` call for `urn:test:upper` with
    `in=hi`, the `Resolved` reply, the frame header, and `Conflict("x")` as `05 08 01 78`.
  - ikigai-python `tests/test_hello.py`: the hello is `IKWH`, a u32 big-endian version
    and a mode byte (`IKWH 00 00 00 07 01` for version 7 in alias mode).
- **Read alongside**: ikigai-python `src/ikigai/postcard.py` and `src/ikigai/wire.py`;
  ikigai-deno `src/postcard.ts` and `src/wire.ts`; the Rust layout in ikigai-cli
  `crates/ikigai-wire/src/lib.rs`.
- **Done when** this repository's tests pin the same bytes the Python tests pin, copied
  as literals, and both runtimes pass them.

## 2. The contract and its digest

- **Namespaces**: `ikigai.contract` (`contract-id`, `contract-iri`, `match-iri`, the
  parsers and `canonical-iri`), and `ikigai.check` for the pre-flight check, which reads
  the same action maps.
- **Proof**: `test/vectors/contract_vectors.json` (`contracts`, `matches`, `parses`) and
  `test/vectors/validation_vectors.json` (`cases`).
- **Read alongside**: ikigai-python `src/ikigai/contract.py`, `src/ikigai/check.py`,
  `tests/test_contract.py`, `tests/test_check.py`; ikigai-deno `src/contract.ts`,
  `src/check.ts`.
- **A decision to make first: BLAKE3.** A contract IRI ends in a BLAKE3 digest, and the
  JDK has no BLAKE3. On the JVM, Apache Commons Codec has one
  (`org.apache.commons.codec.digest.Blake3`). babashka cannot load a Java jar it was not
  built with, so the same code does not reach `bb`. The choices are a BLAKE3 written in
  Clojure (checked against the official BLAKE3 test vectors and the contract vectors), a
  babashka pod, or contract digests on the JVM only. The ecosystem's rule is not to
  reimplement a standard where a vetted implementation exists, so ask before writing one.

## 3. The typed errors and their failure classes

- **Namespace**: `ikigai.wire` (`typed-error`, `display`, `failure-class`).
- **Proof**: `test/vectors/failure_class_vectors.json`: for each typed error its wire
  tag, class, whether it is transient, its retry delay, its HTTP status, and its
  `display`, the text the Rust kernel prints, word for word.
- **Read alongside**: ikigai-python `tests/test_failure_classes.py` and the error classes
  in `src/ikigai/wire.py`; the typed-error round trip in `tests/test_wire.py`.
- The same step can take the helpers the vectors also cover, in any order:
  `ikigai.capability` (`test/vectors/capability_vectors.json`), `ikigai.html`
  (`test/vectors/html_vectors.json`), `ikigai.auth` and `ikigai.base64`
  (`test/vectors/auth_vectors.json`).

## 4. The client over a Unix socket, against a real `ikigai` binary

- **Namespace**: `ikigai.client`.
- **Proof**: an integration test that starts a real kernel and calls it: read
  `urn:iki:fn:toUpper`, write and read back a resource, and see a typed error arrive as
  `ex-info` with its `:ikigai.error/kind`. The test skips itself, saying why, when no
  `ikigai` binary is on `PATH`, and CI gets a job that builds one (the other faces' CI
  installs `ikigai-cli` 0.2.8 with `cargo install --locked`; copy their `interop` job).
- **Read alongside**: ikigai-python `src/ikigai/client.py`, `tests/test_integration.py`,
  `tests/rust_door.py`; ikigai-deno `src/client.ts`, `tests/integration_test.ts`.
- `java.net.UnixDomainSocketAddress` with `java.nio.channels.SocketChannel` (JDK 16 and
  later) reaches the socket. babashka 1.13.229 includes both classes; no connection has
  been made through them from `bb` yet.

## 5. Serving an endpoint a host mounts

- **Namespaces**: `ikigai.serve`, `ikigai.template` (for families), and the floor in
  `ikigai.capability` (`required-scopes`, `refusal`).
- **Proof**: `test/vectors/contract_vectors.json` (`doors`: a description's actions, and
  its Meta Turtle byte for byte), `test/vectors/capability_vectors.json` (`floors`,
  `denials`), and a host mounting the server:
  `ikigai --override urn:iki:fn:x:=/tmp/clj.sock -c 'source urn:iki:fn:x:hello'`.
- **Read alongside**: ikigai-python `src/ikigai/serve.py`, `tests/test_serve.py`,
  `tests/test_families.py`, `tests/test_capability.py`; ikigai-deno `src/serve.ts`,
  `tests/client_serve_test.ts`.

## 6. QUIC, later

A remote door over QUIC, as ikigai-python's optional `src/ikigai/quic.py`. Not before the
socket client and server are done; on the JVM it needs a QUIC library, and that choice
is made then.

## The first demo: the tic-tac-toe store from Clojure

Serve the ikigai Book's tic-tac-toe store, the stored cell
`urn:iki:tutorial:ttt:stored:{x}:{y}`, from Clojure, and pass the same store checks the
other faces pass.

- **Namespace**: `ikigai.example.tictactoe-store` (under `examples/`).
- **The checks**: ikigai-python `tests/test_tictactoe_store.py` and ikigai-deno
  `tests/tictactoe_store_test.ts`: an unplayed square is not found; a mark is played,
  read, trimmed and cleared, and clearing twice is fine; an empty mark is refused; each
  square has one spelling (`01`, `+1` and `-0` are refused); the 64-bit edges are
  squares; a verb the family does not answer is refused; Meta names one action per verb.
  Every message is the Rust original's, word for word (ikigai-tutorial
  `crates/tic-tac-toe/src/lib.rs`, `stored_cell`).
- **Then a Rust host mounts it**:
  `ikigai --override urn:iki:tutorial:ttt:stored:=/tmp/ttt.sock -c 'sink urn:iki:tutorial:ttt:stored:1:1 X' -c 'source urn:iki:tutorial:ttt:stored:1:1'`
  answers `X`.
- After the store, the game's views: `ikigai.example.tictactoe-template/fill` against
  `test/vectors/ttt_template_cases.txt`, as ikigai-python `examples/tictactoe_app.py`
  fills them.
