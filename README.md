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

## Status: being built

The codec, the typed errors and the helpers are written and pinned (ROADMAP.md, steps 1
and 3); the contract digest, the client and serving are next. The repository holds:

- the namespaces of the face (`src/ikigai/`) and of its first demo
  (`examples/ikigai/example/`), each public function with a docstring saying what it
  must do and what proves it, and a body that throws `not implemented yet`;
- the shared vectors (`test/vectors/`), copied verbatim, with their sources and commits
  in [`test/vectors/README.md`](test/vectors/README.md);
- tests that run today: each vector file is checked against its recorded digest and its
  shape, and every vector case is reported as **pending** until the function it waits on
  is written;
- [`ROADMAP.md`](ROADMAP.md): the order to build the face in.

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
```

Both test commands run one runner (`test/ikigai/test_runner.clj`): the usual
`clojure.test` counts, then the pending cases per vector file. A run exits 1 on any
failure or error. A pending case is not a pass: its test asserts that the function it
waits on is still a stub, so implementing the function makes that test fail until its
cases become real assertions. CI (`.github/workflows/ci.yml`) runs the same three
commands on every pull request.

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
| `src/ikigai/scaffold.clj` | the `not implemented yet` marker; deleted when nothing uses it |
| `examples/ikigai/example/` | the tic-tac-toe store and template filler |
| `test/vectors/` | the shared vectors |

## Accessibility

What this face emits that people read, and the rules it follows, are in
[ACCESSIBILITY.md](ACCESSIBILITY.md).

## License

MIT OR Apache-2.0, like the other faces: [LICENSE-MIT](LICENSE-MIT),
[LICENSE-APACHE](LICENSE-APACHE).
