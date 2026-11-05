# ikigai-clojure

The Clojure face for [ikigai](https://github.com/ikigai-rs), for babashka and the JVM.

ikigai is a resource-oriented kernel: everything is a resource named by a URI, read and
written through five verbs (Source, Sink, Exists, Delete, Meta), with capabilities
deciding who may do what. A **face** lets another language take part in two ways: as a
**client** that calls a running kernel over its socket, and as a **servable peer**, whose
functions a kernel mounts as resources of its own. ikigai-clojure is the fourth face,
beside [ikigai-python](https://github.com/ikigai-rs/ikigai-python),
[ikigai-deno](https://github.com/ikigai-rs/ikigai-deno) and
[ikigai-dotnet](https://github.com/ikigai-rs/ikigai-dotnet). All of them are held to the
same shared vectors, byte for byte.

## Status

The face is built through ROADMAP.md step 5: the codec and the wire, the typed errors, the
helpers, the contract digest and the pre-flight check, the client, and serving, each held
to the shared vectors (every case is an assertion) and the client and server to a real
`ikigai` binary. QUIC is not built. Contract digests need BLAKE3, which only the JVM has
here (ROADMAP.md, "Left"). The tic-tac-toe demo under `examples/` is left as the first
thing to build with the face.

## Explore from a REPL

`ikigai.explore` makes a REPL session the way in: connect to a kernel in one call, look
around, read and write with plain functions that return Clojure data, and serve a Clojure
function as a resource the kernel can use and compose.

```clojure
(require '[ikigai.explore :as ik])

(ik/start-peer!)                      ; your Clojure resources' socket; start it first
;; in a terminal:
;;   ikigai --files /tmp/ik/files serve /tmp/ik/kernel.sock \
;;          --mount urn:clj:=/tmp/ikigai-clj-$USER/peer.sock
(ik/connect! "/tmp/ik/kernel.sock")

(ik/catalog "fn")                     ; what is here?
(ik/args "urn:iki:fn:toUpper")        ; what does it take?
(ik/source "urn:iki:fn:toUpper" {:in "hi"})          ;=> "HI"
(ik/sink "urn:file:hello.txt" "hello from Clojure")

(ik/serve! "urn:clj:greet" {:args ["who"]} (fn [{:keys [who]}] (str "Hello, " who "!")))
(ik/source "urn:clj:greet" {:who "Ada"})             ;=> "Hello, Ada!"
;; and in the ikigai REPL: source urn:clj:greet who=Ada
```

A kernel's typed error arrives as an `ex-info` whose data names `:ikigai.error/kind`
(`:unresolved`, `:denied`, `:not-found`, …) and whose message is the kernel's own
sentence. [`examples/explore.clj`](examples/explore.clj) is a guided session: start a
kernel, read, write, serve a Clojure resource and use it from the `ikigai` REPL, compose it
with a built-in, and watch a cached answer recompute after a write.

Below `ikigai.explore` are the functions it composes: `ikigai.client` (a connection you
pass explicitly) and `ikigai.serve` (an endpoint from a full description).

## Run the tests

You need [babashka](https://babashka.org), the
[Clojure CLI](https://clojure.org/guides/install_clojure) with a JDK (21 is what CI
uses), and [clj-kondo](https://github.com/clj-kondo/clj-kondo). On macOS:

```sh
brew install borkdude/brew/babashka borkdude/brew/clj-kondo clojure/tools/clojure
```

Then, at the repository root:

```sh
bb lint                    # clj-kondo; any warning fails
bb test                    # the tests under babashka
clojure -X:test            # the same tests on the JVM
bb test :verbose true      # also list every pending vector case by name
bb test :require-interop true   # fail, not skip, when no `ikigai` binary is on PATH
```

The interop tests (`test/ikigai/interop_test.clj`) start a real `ikigai` kernel, the first
one on `PATH`, in a scratch config home; without one they are reported as skipped, with
the reason.

Both test commands run one runner (`test/ikigai/test_runner.clj`): the usual
`clojure.test` counts, then the vector cases still waiting on a stub (none now), then the
cases skipped on this runtime, each with its reason (on babashka, the contract digests,
which need BLAKE3). A run exits 1 on any failure or error. CI
(`.github/workflows/ci.yml`) runs the same commands on every pull request, and an
`interop` job runs them again against a real `ikigai` with `:require-interop true`.

## Layout

| Path | What |
|---|---|
| `src/ikigai/postcard.clj`, `wire.clj` | the wire's byte format, frames, the hello, typed errors |
| `src/ikigai/contract.clj`, `check.clj` | contract and match IRIs, the pre-flight check |
| `src/ikigai/capability.clj` | capability coverage and the served floor |
| `src/ikigai/html.clj`, `auth.clj`, `base64.clj` | the ecosystem's escaping, URL, header and base64 rules |
| `src/ikigai/template.clj` | URI templates, for families of resources |
| `src/ikigai/iri.clj` | core's IRI check and name encoding |
| `src/ikigai/compose.clj` | the template language's filler (`$h{…}`, `$r{…}`, `$a{…}`) |
| `src/ikigai/client.clj`, `serve.clj` | the client and the servable peer |
| `src/ikigai/explore.clj` | the REPL layer: one session connection, serving functions as resources |
| `src/ikigai/text.clj` | Rust's trim and Unicode White_Space |
| `src/ikigai/scaffold.clj` | the `not implemented yet` marker; deleted when nothing uses it |
| `examples/explore.clj` | a guided REPL session |
| `examples/ikigai/example/` | the tic-tac-toe store and views, to build (stubs) |
| `test/vectors/` | the shared vectors |

## Accessibility

What this face emits that people read, and the rules it follows, are in
[ACCESSIBILITY.md](ACCESSIBILITY.md).

## License

MIT OR Apache-2.0, like the other faces: [LICENSE-MIT](LICENSE-MIT),
[LICENSE-APACHE](LICENSE-APACHE).
