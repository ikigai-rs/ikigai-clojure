(ns ikigai.template
  "URI templates: the grammar a FAMILY of resources is declared over, a
  line-for-line mirror of `ikigai_core::UriTemplate`.

  A template is Level 1: literal text and `{name}` variables, where a name is
  ASCII letters, digits and `_`. An unclosed `{`, an invalid name, an empty
  `{}` and two adjacent variables are refused as
  `invalid URI template: {detail}`.

  No shared vector file pins these yet. Read alongside ikigai-python
  `src/ikigai/template.py` and `tests/test_template.py`, and ikigai-deno
  `src/template.ts`. Needed at step 5 of ROADMAP.md, for families."
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private proof "ikigai-python tests/test_template.py")

(defn parse
  "Parse the template `source` into a value the other functions take, or
  refuse it with an `ex-info` whose message is
  `invalid URI template: {detail}`."
  [source]
  (scaffold/not-implemented `parse proof {:source source}))

(defn variables
  "The variable names of the parsed `template`, in order."
  [template]
  (scaffold/not-implemented `variables proof {:template template}))

(defn match-iri
  "Match `iri` against the parsed `template` and return the map of variable
  name to captured text, or nil when it does not match."
  [template iri]
  (scaffold/not-implemented `match-iri proof {:template template :iri iri}))

(defn expand
  "Fill the parsed `template` from `bindings` (variable name to text), or
  return nil when a variable is unbound."
  [template bindings]
  (scaffold/not-implemented `expand proof {:template template :bindings bindings}))
