(ns ikigai.example.tictactoe-template
  "The tic-tac-toe template language: a filler for the game's views.

  The language is specified in ikigai-tutorial `crates/tic-tac-toe/README.md`
  (\"The template language\"), and its normative cases are the block between
  that README's `template-cases` markers, copied verbatim to
  `test/vectors/ttt_template_cases.txt`. Three markers splice an answer
  (`$h{…}` escaped as HTML text, `$r{…}` raw, `$a{…}` filled again),
  `{name}` inside a marker is a template argument, and `urn:iki:fn:conditional`
  is answered by the filler itself.

  Proved by `test/vectors/ttt_template_cases.txt`, filled with the arguments
  and resources the README names (ikigai-python `tests/test_tictactoe_app.py`
  sets them up as `CASE_ARGUMENTS`, `CASE_RESOURCES` and `CASE_VIEWS`). Read
  alongside ikigai-python `examples/tictactoe_app.py` (`fill`)."
  (:require [ikigai.scaffold :as scaffold]))

(defn fill
  "Fill `template` with the arguments `args` (a map of name to text),
  resolving each marker through `source`, a function of an IRI and an
  argument map that returns text. Return the filled text, or refuse: with
  compose's own error (`compose: …`) when the template is malformed, or with
  the failing marker's error."
  [template args source]
  (scaffold/not-implemented `fill "test/vectors/ttt_template_cases.txt"
                            {:template template :args args :source source}))
