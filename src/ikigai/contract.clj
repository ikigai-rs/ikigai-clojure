(ns ikigai.contract
  "The two identities an action has, mirroring `ikigai_core::contract` (by way
  of ikigai-python's `contract.py`).

  A MATCH IRI names where an action answers: `urn:ikigai:match:{verb}:{pattern}`,
  one per door and verb. A CONTRACT IRI names what the action promises, by its
  content: `urn:ikigai:contract:{endpoint-id}:{verb}:b3:{hex}`, where `{hex}`
  is the BLAKE3 digest of the contract's canonical form (`ikigai:contract:v1`,
  or `ikigai:contract:v2` when the contract uses what only v2 can say).
  Identical contracts share one IRI and different ones never do, so the digest
  must be byte for byte the one the Rust kernel computes.

  Lenient in, canonical out: the parsers accept every spelling core accepts,
  and `canonical-iri` says an IRI back in the one spelling core mints.

  Verbs are keywords here (`:source`, `:sink`, `:exists`, `:delete`, `:meta`).
  An action is the serde (JSON) shape of `ikigai_core::ActionSpec`, with string
  keys, as the vectors carry it: `\"verb\"` (`\"Source\"`), `\"summary\"`,
  `\"inputs\"`, `\"outputs\"`, `\"requires\"`, and the v2 fields.

  **BLAKE3.** The JDK has none. On the JVM the digest is Apache Commons Codec's
  `org.apache.commons.codec.digest.Blake3`, a dependency in `deps.edn`, found
  by name at run time so this namespace still loads where it is absent.
  babashka cannot load a Java library it was not built with, and neither
  babashka nor any pod in the babashka pod registry carries BLAKE3 (the
  `buddy` pod's buddy-core predates its BLAKE3), so under babashka
  `contract-id` and `contract-iri` refuse with an `ex-info` naming the JVM
  path (`:ikigai.contract/error :no-blake3`). Everything else here, match
  IRIs and both parsers included, runs on both.

  Proved by `test/vectors/contract_vectors.json` (`contracts`, `matches` and
  `parses`)."
  (:require [clojure.string :as str]
            [ikigai.iri :as iri]
            [ikigai.postcard :as pc]
            [ikigai.wire :as wire]))

(def match-prefix
  "The prefix of every match IRI."
  "urn:ikigai:match:")

(def contract-prefix
  "The prefix of every contract IRI."
  "urn:ikigai:contract:")

(def contract-form
  "The domain tag that opens the v1 canonical form."
  "ikigai:contract:v1")

(def contract-form-v2
  "The domain tag of the v2 canonical form, chosen by content."
  "ikigai:contract:v2")

;; ---------------------------------------------------------------------------
;; BLAKE3
;; ---------------------------------------------------------------------------

(def ^:private blake3-class
  (delay (try (Class/forName "org.apache.commons.codec.digest.Blake3")
              (catch ClassNotFoundException _ nil))))

(defn digest-available?
  "True when this runtime can compute a contract digest: on the JVM with
  Apache Commons Codec on the classpath; never under babashka."
  []
  (some? @blake3-class))

(def no-blake3-message
  "Why a contract digest cannot be computed here."
  (str "a contract digest needs BLAKE3, and this runtime has none: babashka carries no"
       " BLAKE3 and cannot load the Java library that does. Compute contract IRIs on the"
       " JVM (`clojure -M` or `clojure -X` with this library's deps.edn, which brings"
       " org.apache.commons.codec.digest.Blake3)"))

(defn blake3
  "The 32-byte BLAKE3 digest of the byte array `bs`, or a refusal naming the
  JVM path when this runtime has no BLAKE3 (see the namespace docstring).

    (ikigai.test-util/->hex (blake3 (byte-array 0)))
    ;=> \"af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262\" ; on the JVM"
  ^bytes [^bytes bs]
  (if-let [cls @blake3-class]
    (let [method (.getMethod ^Class cls "hash" (into-array Class [(class (byte-array 0))]))]
      (.invoke ^java.lang.reflect.Method method nil (object-array [bs])))
    (throw (ex-info no-blake3-message {:ikigai.contract/error :no-blake3}))))

;; ---------------------------------------------------------------------------
;; The canonical form
;; ---------------------------------------------------------------------------

(defn- u64-le ^bytes [n]
  (byte-array (map #(unchecked-byte (bit-and (bit-shift-right (long n) (* 8 %)) 0xff)) (range 8))))

(defn- canon-str ^bytes [s]
  (let [b (pc/utf8-bytes s)]
    (pc/concat-bytes [(u64-le (alength b)) b])))

(defn- canon-option ^bytes [s]
  (if (nil? s) (byte-array [0]) (pc/concat-bytes [(byte-array [1]) (canon-str s)])))

(defn- canon-u8 ^bytes [b] (byte-array [b]))

(defn- sorted-set-of
  "The distinct strings of `values` in UTF-8 byte order."
  [values]
  (sort wire/utf8-order (distinct values)))

(defn- canon-set ^bytes [values]
  (let [items (sorted-set-of values)]
    (pc/concat-bytes (cons (u64-le (count items)) (map canon-str items)))))

(defn- compare-bytes
  "Unsigned lexicographic order of two byte arrays, a shorter prefix first."
  [^bytes a ^bytes b]
  (let [n (min (alength a) (alength b))]
    (loop [i 0]
      (if (= i n)
        (compare (alength a) (alength b))
        (let [x (bit-and (aget a i) 0xff) y (bit-and (aget b i) 0xff)]
          (if (= x y) (recur (inc i)) (compare x y)))))))

(defn- canon-items
  "Core's `Canon::feed_items`: sorted and deduplicated by encoding,
  count-prefixed, each length-prefixed."
  ^bytes [items]
  (let [unique (->> items (sort compare-bytes) (partition-by vec) (map first))]
    (pc/concat-bytes (cons (u64-le (count unique))
                           (mapcat (fn [^bytes b] [(u64-le (alength b)) b]) unique)))))

(defn- compare-key
  "Lexicographic order over two keys: vectors of strings (UTF-8 order),
  booleans, numbers and nested keys."
  [a b]
  (cond
    (and (string? a) (string? b)) (wire/utf8-order a b)
    (and (sequential? a) (sequential? b))
    (loop [[x & xs :as as] (seq a) [y & ys :as bs] (seq b)]
      (cond
        (and (empty? as) (empty? bs)) 0
        (empty? as) -1
        (empty? bs) 1
        :else (let [c (compare-key x y)] (if (zero? c) (recur xs ys) c))))
    :else (compare a b)))

(defn- binding? [arg] (= "binding" (get arg "source" "argument")))

(defn- input-key
  "Core's `input_key`: name, summary, required, is-binding, class, default,
  one_of (sorted, deduplicated). An Option orders nil before a value."
  [arg]
  (let [opt (fn [v] (if (nil? v) [0 ""] [1 v]))]
    [(get arg "name") (get arg "summary" "") (boolean (get arg "required" true)) (binding? arg)
     (opt (get arg "class")) (opt (get arg "default")) (vec (sorted-set-of (get arg "one_of")))]))

(defn- declared-empty-one-of? [arg]
  (and (contains? arg "one_of") (empty? (get arg "one_of"))))

(defn uses-v2?
  "Core's `uses_v2`: whether `action` uses a construct v1 cannot carry (an
  input's `needs`, `list`, `values`, `examples`, `fixed`, `reads_body` or an
  empty `one_of`; the action's `groups`, `arguments`, `output_from`, stated
  `failures` or `existence_sensitive`).

    (uses-v2? {\"verb\" \"Source\" \"existence_sensitive\" true})  ;=> true"
  [action]
  (boolean
   (or (some? (get action "failures"))
       (get action "existence_sensitive")
       (seq (get action "groups"))
       (seq (get action "arguments"))
       (some? (get action "output_from"))
       (some (fn [arg]
               (or (seq (get arg "needs"))
                   (some? (get arg "list"))
                   (seq (get arg "values"))
                   (seq (get arg "examples"))
                   (some? (get arg "fixed"))
                   (get arg "reads_body")
                   (declared-empty-one-of? arg)))
             (get action "inputs")))))

(defn- verb-segment [verb] (name verb))

(defn- action-verb [action]
  (or (wire/parse-verb (get action "verb"))
      (throw (ex-info (str "not a verb: " (pr-str (get action "verb"))) {:action action}))))

(defn- input-v2 ^bytes [arg]
  (let [values (get arg "values")]
    (pc/concat-bytes
     (concat
      [(canon-str (get arg "name")) (canon-str (get arg "summary" ""))
       (canon-u8 (if (get arg "required" true) 1 0)) (canon-u8 (if (binding? arg) 1 0))
       (canon-option (get arg "class")) (canon-option (get arg "default"))
       (canon-set (get arg "one_of")) (canon-set (get arg "needs")) (canon-option (get arg "list"))
       (u64-le (count values))]
      (mapcat (fn [v]
                (let [note (get values v)]
                  [(canon-str v) (canon-str (get note "summary" ""))
                   (canon-set (get note "tags")) (canon-set (get note "needs"))]))
              (sort wire/utf8-order (keys values)))
      [(canon-u8 (if (declared-empty-one-of? arg) 1 0)) (canon-set (get arg "examples"))]))))

(defn- group-v2 ^bytes [group]
  (pc/concat-bytes [(canon-str (get group "kind")) (canon-set (get group "members"))
                    (canon-option (get group "pipe"))]))

(defn- canonical-v2 ^bytes [endpoint-id action verb]
  (let [inputs (get action "inputs")
        arguments (get action "arguments")
        output-from (get action "output_from")
        failures (get action "failures")
        readers (keep #(when (get % "reads_body") (get % "name")) inputs)
        fixed (keep #(when (some? (get % "fixed")) (str (get % "name") (char 0) (get % "fixed"))) inputs)]
    (pc/concat-bytes
     (concat
      [(canon-str contract-form-v2) (canon-str endpoint-id) (canon-str (verb-segment verb))
       (canon-str (get action "summary" ""))
       (canon-items (map input-v2 inputs))
       (canon-set (get action "outputs")) (canon-set (get action "requires"))
       (canon-items (map group-v2 (get action "groups")))]
      (cond
        (empty? arguments) [(canon-u8 0)]
        (contains? arguments "open") [(canon-u8 1) (canon-str (get arguments "open"))]
        :else [(canon-u8 2) (canon-str (get arguments "envelope"))])
      (cond
        (nil? output-from) [(canon-u8 0)]
        (= "origin" output-from) [(canon-u8 2)]
        :else [(canon-u8 1) (canon-str (get output-from "input"))])
      ;; The tails below follow only when present, so every contract without them keeps
      ;; its digest: stated failures, sensitivity, body readers, fixed request inputs.
      (when (some? failures) [(canon-u8 1) (canon-items (map canon-str failures))])
      (when (get action "existence_sensitive") [(canon-u8 2)])
      (when (seq readers) [(canon-u8 3) (canon-set readers)])
      (when (seq fixed) [(canon-u8 4) (canon-set fixed)])))))

(defn- canonical-v1 ^bytes [endpoint-id action verb]
  (let [ks (->> (get action "inputs") (map input-key) (sort compare-key) (partition-by identity)
                (map first))]
    (pc/concat-bytes
     (concat
      [(canon-str contract-form) (canon-str endpoint-id) (canon-str (verb-segment verb))
       (canon-str (get action "summary" "")) (u64-le (count ks))]
      (mapcat (fn [[nm summary required binding [has-class cls] [has-default default] one-of]]
                [(canon-str nm) (canon-str summary) (canon-u8 (if required 1 0))
                 (canon-u8 (if binding 1 0))
                 (canon-option (when (= 1 has-class) cls))
                 (canon-option (when (= 1 has-default) default))
                 (canon-set one-of)])
              ks)
      [(canon-set (get action "outputs")) (canon-set (get action "requires"))]))))

(defn canonical-form
  "The canonical form of `action` on the endpoint `endpoint-id`, as bytes:
  what `contract-id` digests. v2 when `uses-v2?`, else v1."
  ^bytes [endpoint-id action]
  (let [verb (action-verb action)]
    (if (uses-v2? action)
      (canonical-v2 endpoint-id action verb)
      (canonical-v1 endpoint-id action verb))))

(defn contract-id
  "The digest part of an action's contract IRI for the endpoint
  `endpoint-id`: `b3:` and 64 lowercase hex digits of BLAKE3 over the canonical
  form the action's content selects. Declaration order and duplicates in the
  action never move it. JVM only (see the namespace docstring)."
  [endpoint-id action]
  (str "b3:" (str/join (map #(format "%02x" (bit-and % 0xff))
                            (blake3 (canonical-form endpoint-id action))))))

(defn- contract-iri-of [endpoint-id verb digest]
  (str contract-prefix (iri/encode-name endpoint-id) ":" (verb-segment verb) ":" digest))

(defn contract-iri
  "The contract IRI of `action` on the endpoint `endpoint-id`: each
  `contracts` row's `contract`, from its `id` and `action`. JVM only."
  [endpoint-id action]
  (contract-iri-of endpoint-id (action-verb action) (contract-id endpoint-id action)))

(defn match-iri
  "The match IRI of `verb` at the IRI pattern `pattern`, the pattern encoded
  as a name (a template's braces become `%7B` and `%7D`): each `matches` row
  gives one per verb, under `iris`.

    (match-iri :source \"urn:py:echo/{message}\")
    ;=> \"urn:ikigai:match:source:urn:py:echo/%7Bmessage%7D\""
  [verb pattern]
  (str match-prefix (verb-segment verb) ":" (iri/encode-name pattern)))

(defn- parse-verb-segment [s]
  (when (= s (str/lower-case s)) (wire/parse-verb s)))

(defn parse-match-iri
  "Parse a match IRI into `[verb pattern]`, or nil when `iri` is not one:
  each `parses` row's `match`.

    (parse-match-iri \"urn:ikigai:match:source:urn:file:%7Bpath%7D\")
    ;=> [:source \"urn:file:{path}\"]"
  [iri]
  (when (str/starts-with? iri match-prefix)
    (let [rest-of (subs iri (count match-prefix))
          cut (str/index-of rest-of ":")]
      (when cut
        (when-let [verb (parse-verb-segment (subs rest-of 0 cut))]
          (when-let [pattern (iri/decode-segment (subs rest-of (inc cut)))]
            [verb pattern]))))))

(defn parse-contract-iri
  "Parse a contract IRI into `[endpoint-id verb digest]`, or nil when `iri`
  is not one: each `parses` row's `contract`. Parsed from the right, because
  an id may carry colons of its own; the digest comes back in lowercase hex."
  [iri]
  (when (str/starts-with? iri contract-prefix)
    (let [rest-of (subs iri (count contract-prefix))
          cut (str/last-index-of rest-of ":b3:")]
      (when cut
        (let [hex (subs rest-of (+ cut 4))
              head (subs rest-of 0 cut)
              verb-cut (str/last-index-of head ":")]
          (when (and (re-matches #"[0-9a-fA-F]{64}" hex) verb-cut)
            (when-let [verb (parse-verb-segment (subs head (inc verb-cut)))]
              (when-let [id (iri/decode-segment (subs head 0 verb-cut))]
                [id verb (str "b3:" (str/lower-case hex))]))))))))

(defn canonical-iri
  "The canonical spelling of a match or contract IRI, or nil when `iri` is
  neither: each `parses` row's `canonical`.

    (canonical-iri \"urn:ikigai:match:source:urn:%66ile:%7bpath}\")
    ;=> \"urn:ikigai:match:source:urn:file:%7Bpath%7D\""
  [iri]
  (if-let [[verb pattern] (parse-match-iri iri)]
    (match-iri verb pattern)
    (when-let [[id verb digest] (parse-contract-iri iri)]
      (contract-iri-of id verb digest))))

(defn group-segment
  "`InputGroup::node_segment`: a group's segment under its contract IRI,
  `group:{kind}:{m1},{m2}`, each member encoded as a name (with `,` as `%2C`),
  sorted and deduplicated. Also the `path` of a group violation.

    (group-segment {\"kind\" \"exactly-one\" \"members\" [\"b\" \"a\" \"a\"]})
    ;=> \"group:exactly-one:a,b\""
  [group]
  (str "group:" (get group "kind") ":"
       (str/join "," (sorted-set-of (map #(str/replace (iri/encode-name %) "," "%2C")
                                         (get group "members"))))))
