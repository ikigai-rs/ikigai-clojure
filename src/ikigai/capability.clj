(ns ikigai.capability
  "Capability coverage, the served floor and its denials, ported from
  `ikigai_core::capability` (through ikigai-python's `capability.py` and the
  floor in its `serve.py`).

  A capability is `:root` or the set of scopes it holds (the vectors spell
  root as the string `\"root\"` and a held set as a list of IRIs; `capability`
  reads either). A scope is a `urn:cap:` IRI.

  - Root satisfies every scope.
  - A plain scope must be HELD, exactly (core's `Capability::allows`). An
    EXCLUSION (`deny-scope?`: a `-` leading the rule,
    `urn:cap:fs:read:-/root/secret`) is never granted by being held. A
    PRINCIPAL scope (`urn:cap:principal:<iri>`) is granted only as the ONE
    principal the capability carries.
  - A DECLARED scope ending in `*` names a family, a presence test: holding any
    scope under its prefix satisfies it (core's `cap_satisfies`). There is no
    infix wildcard.

  A served door enforces the capabilities it declares on every call it
  answers. The floor is the scopes each verb requires, derived from the
  endpoint's description; a call whose capability does not satisfy them is
  denied with the kernel's own sentence, or, for an existence-sensitive door,
  answered exactly as a name bound nowhere is.

  A description here is the serde (JSON) shape of `ikigai_core::Description`,
  with string keys, as the vectors carry it; verbs inside it are spelled
  `\"Source\"`, `\"Sink\"` and so on. Verbs given to these functions are
  keywords (`:source`).

  Proved by `test/vectors/capability_vectors.json` (`deny_scopes`,
  `coverage`, `principals`, `floors` and `denials`)."
  (:refer-clojure :exclude [satisfies?])
  (:require [clojure.string :as str]
            [ikigai.iri :as iri]
            [ikigai.wire :as wire]))

(def principal-scope-prefix
  "The prefix of a principal scope: `urn:cap:principal:<iri>`."
  "urn:cap:principal:")

(def max-principal-len
  "The longest principal IRI a capability carries, in UTF-8 bytes: 512."
  512)

(defn capability
  "A capability from its vector spelling: `\"root\"` (or `:root`) is `:root`,
  a sequence of scopes is their set.

    (capability [\"urn:cap:a\" \"urn:cap:a\"])  ;=> #{\"urn:cap:a\"}"
  [held]
  (if (#{"root" :root} held) :root (set held)))

(defn- word? [segment]
  (boolean (re-matches #"[a-z0-9][a-z0-9_-]*" segment)))

(defn deny-scope?
  "True when `scope` is a deny scope (core's `is_deny_scope`): a `-` leading
  the rule in `urn:cap:<family>:-<rule>` or
  `urn:cap:<family>:<qualifier>:-<rule>`, where family and qualifier are words
  (`[a-z0-9][a-z0-9_-]*`). Each `deny_scopes` row's `is_deny_scope`.

    (map deny-scope? [\"urn:cap:fs:read:-/root/secret\" \"urn:cap:net:-\"
                      \"urn:cap:fs:read:notes:-x\" \"urn:cap:-fs:read\"])
    ;=> (true true false false)"
  [scope]
  (boolean
   (when (str/starts-with? scope "urn:cap:")
     (let [segments (str/split (subs scope (count "urn:cap:")) #":" 3)]
       (when (and (word? (first segments)) (>= (count segments) 2))
         (let [second-segment (second segments)]
           (cond
             (str/starts-with? second-segment "-") true
             (word? second-segment) (and (= 3 (count segments))
                                         (str/starts-with? (nth segments 2) "-"))
             :else false)))))))

(defn- carriable-principal? [principal-iri]
  (and (<= (count (.getBytes ^String principal-iri "UTF-8")) max-principal-len)
       (iri/iri? principal-iri)
       (not (str/includes? principal-iri "*"))
       (not (deny-scope? (str principal-scope-prefix principal-iri)))))

(defn- principal-in [scope]
  (when (str/starts-with? scope principal-scope-prefix)
    (let [p (subs scope (count principal-scope-prefix))]
      (when (carriable-principal? p) p))))

(defn principal
  "The one principal `capability` carries, or nil for root, for none and for
  several; a wildcard is never one. Each `principals` row's `principal`.

    (principal #{\"urn:cap:x\" \"urn:cap:principal:urn:example:person:alice\"})
    ;=> \"urn:example:person:alice\""
  [capability]
  (when-not (= capability :root)
    (let [found (keep principal-in capability)]
      (when (= 1 (count found)) (first found)))))

(defn allows?
  "True when `capability` grants `scope` matched EXACTLY (core's
  `Capability::allows`): root grants everything; otherwise the scope is held,
  is not an exclusion, and, when it is a principal scope, names the
  capability's one principal. Each `coverage` row's `allows`.

    (allows? #{\"urn:cap:browse:read:core\"} \"urn:cap:browse:read:*\")  ;=> false"
  [capability scope]
  (or (= capability :root)
      (and (contains? capability scope)
           (not (deny-scope? scope))
           (let [named (principal-in scope)]
             (or (nil? named) (= named (principal capability)))))))

(defn satisfies?
  "True when `capability` satisfies the DECLARED scope `scope`, as the
  kernel's floor decides it: a scope ending in `*` is satisfied by holding any
  scope that starts with the text before the `*` (root always); any other
  scope is `allows?`. Each `coverage` row's `satisfies`.

    (satisfies? #{\"urn:cap:browse:read:core\"} \"urn:cap:browse:read:*\")  ;=> true"
  [capability scope]
  (if (str/ends-with? scope "*")
    (or (= capability :root)
        (let [prefix (subs scope 0 (dec (count scope)))]
          (boolean (some #(str/starts-with? % prefix) capability))))
    (allows? capability scope)))

(defn denial-message
  "The text a refused floor carries in its Denied, word for word core's
  `Kernel::denial_message`: a family requirement also names what would
  satisfy it.

    (denial-message \"urn:cap:script:run:open-*\" \"urn:script:open-door\")
    ;=> \"capability does not grant `urn:cap:script:run:open-*` (declared by `urn:script:open-door`; any grant under `urn:cap:script:run:open-` satisfies it)\""
  [scope target]
  (if-not (str/ends-with? scope "*")
    (str "capability does not grant `" scope "` (declared by `" target "`)")
    (let [prefix (subs scope 0 (dec (count scope)))]
      (if (= "" prefix)
        (str "capability does not grant `" scope "` (declared by `" target
             "`; any grant at all satisfies it)")
        (str "capability does not grant `" scope "` (declared by `" target
             "`; any grant under `" prefix "` satisfies it)")))))

;; ---------------------------------------------------------------------------
;; The floor, from a description in its serde shape
;; ---------------------------------------------------------------------------

(def ^:private flat-v2-keys
  ["failures" "groups" "arguments" "output_from" "existence_sensitive"])

(defn description-actions
  "`Description::action_specs` over a description in its serde shape: one
  action (serde shape) per non-Meta verb, the explicit one where declared,
  else made from the flat fields with an empty summary. A verb with several
  explicit actions takes the FIRST, as core's `find` does.

    (description-actions {\"verbs\" [\"Source\" \"Meta\"] \"inputs\" [] \"outputs\" []
                          \"requires\" [\"urn:cap:x\"]})
    ;=> [{\"verb\" \"Source\", \"summary\" \"\", \"inputs\" [], \"outputs\" [], \"requires\" [\"urn:cap:x\"]}]"
  [description]
  (let [explicit (reduce (fn [m a] (if (contains? m (get a "verb")) m (assoc m (get a "verb") a)))
                         {} (get description "actions"))]
    (vec (for [verb (get description "verbs")
               :when (not= verb "Meta")]
           (or (get explicit verb)
               (merge {"verb" verb
                       "summary" ""
                       "inputs" (get description "inputs")
                       "outputs" (get description "outputs")
                       "requires" (get description "requires" [])}
                      (select-keys description flat-v2-keys)))))))

(defn- floor-table
  "`[declared every]` as core's Floor holds them: each declared verb with its
  own requires, and the union of every scope declared, deduplicated in
  declaration order."
  [description]
  (let [declared (mapv (fn [a] [(get a "verb") (get a "requires" [])]) (description-actions description))
        sources (if (seq declared)
                  (map second declared)
                  (cons (get description "requires" [])
                        (map #(get % "requires" []) (get description "actions"))))]
    [declared (vec (distinct (apply concat sources)))]))

(defn required-scopes
  "The scopes `verb` requires on the endpoint `description` (a description
  map, as the vectors carry it): each `floors` row gives them per verb under
  `verbs`. A declared verb needs exactly its own requires (an explicit
  per-verb requires REPLACES the flat one); an undeclared Exists needs what
  Source needs; any other undeclared verb needs everything declared,
  deduplicated in declaration order; and Meta needs nothing.

    (def calendar {\"verbs\" [\"Source\" \"Sink\" \"Meta\"] \"inputs\" [] \"outputs\" []
                   \"actions\" [{\"verb\" \"Source\" \"requires\" [\"urn:cap:cal:read\"]}
                               {\"verb\" \"Sink\" \"requires\" [\"urn:cap:cal:write\"]}]})
    (map #(required-scopes calendar %) [:sink :exists :delete :meta])
    ;=> ([\"urn:cap:cal:write\"] [\"urn:cap:cal:read\"]
    ;    [\"urn:cap:cal:read\" \"urn:cap:cal:write\"] [])"
  [description verb]
  (let [[declared every] (floor-table description)
        own (reduce (fn [m [v r]] (if (contains? m v) m (assoc m v r))) {} declared)
        verb-name (wire/verb-name verb)]
    (cond
      (contains? own verb-name) (vec (get own verb-name))
      (= verb :meta) []
      (and (= verb :exists) (contains? own "Source")) (vec (get own "Source"))
      :else every)))

(defn unsatisfied-scopes
  "The scopes in `required-scopes` that `capability` does not satisfy: empty
  exactly when the floor admits the call."
  [description verb capability]
  (vec (remove #(satisfies? capability %) (required-scopes description verb))))

(defn conceals?
  "True when the floor's refusal of `verb` under `capability` must answer as a
  name bound nowhere (Unresolved), never Denied: core's `Floor::conceals`.
  Nothing is concealed unless the description marks existence sensitivity
  somewhere; a declared verb conceals when its own action is marked and the
  floor refuses it; an undeclared verb whenever the floor refuses it; and Meta,
  which the floor never gates, when the capability passes the floor of no
  declared verb."
  [description verb capability]
  (let [specs (description-actions description)
        marked (set (keep #(when (get % "existence_sensitive") (get % "verb")) specs))
        anywhere (or (seq marked)
                     (get description "existence_sensitive")
                     (some #(get % "existence_sensitive") (get description "actions")))]
    (boolean
     (when anywhere
       (let [passes? (fn [scopes] (every? #(satisfies? capability %) scopes))
             [declared every] (floor-table description)
             verb-name (wire/verb-name verb)]
         (cond
           (= verb :meta) (if (empty? declared)
                            (not (passes? every))
                            (not-any? (comp passes? second) declared))
           (and (some #(= verb-name (first %)) declared) (not (marked verb-name))) false
           :else (not (passes? (required-scopes description verb)))))))))

(defn refusal
  "What the floor answers a `verb` call on `target` under `capability` for the
  endpoint `description`: nil when it admits the call, `{:denied sentence}`
  with the kernel's Denied text when it refuses, and `{:unresolved iri}` when
  an existence-sensitive door conceals itself. Each `denials` row's `denied`
  and `unresolved`.

    (refusal {\"verbs\" [\"Source\"] \"inputs\" [] \"outputs\" []
              \"requires\" [\"urn:cap:cal:read\"]}
             :source \"urn:cal\" #{\"urn:cap:nothing\"})
    ;=> {:denied \"capability does not grant `urn:cap:cal:read` (declared by `urn:cal`)\"}"
  [description verb target capability]
  (if (conceals? description verb capability)
    {:unresolved target}
    (when-let [lacking (first (unsatisfied-scopes description verb capability))]
      {:denied (denial-message lacking target)})))
