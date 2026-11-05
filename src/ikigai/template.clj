(ns ikigai.template
  "URI templates: the grammar a FAMILY of resources is declared over, a
  line-for-line mirror of `ikigai_core::UriTemplate` (core's `grammar.rs`), by
  way of ikigai-python's `template.py`. The host and this peer must agree on
  which IRIs a pattern names, so the rule is core's exactly:

  - Level 1 only: literal text and `{name}` variables, where a name is ASCII
    letters, digits and `_`. An unclosed `{`, an invalid name, an empty `{}`
    and two adjacent variables are refused as `invalid URI template: {detail}`.
    A `}` with no `{` before it is literal.
  - A variable followed by a literal captures up to the LEFTMOST occurrence of
    that literal; a trailing variable captures the whole remainder.
  - Every capture is non-empty and raw (no percent-decoding), with no other
    character class; the whole IRI must be consumed. A variable named twice is
    accepted, and the last capture wins.

  A template with no variables is an exact IRI, and a template matches its own
  source text (each `{x}` captures the literal `{x}`).

  A parsed template is a map: `{:source s :parts [[var? text] …]}`.

  Proved by the cases ikigai-python's `tests/test_template.py` ports from core,
  copied into `test/ikigai/template_test.clj`."
  (:require [clojure.string :as str]))

(defn- refuse [detail]
  (throw (ex-info (str "invalid URI template: " detail) {:ikigai.template/detail detail})))

(defn parse
  "Parse the template `source` into a value the other functions take, or
  refuse it with an `ex-info` whose message is
  `invalid URI template: {detail}`.

    (:parts (parse \"urn:r:{id}/data\"))  ;=> [[false \"urn:r:\"] [true \"id\"] [false \"/data\"]]"
  [^String source]
  (loop [rest-of source parts []]
    (if-let [open (str/index-of rest-of "{")]
      (let [parts (if (pos? open) (conj parts [false (subs rest-of 0 open)]) parts)
            close (str/index-of rest-of "}" open)]
        (when-not close (refuse (str "unclosed '{' in `" source "`")))
        (let [name (subs rest-of (inc open) close)]
          (when-not (re-matches #"[A-Za-z0-9_]+" name)
            (refuse (str "invalid variable `{" name "}` in `" source "`")))
          (recur (subs rest-of (inc close)) (conj parts [true name]))))
      (let [parts (if (seq rest-of) (conj parts [false rest-of]) parts)]
        (when (some (fn [[[a] [b]]] (and a b)) (partition 2 1 parts))
          (refuse (str "adjacent variables are ambiguous in `" source "`")))
        {:source source :parts parts}))))

(defn variables
  "The variable names of the parsed `template`, in order.

    (variables (parse \"urn:{a_1}:{B2}\"))  ;=> [\"a_1\" \"B2\"]"
  [template]
  (vec (keep (fn [[var? text]] (when var? text)) (:parts template))))

(defn exact?
  "True when the parsed `template` has no variables: it names one IRI."
  [template]
  (empty? (variables template)))

(defn match-iri
  "Match `iri` against the parsed `template` and return the map of variable
  name to captured text, or nil when it does not match.

    (match-iri (parse \"urn:r:{x}:{y}\") \"urn:r:1:2:3\")  ;=> {\"x\" \"1\", \"y\" \"2:3\"}"
  [template ^String iri]
  (let [parts (:parts template)
        n (count parts)]
    (loop [i 0 pos 0 bindings {}]
      (if (= i n)
        (when (= pos (count iri)) bindings)
        (let [[var? text] (nth parts i)]
          (if-not var?
            (when (.startsWith iri ^String text (int pos))
              (recur (inc i) (+ pos (count text)) bindings))
            (let [[next-var? next-text] (when (< (inc i) n) (nth parts (inc i)))]
              (if (and next-text (not next-var?))
                (let [idx (.indexOf iri ^String next-text (int pos))]
                  (when (> idx pos)
                    (recur (inc i) idx (assoc bindings text (subs iri pos idx)))))
                (when (< pos (count iri))
                  (recur (inc i) (count iri) (assoc bindings text (subs iri pos))))))))))))

(defn expand
  "Fill the parsed `template` from `bindings` (variable name to text), or
  return nil when a variable is unbound.

    (expand (parse \"urn:r:{id}/data\") {\"id\" \"7\"})  ;=> \"urn:r:7/data\""
  [template bindings]
  (reduce (fn [out [var? text]]
            (if var?
              (if (contains? bindings text) (str out (get bindings text)) (reduced nil))
              (str out text)))
          "" (:parts template)))
