(ns ikigai.check
  "The pre-flight check: an action's contract applied to a proposed set of
  named arguments, mirroring `ikigai_core::ActionSpec::check`.

  It answers whether the arguments conform before anything is invoked: a
  required input that is missing, a value outside an input's `one_of`, a
  value that is not of its declared XSD class, an argument the action does not
  declare, and the rest of core's rules, each as a violation.

  Proved by `test/vectors/validation_vectors.json` (`cases`). Read alongside
  ikigai-python `src/ikigai/check.py`."
  (:require [ikigai.scaffold :as scaffold]))

(defn check
  "Check the named arguments `args` (a sequence of `[name value]` pairs, in
  the order given, repeats allowed) against `action` (an action map, as the
  vectors carry it). Return the violations in core's order, as maps equal to
  the case's `violations`; an empty sequence means the arguments conform."
  [action args]
  (scaffold/not-implemented `check "test/vectors/validation_vectors.json"
                            {:action action :args args}))
