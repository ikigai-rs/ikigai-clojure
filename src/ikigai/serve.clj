(ns ikigai.serve
  "Serve Clojure functions as ikigai resources over a Unix socket, so that a
  Rust host can mount them (`ikigai --mount urn:clj:=/tmp/clj.sock`) or
  override a name with them (`--override urn:x:=/tmp/clj.sock`).

  An endpoint is an IRI pattern (an exact IRI, or a URI template naming a
  family), a description, and a function per verb it answers. The description
  is the serde (JSON) shape of `ikigai_core::Description`, with string keys,
  as the vectors carry it: `\"id\"`, `\"title\"`, `\"summary\"`, `\"verbs\"`
  (`[\"Source\" \"Meta\"]`), `\"inputs\"`, `\"outputs\"`, `\"requires\"`, and
  `\"actions\"` (one per verb) for a door whose verbs differ. It is not
  decoration: the host routes named arguments by it, the catalog lists it, the
  floor enforces the capabilities it declares (`ikigai.capability`), the
  pre-flight check runs its contract (`ikigai.check`), and its Meta face
  answers in Turtle, JSON and text. `ikigai.explore/defresource` builds one from
  a short form.

  A handler is a function of one map: `:verb`, `:target` (the IRI called),
  `:bindings` (what a template's variables captured, by name), `:args` (each
  named argument as a string, or a byte array when it is not UTF-8),
  `:content` (the `content` argument, the piped value) and `:capability`. It
  returns a string (sent as the action's first output type, `text/plain` by
  default), a byte array, a representation map (`ikigai.wire/representation`),
  nil from a mutating verb (sent as `ok`), or a boolean from Exists; or it
  throws a typed error (`ikigai.wire/typed-error`), which crosses the wire as
  itself. Any other exception crosses as an Endpoint error with its message.

  The served rules are ikigai-python's (`serve.py`, `Space`), which are a Rust
  kernel's: the first declared door whose pattern matches wins; a name bound
  nowhere is Unresolved; the floor runs before anything is invoked (Meta is
  never gated; an existence-sensitive door refused answers as a name bound
  nowhere); bindings come from the name, never from an argument; a missing
  required argument and an enforced check violation are refused before the
  handler runs; a flat door's Exists is true, a family's is whether Source
  succeeds. A served answer is a MISS when cacheable (the HOST caches it by
  its expiry) and UNCACHEABLE otherwise; a write's answer is never cacheable.

  Access: the socket is created `0600` in a directory this process makes
  `0700` when it creates it, so only its user can connect. Unlike
  ikigai-python, which also reads the peer's uid from the socket, the JDK's
  peer-credential option is not available here (neither on babashka nor on
  JDK 21 for macOS), so the file mode is the whole of the access check.

  On babashka the Meta Turtle face, which names each action by its
  content-addressed contract IRI, needs BLAKE3 and so is refused with
  `ikigai.contract`'s sentence; JSON and text Meta, and everything else, serve
  on both runtimes.

  Proved by `test/vectors/contract_vectors.json` (`doors`: a description's
  actions with their match and contract IRIs, and its Meta Turtle, byte for
  byte), by `capability_vectors.json` through the floor, and by a real host
  mounting the server (`test/ikigai/serve_interop_test.clj`)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ikigai.capability :as cap]
            [ikigai.check :as check]
            [ikigai.contract :as contract]
            [ikigai.iri :as iri]
            [ikigai.postcard :as pc]
            [ikigai.template :as template]
            [ikigai.wire :as wire])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel SocketChannel)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)))

(set! *warn-on-reflection* true)

(def vocab-ns
  "The ikigai vocabulary's namespace, the `ik:` prefix."
  "https://ikigai-rs.dev/ns#")

;; ---------------------------------------------------------------------------
;; The description's faces: actions, Turtle, text
;; ---------------------------------------------------------------------------

(defn action-specs
  "The actions of `description` at `pattern`, one per non-Meta verb, each
  with its `match` and `contract` IRIs: each `doors` row's `actions`, as
  `{\"verb\" v \"match\" m \"contract\" c}`. The contract IRI needs BLAKE3
  (JVM only)."
  [pattern description]
  (vec (for [spec (cap/description-actions description)
             :let [verb (wire/parse-verb (get spec "verb"))]]
         {"verb" (get spec "verb")
          "match" (contract/match-iri verb pattern)
          "contract" (contract/contract-iri (get description "id") spec)})))

(defn- lit [^String s]
  (str "\"" (-> s
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\"")
                (str/replace "\n" "\\n")
                (str/replace "\r" "\\r")
                (str/replace "\t" "\\t"))
       "\""))

(defn- iri-term
  "Core's `iri_term`: escape, then parse; a value that is still no IRI is a
  literal of the original string."
  [s]
  (let [escaped (iri/escape-iri-fragment s)]
    (if (iri/iri? escaped) (str "<" escaped ">") (lit s))))

(defn- cap-term [^String scope]
  (if (some #(str/starts-with? scope %) ["urn:" "http://" "https://"]) (iri-term scope) (lit scope)))

(defn- failure-term [word]
  (str "ik:" (str/join (map str/capitalize (str/split word #"-"))) "Failure"))

(def ^:private skos "http://www.w3.org/2004/02/skos/core#")

(defn- value-node-iri [owner nm value]
  (str owner ":input:" (iri/encode-name nm) ":value:" (iri/encode-name value)))

(defn- sorted-keys [m] (sort wire/utf8-order (keys m)))

(defn- input-predicates [owner arg]
  (str/join
   (concat
    [(str "ik:inputName " (lit (get arg "name")) " ;\n    ik:source " (lit (get arg "source"))
          " ;\n    ik:required " (if (get arg "required") "true" "false"))]
    (when (seq (get arg "summary")) [(str " ;\n    ik:summary " (lit (get arg "summary")))])
    (when (some? (get arg "class")) [(str " ;\n    ik:class " (iri-term (get arg "class")))])
    (when (some? (get arg "default")) [(str " ;\n    ik:default " (lit (get arg "default")))])
    (for [v (get arg "one_of")] (str " ;\n    ik:oneOf " (lit v)))
    (when (and (contains? arg "one_of") (empty? (get arg "one_of"))) [" ;\n    ik:enumerated true"])
    (for [e (get arg "examples")] (str " ;\n    ik:example " (lit e)))
    (when (some? (get arg "list")) [(str " ;\n    ik:listSeparator " (lit (get arg "list")))])
    (for [n (get arg "needs")] (str " ;\n    ik:needs <" owner ":input:" (iri/encode-name n) ">"))
    (when (some? (get arg "fixed")) [(str " ;\n    ik:fixed " (lit (get arg "fixed")))])
    (when (get arg "reads_body") [" ;\n    ik:readsBody true"])
    (for [v (sorted-keys (get arg "values"))]
      (str " ;\n    ik:valueNote <" (value-node-iri owner (get arg "name") v) ">")))))

(defn- value-nodes [owner arg]
  (for [v (sorted-keys (get arg "values"))
        :let [note (get-in arg ["values" v])
              preds (concat [(str "a <" skos "Concept>") (str "<" skos "notation> " (lit v))]
                            (when (seq (get note "summary"))
                              [(str "<" skos "definition> " (lit (get note "summary")))])
                            (for [t (get note "tags")] (str "ik:tag " (lit t)))
                            (for [n (get note "needs")]
                              (str "ik:needs <" owner ":input:" (iri/encode-name n) ">")))]]
    (str "<" (value-node-iri owner (get arg "name") v) "> " (str/join " ;\n    " preds) " .")))

(defn- shape-predicates
  "The argument set, the output origin and the groups of `source` (a
  description or an action), in core's order: `[predicates extra-nodes]`."
  [owner source]
  (let [arguments (get source "arguments")
        output-from (get source "output_from")
        groups (get source "groups")]
    [(concat
      (cond
        (empty? arguments) nil
        (contains? arguments "open") [(str "ik:openArguments " (lit (get arguments "open")))]
        :else [(str "ik:envelope <" owner ":input:" (iri/encode-name (get arguments "envelope")) ">")])
      (cond
        (nil? output-from) nil
        (= "origin" output-from) ["ik:outputFrom ik:Origin"]
        :else [(str "ik:outputFrom <" owner ":input:" (iri/encode-name (get output-from "input")) ">")])
      (for [g groups] (str "ik:group <" owner ":" (contract/group-segment g) ">")))
     (for [g groups
           :let [preds (concat ["a ik:InputGroup" (str "ik:groupKind " (lit (get g "kind")))]
                               (for [m (get g "members")]
                                 (str "ik:member <" owner ":input:" (iri/encode-name m) ">"))
                               (when (some? (get g "pipe"))
                                 [(str "ik:pipe <" owner ":input:" (iri/encode-name (get g "pipe")) ">")]))]]
       (str "<" owner ":" (contract/group-segment g) "> " (str/join " ;\n    " preds) " ."))]))

(defn description->turtle
  "The Meta Turtle of `description`, byte for byte as core's `to_turtle`
  writes it: each `doors` row's `turtle`. Skolemized node IRIs, no blank
  nodes; each verb's `ik:Action` node is its content-addressed contract IRI
  (so this needs BLAKE3, JVM only). `pattern` is accepted for symmetry with
  `action-specs`; the Turtle names the endpoint by its id."
  [_pattern description]
  (let [id (get description "id")
        endpoint (str "urn:ikigai:endpoint:" (iri/encode-name id))
        [shape shape-nodes] (shape-predicates endpoint description)
        actions (for [spec (cap/description-actions description)]
                  (let [action (contract/contract-iri id spec)
                        [a-shape a-nodes] (shape-predicates action spec)
                        inputs (get spec "inputs")]
                    {:iri action
                     :nodes (concat
                             (mapcat (fn [arg]
                                       (let [node (str action ":input:" (iri/encode-name (get arg "name")))]
                                         (cons (str "<" node "> " (input-predicates action arg) " .")
                                               (value-nodes action arg))))
                                     inputs)
                             a-nodes
                             [(str "<" action "> "
                                   (str/join " ;\n    "
                                             (concat ["a ik:Action" (str "ik:verb " (lit (get spec "verb")))]
                                                     (when (seq (get spec "summary"))
                                                       [(str "ik:summary " (lit (get spec "summary")))])
                                                     (for [o (get spec "outputs")] (str "ik:output " (lit o)))
                                                     (for [c (get spec "requires")] (str "ik:requires " (cap-term c)))
                                                     (for [f (get spec "failures")] (str "ik:mayFail " (failure-term f)))
                                                     (when (get spec "existence_sensitive") ["ik:existenceSensitive true"])
                                                     (for [arg inputs]
                                                       (str "ik:input <" action ":input:"
                                                            (iri/encode-name (get arg "name")) ">"))
                                                     a-shape))
                                   " .")])}))
        preds (concat
               ["a ik:Endpoint" (str "ik:id " (lit id))]
               (when (seq (get description "title")) [(str "ik:title " (lit (get description "title")))])
               (when (seq (get description "summary")) [(str "ik:summary " (lit (get description "summary")))])
               [(str "ik:verb " (str/join ", " (map lit (get description "verbs"))))]
               (when (seq (get description "outputs"))
                 [(str "ik:output " (str/join ", " (map lit (get description "outputs"))))])
               (when (seq (get description "requires"))
                 [(str "ik:requires " (str/join ", " (map cap-term (get description "requires"))))])
               (when (get description "existence_sensitive") ["ik:existenceSensitive true"])
               (when (seq (get description "failures"))
                 [(str "ik:mayFail " (str/join ", " (map failure-term (get description "failures"))))])
               (for [arg (get description "inputs")]
                 (str "ik:input <" endpoint ":input:" (iri/encode-name (get arg "name")) ">"))
               shape
               (for [a actions] (str "ik:action <" (:iri a) ">")))
        nodes (concat
               (mapcat (fn [arg]
                         (let [node (str endpoint ":input:" (iri/encode-name (get arg "name")))]
                           (cons (str "<" node "> " (input-predicates endpoint arg) " .")
                                 (value-nodes endpoint arg))))
                       (get description "inputs"))
               shape-nodes
               (mapcat :nodes actions))]
    (apply str "@prefix ik: <" vocab-ns "> .\n\n<" endpoint "> " (str/join " ;\n    " preds) " .\n"
           (map #(str "\n" % "\n") nodes))))

(defn description->text
  "The human face of `description`, as core's `to_text` writes it: the id
  and title, the summary, the verbs, the flat inputs and the outputs."
  [description]
  (str (get description "id") " — " (get description "title") "\n"
       (when (seq (get description "summary")) (str (get description "summary") "\n"))
       "verbs: " (str/join ", " (get description "verbs")) "\n"
       (apply str (for [arg (get description "inputs")]
                    (str "  input " (get arg "name") " [" (get arg "source" "argument") "]"
                         (when-not (get arg "required" true) " (optional)") ": "
                         (get arg "summary" "") "\n")))
       (when (seq (get description "outputs"))
         (str "outputs: " (str/join ", " (get description "outputs")) "\n"))))

;; ---------------------------------------------------------------------------
;; Endpoints and the served space
;; ---------------------------------------------------------------------------

(defn- alias-pattern
  "The alias-stripped form an alias mount forwards: `urn:clj:hello` arrives as
  `urn:hello` after `--mount urn:clj:=…` strips its prefix; nil when there is
  no plain first segment to strip."
  [^String pattern]
  (let [parts (str/split pattern #":" 3)]
    (when (and (= 3 (count parts)) (not (str/includes? (second parts) "{")))
      (str "urn:" (nth parts 2)))))

(defn endpoint
  "An endpoint answering at the IRI pattern `pattern`, described by
  `description` (a map as the vectors' `description`), whose verbs are
  answered by `handlers` (a map of verb keyword to function). `opts` may give
  `:cacheable` (true when Source's answer is a pure function of the name and
  arguments, so a host may cache it).

    (endpoint \"urn:clj:hello\"
              {\"id\" \"hello\" \"title\" \"\" \"summary\" \"Greet someone\"
               \"verbs\" [\"Source\" \"Meta\"] \"outputs\" [\"text/plain\"]
               \"inputs\" [{\"name\" \"who\" \"required\" true \"source\" \"argument\"
                           \"summary\" \"the name to greet\"}]}
              {:source (fn [{:keys [args]}] (str \"Hello, \" (args \"who\") \"!\"))})"
  ([pattern description handlers] (endpoint pattern description handlers {}))
  ([pattern description handlers {:keys [cacheable]}]
   (when-not (str/starts-with? pattern "urn:")
     (throw (ex-info (str "an endpoint's pattern is a urn: (" pattern ")") {:pattern pattern})))
   (let [specs (cap/description-actions description)
         declared (set (map #(wire/parse-verb (get % "verb")) specs))
         missing (remove #(contains? handlers %) declared)]
     (when (seq missing)
       (throw (ex-info (str "`" (get description "id") "` declares " (str/join ", " (map wire/verb-name missing))
                            " but has no handler for it")
                       {:missing missing})))
     {:pattern pattern
      :template (template/parse pattern)
      :alias (some-> (alias-pattern pattern) template/parse)
      :description description
      :actions (into {} (map (fn [s] [(wire/parse-verb (get s "verb")) s])) specs)
      :handlers handlers
      :flat (not (contains? description "actions"))
      :cacheable (boolean cacheable)})))

(defn- answered-verbs [door]
  (let [default-exists (or (:flat door) (contains? (:actions door) :source))]
    (concat (filter #(or (contains? (:actions door) %) (and (= % :exists) default-exists))
                    [:source :sink :exists :delete])
            [:meta])))

(defn space
  "The served space over `endpoints`, in declaration order. Refuses two
  endpoints with the same pattern text."
  [endpoints]
  (let [route (fn [routes door tmpl]
                (when-let [held (some (fn [[t d]] (when (and (not (identical? d door))
                                                           (= (:source t) (:source tmpl)))
                                                  d))
                                      routes)]
                  (throw (ex-info (str "two endpoints answer " (:source tmpl) ": "
                                       (get-in held [:description "id"]) " and "
                                       (get-in door [:description "id"]))
                                  {})))
                (conj routes [tmpl door]))]
    {:doors (vec endpoints)
     :declared (reduce (fn [rs d] (route rs d (:template d))) [] endpoints)
     :aliased (reduce (fn [rs d] (if (:alias d) (route rs d (:alias d)) rs)) [] endpoints)}))

(defn lookup
  "`[door bindings]` for `target` in `space`, or nil. An alias mount
  (`alias-first`) tries the alias-stripped patterns first."
  [space target alias-first]
  (some (fn [routes]
          (some (fn [[tmpl door]]
                  (when-let [b (template/match-iri tmpl target)] [door b]))
                routes))
        (if alias-first [(:aliased space) (:declared space)] [(:declared space) (:aliased space)])))

(defn- typed [kind fields] {:reply :error-typed :error (wire/typed-error kind fields)})

(defn- yes-no [value cacheable]
  {:reply :resolved
   :representation (wire/representation (pc/utf8-bytes (str value)) "text/plain"
                                        {:expiry (if cacheable :never :always)})
   :cache-status (if cacheable :miss :uncacheable)})

(defn- refuse-verb [door verb]
  (typed :endpoint {:message (str "verb " (wire/verb-name verb) " is not supported by `"
                                  (get-in door [:description "id"]) "` (it answers "
                                  (str/join ", " (map wire/verb-name (answered-verbs door))) ")")}))

(def ^:private enforced
  "The check's violations a served door refuses, as a Rust kernel's engine does;
  `unknown` and `implausible` are what validation reports, not refusals."
  #{"exactly-one" "at-least-one" "at-most-one" "needs" "envelope" "not-one-of"})

(defn- decode-arg [arg]
  (cond
    (contains? arg :inline) (let [t (pc/utf8-decode (:inline arg))] (if (string? t) t (:inline arg)))
    :else ::by-reference))

(defn- representation-of [door verb spec result]
  (let [mutating (#{:sink :delete} verb)
        result (cond (and (nil? result) mutating) "ok"
                     (and (boolean? result) (= verb :exists)) (str result)
                     :else result)
        media (or (first (get spec "outputs")) "text/plain")
        cacheable (and (:cacheable door) (not mutating))
        rep (cond
              (and (map? result) (contains? result :bytes)) result
              (string? result) (wire/representation (pc/utf8-bytes result) media
                                                    {:expiry (if cacheable :never :always)})
              (bytes? result) (wire/representation result media {:expiry (if cacheable :never :always)})
              :else (throw (ex-info (str "a handler returned " (pr-str (type result))
                                         "; return a string, bytes or a representation")
                                    {})))
        rep (if mutating (assoc rep :expiry :always) rep)]
    {:reply :resolved :representation rep
     :cache-status (if (= :always (:expiry rep)) :uncacheable :miss)}))

(defn- invoke [door verb bindings request capability]
  (let [spec (get-in door [:actions verb])
        inputs (get spec "inputs")
        given (:args request)
        args (reduce (fn [m arg]
                       (let [nm (get arg "name")]
                         (cond
                           (= "binding" (get arg "source")) m
                           (contains? given nm)
                           (let [v (decode-arg (get given nm))]
                             (if (= v ::by-reference)
                               (reduced (typed :invalid-argument
                                               {:name nm :detail "arrived by reference; this peer only takes inline values"}))
                               (assoc m nm v)))
                           (get arg "required" true) (reduced (typed :missing-argument {:name nm}))
                           (some? (get arg "default")) (assoc m nm (get arg "default"))
                           :else m)))
                     {} inputs)]
    (if (:reply args)
      args
      (let [pairs (concat (for [arg inputs :when (= "binding" (get arg "source"))]
                            [(get arg "name") (get bindings (get arg "name") "")])
                          (for [[nm a] given :when (contains? a :inline)]
                            [nm (let [t (pc/utf8-decode (:inline a))] (if (string? t) t (String. ^bytes (:inline a) "UTF-8")))]))
            refused (first (filter #(enforced (:kind %)) (check/check spec pairs)))]
        (if refused
          (let [path (or (:path refused) "")
                subject (if (str/starts-with? path "input:")
                          (second (str/split path #":"))
                          (nth (str/split path #":" 3) 2 path))]
            (typed :invalid-argument {:name subject :detail (:message refused)}))
          (try
            (let [all-args (merge (into {} (for [[nm a] given
                                                 :let [v (decode-arg a)]
                                                 :when (not= v ::by-reference)]
                                             [nm v]))
                                  args)
                  result ((get-in door [:handlers verb])
                          {:verb verb :target (:target request) :bindings bindings
                           :args all-args :content (get all-args "content") :capability capability})]
              (representation-of door verb spec result))
            (catch clojure.lang.ExceptionInfo e
              (if (wire/typed-error? e)
                {:reply :error-typed :error e}
                (typed :endpoint {:message (or (ex-message e) (str e))})))
            (catch Exception e
              (typed :endpoint {:message (or (ex-message e) (.getName (class e)))}))))))))

(defn- default-exists [door bindings request capability]
  (cond
    (:flat door) (yes-no true false)
    (not (contains? (:actions door) :source)) (refuse-verb door :exists)
    :else (let [reply (invoke door :source bindings request capability)]
            (cond
              (= :resolved (:reply reply)) (yes-no true (:cacheable door))
              (and (= :error-typed (:reply reply))
                   (= :not-found (:ikigai.error/kind (ex-data (:error reply)))))
              (yes-no false (:cacheable door))
              :else reply))))

(defn- meta-reply [door request]
  (let [as (some-> (get-in request [:args "as" :inline]) pc/utf8-decode)
        target (if (string? as) as "text/turtle")
        description (:description door)
        rep (fn [^String body media] {:reply :resolved :cache-status :uncacheable
                                      :representation (wire/representation (pc/utf8-bytes body) media)})]
    (try
      (case target
        ("text/turtle" "*/*" "") (rep (description->turtle (:pattern door) description) "text/turtle")
        "text/plain" (rep (description->text description) "text/plain;charset=utf-8")
        "application/json" (rep (json/write-str description) "application/json")
        (typed :endpoint {:message (str "meta renderer does not support target `" target "`")}))
      (catch clojure.lang.ExceptionInfo e
        (typed :endpoint {:message (ex-message e)})))))

(defn resolve-request
  "Route and answer the request `request` (a request map) in `space` under
  `capability`, as a reply map: what the server answers an Issue with."
  [space request capability alias-first]
  (let [target (:target request)
        verb (:verb request)]
    (if-let [[door bindings] (lookup space target alias-first)]
      (let [description (:description door)
            refusal (when-not (= :root capability)
                      (cap/refusal description verb target capability))]
        (cond
          (:unresolved refusal) (typed :unresolved {:iri target})
          (:denied refusal) (typed :denied {:message (:denied refusal)})
          (= verb :meta) (meta-reply door request)
          (contains? (:actions door) verb) (invoke door verb bindings request capability)
          (= verb :exists) (default-exists door bindings request capability)
          :else (refuse-verb door verb)))
      (typed :unresolved {:iri target}))))

(defn entries
  "The catalog of `space`: one row per door, its pattern (alias-stripped when
  `strip-alias`), and its id."
  [space strip-alias]
  (vec (for [d (:doors space)]
         {:pattern (or (when strip-alias (some-> (:alias d) :source)) (:pattern d))
          :endpoint (get-in d [:description "id"])
          :origin nil})))

(defn dispatch
  "Answer one decoded call in `space`, as a reply map. Never throws for a
  well-formed call: a failure comes back as a typed error."
  [space call alias-first]
  (case (:call call)
    :entries {:reply :entries :entries (entries space alias-first)}
    :is-cached {:reply :cached :cached false}
    :issue (resolve-request space (:request call) :root alias-first)
    (:issue-as :issue-with) (if (and (= :issue-with (:call call)) (get-in call [:context :trace]))
                              (recur space (assoc call :call :issue-traced
                                                  :trace (get-in call [:context :trace]))
                                     alias-first)
                              (resolve-request space (:request call) (:capability call) alias-first))
    :issue-traced (let [started (System/currentTimeMillis)
                        reply (resolve-request space (:request call) (:capability call) alias-first)
                        ended (System/currentTimeMillis)]
                    (if (= :resolved (:reply reply))
                      (assoc reply :reply :resolved-traced
                             :events [{:target (get-in call [:request :target])
                                       :thread (.getName (Thread/currentThread))
                                       :started started :ended ended :cache-hit false :span 0
                                       :parent nil
                                       :capability (when-not (= :root (:capability call))
                                                     (vec (sort wire/utf8-order (:capability call))))
                                       :notes []}])
                      reply))
    (typed :endpoint {:message (str "unsupported call " (:call call))})))

;; ---------------------------------------------------------------------------
;; The socket server
;; ---------------------------------------------------------------------------

(defn- handle-connection [space-ref ^SocketChannel ch]
  (with-open [ch ch]
    (let [in (Channels/newInputStream ch)
          out (Channels/newOutputStream ch)
          first-frame (try (wire/read-frame in) (catch Exception _ nil))
          hello (some-> first-frame wire/decode-hello)]
      (when hello
        (let [peer (wire/accepted-version (:version hello))]
          (wire/write-frame out (wire/encode-hello (or peer wire/protocol-version)))
          (when peer
            (let [alias-first (= :alias (:mode hello))]
              (loop []
                (when-let [frame (try (wire/read-frame in) (catch Exception _ nil))]
                  (let [call (try (wire/decode-call frame)
                                  (catch clojure.lang.ExceptionInfo e
                                    ;; An undecodable frame: answer once, loudly, then drop
                                    ;; the connection, as framing after it is unreliable.
                                    (wire/write-frame out (wire/encode-reply
                                                           (typed :endpoint {:message (ex-message e)})
                                                           peer))
                                    nil))]
                    (when call
                      (wire/write-frame out (wire/encode-reply (dispatch @space-ref call alias-first) peer))
                      (recur))))))))))))

(defn serve
  "Serve `endpoints` on the Unix socket at `path`: answer hellos, enforce each
  endpoint's floor, and answer calls on a thread per connection until `stop`
  is called on the returned server. `set-endpoints!` changes what it serves
  while it runs. A leftover socket at `path` is replaced;
  the socket is made `0600`.

    (def server (serve [hello-endpoint] \"/tmp/clj/hello.sock\"))
    ;; ikigai --mount urn:clj:=/tmp/clj/hello.sock -c 'source urn:clj:hello who=Ada'
    (stop server)"
  [endpoints path]
  (let [space-ref (atom (space endpoints))
        file (io/file path)
        parent (.getParentFile (.getAbsoluteFile file))
        _ (when-not (.exists parent)
            (.mkdirs parent)
            (Files/setPosixFilePermissions (.toPath parent) (PosixFilePermissions/fromString "rwx------")))
        _ (.delete file)
        listener (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                   (.bind (UnixDomainSocketAddress/of (str path))))
        _ (Files/setPosixFilePermissions (.toPath file) (PosixFilePermissions/fromString "rw-------"))
        running (atom true)
        accept-loop (future
                      (while @running
                        (let [ch (try (.accept listener) (catch java.io.IOException _ nil))]
                          (if (and ch @running)
                            (future (handle-connection space-ref ch))
                            (some-> ^SocketChannel ch .close)))))]
    {:path (str path) :space space-ref :listener listener :running running :accept-loop accept-loop}))

(defn set-endpoints!
  "Replace what the running `server` serves with `endpoints`: the next call on
  any connection, open or new, is answered by them. A host that mounts the
  socket keeps its mount, so a name added here resolves through it at once.

    (set-endpoints! server [hello-endpoint other-endpoint])"
  [server endpoints]
  (reset! (:space server) (space endpoints))
  server)

(defn endpoints
  "What the running `server` serves now, in declaration order."
  [server]
  (:doors @(:space server)))

(defn stop
  "Stop the `server` `serve` returned and remove its socket."
  [server]
  (reset! (:running server) false)
  (.close ^ServerSocketChannel (:listener server))
  (.delete (io/file (:path server)))
  nil)
