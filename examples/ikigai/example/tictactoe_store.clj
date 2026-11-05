(ns ikigai.example.tictactoe-store
  "The tic-tac-toe game's stateful atom, the stored cell, served from Clojure:
  the first demo (ROADMAP.md, \"The first demo\").

  The Rust original is `stored_cell` in ikigai-tutorial
  `crates/tic-tac-toe/src/lib.rs`, and ikigai-python
  `examples/tictactoe_store.py` and ikigai-deno's store test serve the same
  contract, message for message, so a Rust host can mount it and keep the
  rest of the game in its own kernel.

  `urn:iki:tutorial:ttt:stored:{x}:{y}` is one family answering three verbs:
  Source gives the mark at `(x, y)` as `text/plain` (a square nobody has
  played is not-found); Sink plays the mark in `content`, trimmed (an empty
  mark is an invalid argument); Delete clears the square, idempotently. `x`
  and `y` are integers in their one plain spelling: `01`, `+1` and `-0` are
  refused, so one square never has two names.

  Proved by the store checks ikigai-python `tests/test_tictactoe_store.py`
  makes, every message word for word the Rust original's."
  (:require [ikigai.scaffold :as scaffold]))

(def stored
  "The family's IRI template."
  "urn:iki:tutorial:ttt:stored:{x}:{y}")

(def ^:private proof "ikigai-python tests/test_tictactoe_store.py")

(defn stored-name
  "The stored cell's IRI for the square `(x, y)`:
  `(stored-name 0 -1)` is `urn:iki:tutorial:ttt:stored:0:-1`."
  [x y]
  (scaffold/not-implemented `stored-name proof {:x x :y y}))

(defn cell-store
  "A new, empty store of marks by square."
  []
  (scaffold/not-implemented `cell-store proof {}))

(defn stored-cell
  "The stored-cell family over `store`, as an `ikigai.serve/endpoint`."
  [store]
  (scaffold/not-implemented `stored-cell proof {:store store}))
