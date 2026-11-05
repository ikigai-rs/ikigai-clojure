# The shared vectors

Every file here is a VERBATIM copy of a file another repository owns. Copy them without
reformatting; never edit one by hand. `SHA256SUMS` records each file's digest, and
`ikigai.vectors-test` fails when a file differs from it, holds a carriage return, or has
no `eol=lf` line in `.gitattributes`. ikigai-python and ikigai-deno hold the same files,
byte for byte, and ikigai-devtools' vector-drift compares the copies across the faces.

| File | Source | At commit |
|---|---|---|
| `contract_vectors.json` | ikigai-core `crates/ikigai-vocab/tests/vectors/contract_vectors.json` | [`d232fd7`](https://github.com/ikigai-rs/ikigai-core/commit/d232fd7f21af66de41e40a24dddf0f494b55b18b) |
| `contract_cases.json` | ikigai-core `crates/ikigai-vocab/tests/vectors/contract_cases.json` | [`d232fd7`](https://github.com/ikigai-rs/ikigai-core/commit/d232fd7f21af66de41e40a24dddf0f494b55b18b) |
| `capability_vectors.json` | ikigai-core `crates/ikigai-vocab/tests/vectors/capability_vectors.json` | [`d232fd7`](https://github.com/ikigai-rs/ikigai-core/commit/d232fd7f21af66de41e40a24dddf0f494b55b18b) |
| `validation_vectors.json` | ikigai-core `crates/ikigai-vocab/tests/vectors/validation_vectors.json` | [`d232fd7`](https://github.com/ikigai-rs/ikigai-core/commit/d232fd7f21af66de41e40a24dddf0f494b55b18b) |
| `failure_class_vectors.json` | ikigai-core `crates/ikigai-vocab/tests/vectors/failure_class_vectors.json` | [`d232fd7`](https://github.com/ikigai-rs/ikigai-core/commit/d232fd7f21af66de41e40a24dddf0f494b55b18b) |
| `html_vectors.json` | ikigai-fn `tests/vectors/html_vectors.json` | [`2399a9a`](https://github.com/ikigai-rs/ikigai-fn/commit/2399a9ad80216836d958692f6d5d7d326a761b50) |
| `auth_vectors.json` | ikigai-http `tests/vectors/auth_vectors.json` | [`9db8891`](https://github.com/ikigai-rs/ikigai-http/commit/9db8891b49c330d67b0fc4f46df97987d21b73d5) |
| `ttt_template_cases.txt` | ikigai-tutorial `crates/tic-tac-toe/README.md`, the block between the `template-cases` markers (the lines inside its ```` ```text ```` fence) | [`afc6eee`](https://github.com/ikigai-rs/ikigai-tutorial/commit/afc6eeeaa6696d58006d99a23cfa88ae4f3eb21f) |

Each commit was that repository's `main` on 2026-11-05. On that day every file was also
byte-identical to the copy in ikigai-python and in ikigai-deno.

## Updating a file

1. Fetch the file at the source's new commit, by commit, never by branch name, e.g.
   `curl -fsS -o contract_vectors.json https://raw.githubusercontent.com/ikigai-rs/ikigai-core/<sha>/crates/ikigai-vocab/tests/vectors/contract_vectors.json`.
   For `ttt_template_cases.txt`, copy the lines between the README's ```` ```text ```` and
   closing ```` ``` ```` inside the markers, ending with a newline.
2. `shasum -a 256 $(ls | grep -v -e README.md -e SHA256SUMS | sort) > SHA256SUMS` in this
   directory.
3. Update the table above, then run the tests: a changed file usually means new cases.

## What is not here

- ikigai-python's `fixed_contract_vectors.json` is generated for that face alone and is
  not yet in core's shared vectors; this face takes it when core does.
- ikigai-cli `crates/ikigai-wire/tests/vectors/stream_frames.json` (wire v10's stream
  frames) is held by the cli only so far; the faces will copy it verbatim when they
  implement streams.
- There is no shared vector file for the postcard codec or the wire's call and reply
  bytes. Those are pinned inside the other faces' tests; see ROADMAP.md, step 1.
