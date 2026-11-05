(ns ikigai.example.tictactoe-template
  "The tic-tac-toe game's views, filled from Clojure.

  The template language itself is written: `ikigai.compose/fill` fills a
  template, and `test/ikigai/compose_test.clj` holds it to the 38 template
  cases. What is left here is the game's side: which template each view fills
  (ikigai-tutorial's README has the table: `view:square:{x}:{y}` is
  `template:square` with those arguments), and a `source` that asks a host for
  templates and raw resources. ikigai-python `examples/tictactoe_app.py`
  (`VIEWS`, `Game.source`) is the model.

  The language is specified in ikigai-tutorial `crates/tic-tac-toe/README.md`
  (\"The template language\"), and its normative cases are the block between
  that README's `template-cases` markers, copied verbatim to
  `test/vectors/ttt_template_cases.txt`. Three markers splice an answer
  (`$h{…}` escaped as HTML text, `$r{…}` raw, `$a{…}` filled again),
  `{name}` inside a marker is a template argument, and `urn:iki:fn:conditional`
  is answered by the filler itself.

  `test/ikigai/compose_test.clj` shows a small world of resources and two views
  (`case-source`), the shape a game's `source` takes."
  (:require [ikigai.scaffold :as scaffold]))

(defn fill
  "Fill one of the game's view templates, `template`, with the arguments
  `args` (a map of name to text), resolving each marker through `source`, a
  function of an IRI and an argument map that returns text: a thin layer over
  `ikigai.compose/fill` that also answers the game's own views. Return the
  filled text, or refuse: with compose's own error (`compose: …`) when the
  template is malformed, or with the failing marker's error."
  [template args source]
  (scaffold/not-implemented `fill "the game's views, on ikigai.compose/fill"
                            {:template template :args args :source source}))
