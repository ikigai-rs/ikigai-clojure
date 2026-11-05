(ns ikigai.capability
  "Capability coverage, the served floor and its denials, ported from
  `ikigai_core::capability`.

  A capability is `:root` or the set of scopes it holds (the vectors spell
  root as the string `\"root\"` and a held set as a list of IRIs). A scope is a
  `urn:cap:` IRI. A DECLARED scope ending in `*` names a family, satisfied by
  holding any scope under its prefix; a deny scope (an exclusion) is never a
  grant.

  A served door enforces the capabilities it declares on every call it
  answers. The floor is the scopes each verb requires, derived from the
  endpoint's description; a call whose capability does not satisfy them is
  denied with the kernel's own sentence, or, for an existence-sensitive door,
  answered exactly as a name bound nowhere is.

  Proved by `test/vectors/capability_vectors.json` (`deny_scopes`,
  `coverage`, `principals`, `floors` and `denials`). Read alongside
  ikigai-python `src/ikigai/capability.py` and the floor in its
  `src/ikigai/serve.py` (`required_scopes`, `conceals`)."
  (:refer-clojure :exclude [satisfies?])
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private proof "test/vectors/capability_vectors.json")

(defn deny-scope?
  "True when `scope` is a deny scope: each `deny_scopes` row's
  `is_deny_scope`."
  [scope]
  (scaffold/not-implemented `deny-scope? proof {:scope scope}))

(defn allows?
  "True when `capability` grants `scope` matched EXACTLY (core's
  `Capability::allows`): root grants everything; otherwise the scope is held,
  is not an exclusion, and, when it is a principal scope, names the
  capability's one principal. Each `coverage` row's `allows`."
  [capability scope]
  (scaffold/not-implemented `allows? proof {:capability capability :scope scope}))

(defn satisfies?
  "True when `capability` satisfies the DECLARED scope `scope`, as the
  kernel's floor decides it: a scope ending in `*` is satisfied by holding any
  scope that starts with the text before the `*` (root always); any other
  scope is `allows?`. Each `coverage` row's `satisfies`."
  [capability scope]
  (scaffold/not-implemented `satisfies? proof {:capability capability :scope scope}))

(defn principal
  "The one principal `capability` carries, or nil for root, for none and for
  several; a wildcard is never one. Each `principals` row's `principal`."
  [capability]
  (scaffold/not-implemented `principal proof {:capability capability}))

(defn required-scopes
  "The scopes `verb` requires on the endpoint `description` (a description
  map, as the vectors carry it): each `floors` row gives them per verb under
  `verbs`. An undeclared Exists needs what Source needs, an undeclared
  mutating verb needs everything declared, and Meta needs nothing."
  [description verb]
  (scaffold/not-implemented `required-scopes proof {:description description :verb verb}))

(defn refusal
  "What the floor answers a `verb` call on `target` under `capability` for the
  endpoint `description`: nil when it admits the call, `{:denied sentence}`
  with the kernel's Denied text when it refuses, and `{:unresolved iri}` when
  an existence-sensitive door conceals itself. Each `denials` row's `denied`
  and `unresolved`."
  [description verb target capability]
  (scaffold/not-implemented `refusal proof
                            {:description description :verb verb
                             :target target :capability capability}))
