(ns ikigai.wire
  "The ikigai IPC wire protocol: calls, replies, the hello, framing, and the
  typed errors that cross it.

  Mirrors `ikigai-wire` (Rust) at protocol version 9. The codec is postcard
  (see `ikigai.postcard`), which does not describe itself, so every type here
  restates a Rust layout field for field; the Rust declaration in
  ikigai-cli `crates/ikigai-wire/src/lib.rs` is the normative source.

  A frame is a u32 big-endian length, then the payload; a frame above 64 MiB
  is refused before anything is allocated. The first frame each way is the
  hello: the bytes `IKWH`, a u32 big-endian version and a one-byte mode,
  deliberately not postcard. A peer accepts a hello from version 7 through 9
  and then speaks the peer's version.

  The values, as Clojure data:

  - a verb is `:source`, `:sink`, `:exists`, `:delete` or `:meta`;
  - an argument is `{:inline bytes}`, `{:reference iri}` or `{:content id}`;
  - a request is `{:verb v :target iri :args {name argument}}`;
  - a capability is `:root` or a set of `urn:cap:` scopes;
  - a representation is `{:bytes b :media-type m :params {k v} :expiry e}`,
    where an expiry is `:always`, `:never` or `{:at millis}`;
  - a call is a map whose `:call` names its variant (`:issue`, `:is-cached`,
    `:entries`, `:issue-as`, `:issue-traced`, `:issue-with`), and a reply one
    whose `:reply` does (`:resolved`, `:cached`, `:entries`, `:error`,
    `:resolved-traced`, `:error-typed`).

  A failure crosses the wire typed (`Reply::ErrorTyped`, tag 5, then the
  error's own tag). Here a typed error is an `ex-info` whose data carries
  `:ikigai.error/kind` (a keyword such as `:denied` or `:conflict`), the bare
  `:message` the wire carries, and the fields of that kind; its `ex-message` is
  the text the Rust kernel's `Display` prints.

  A frame or payload that breaks the protocol is refused with an `ex-info`
  carrying `:ikigai.wire/error :protocol`. The layouts are not recursive, so a
  decoded value's depth is fixed by its type; what a peer controls is size,
  and that is bounded twice: by the frame limit, read before allocating, and by
  `ikigai.postcard/decode-count`, which refuses a count larger than the bytes
  left before looping over it.

  Proved by `test/vectors/failure_class_vectors.json` (the typed errors: tag,
  class, display, HTTP status) and, until a shared wire vector file exists, by
  the byte fixtures in ikigai-python `tests/test_wire.py` and
  `tests/test_hello.py`, copied into `test/ikigai/wire_test.clj`."
  (:require [clojure.string :as str]
            [ikigai.postcard :as pc]))

(set! *warn-on-reflection* true)

(def protocol-version
  "The wire version this face speaks: 9, as ikigai-wire's `PROTOCOL_VERSION`."
  9)

(def min-protocol-version
  "The oldest peer version this face still accepts in a hello: 7."
  7)

(def conflict-since
  "The first wire version that carries `Conflict` as its own error tag: 8."
  8)

(def issue-with-since
  "The first wire version with `Call::IssueWith` and error tags 9 to 13: 9."
  9)

(def max-frame
  "The largest frame payload either side accepts: 64 MiB."
  (* 64 1024 1024))

(def verbs
  "The verbs in their postcard variant order (0 to 4)."
  [:source :sink :exists :delete :meta])

(defn verb-index
  "The postcard variant index of `verb`: `(verb-index :sink)` is 1."
  [verb]
  (or (first (keep-indexed (fn [i v] (when (= v verb) i)) verbs))
      (throw (ex-info (str "not a verb: " (pr-str verb)) {:verb verb}))))

(defn verb-name
  "The verb as the vectors and JSON faces spell it: `(verb-name :sink)` is `\"Sink\"`."
  [verb]
  (str/capitalize (name verb)))

(defn parse-verb
  "The verb keyword for a name spelled any case (`\"Sink\"`, `\"sink\"`), or nil.

    (parse-verb \"Exists\")  ;=> :exists"
  [s]
  (some #(when (= (name %) (str/lower-case (str s))) %) verbs))

(defn- protocol-error
  [message]
  (ex-info message {:ikigai.wire/error :protocol}))

;; ---------------------------------------------------------------------------
;; Typed errors
;; ---------------------------------------------------------------------------

(def ^:private error-kinds
  "Per kind: its wire tag, its failure class, whether it is transient, and the
  Display prefix put before the bare message (nil when the message is already
  the Display's own sentence)."
  {:unresolved {:tag 0 :class "permanent" :prefix nil}
   :missing-argument {:tag 1 :class "permanent" :prefix nil}
   :invalid-argument {:tag 2 :class "permanent" :prefix nil}
   :endpoint {:tag 3 :class "unknown" :prefix "endpoint error: "}
   :denied {:tag 4 :class "denied" :prefix "denied: "}
   :not-found {:tag 5 :class "permanent" :prefix "not found: "}
   :timeout {:tag 6 :class "transient" :prefix "timeout: " :transient true}
   :unavailable {:tag 7 :class "transient" :prefix "unavailable: " :transient true}
   :conflict {:tag 8 :class "permanent" :prefix "conflict: "}
   :depth-exceeded {:tag 9 :class "permanent" :prefix nil}
   :retry-after {:tag 10 :class "retry-after" :prefix nil}
   :over-budget {:tag 11 :class "over-budget" :prefix "over budget: "}
   :refused {:tag 12 :class "refused" :prefix "refused: "}
   :unsupported {:tag 13 :class "permanent" :prefix "unsupported: "}})

(def ^:private v9-kinds #{:depth-exceeded :retry-after :over-budget :refused :unsupported})

(defn- bare-message
  "The message the wire carries for an error of `kind` with `fields`."
  [kind {:keys [iri name detail depth target message]}]
  (case kind
    :unresolved (str "no endpoint resolved for " iri)
    :missing-argument (str "missing required argument `" name "`")
    :invalid-argument (str "invalid argument `" name "`: " detail)
    :depth-exceeded (str "nesting budget exceeded: sub-request for " target
                         " would run at depth " depth)
    (:retry-after :over-budget :unsupported) detail
    message))

(defn- error-data
  "The data of a typed error, whether `error` is the `ex-info` or its data."
  [error]
  (if (instance? Throwable error) (ex-data error) error))

(defn display
  "The text the Rust kernel's `Display` prints for a typed error, word for
  word: each vector case's `display`. `error` is a typed error's `ex-info`, or
  its data.

    (display {:ikigai.error/kind :denied :message \"needs urn:cap:x\"})
    ;=> \"denied: needs urn:cap:x\""
  [error]
  (let [{kind :ikigai.error/kind :as data} (error-data error)
        message (bare-message kind data)]
    (if (= kind :retry-after)
      (if (nil? (:delay-ms data))
        (str "retry later: " (:detail data))
        (str "retry after " (:delay-ms data) " ms: " (:detail data)))
      (str (get-in error-kinds [kind :prefix]) message))))

(defn typed-error
  "Build the typed error of kind `kind` (`:unresolved`, `:missing-argument`,
  `:invalid-argument`, `:endpoint`, `:denied`, `:not-found`, `:timeout`,
  `:unavailable`, `:conflict`, and the v9 kinds `:depth-exceeded`,
  `:retry-after`, `:over-budget`, `:refused`, `:unsupported`) with its
  `fields`, as an `ex-info` whose message is `(display …)` of it. The fields
  per kind: `:iri` (unresolved); `:name` (missing-argument); `:name` and
  `:detail` (invalid-argument); `:depth` and `:target` (depth-exceeded);
  `:delay-ms`, or nil, and `:detail` (retry-after); `:detail` (over-budget);
  `:detail` and `:verb`, or nil (unsupported); `:message` for every other kind.

    (ex-message (typed-error :conflict {:message \"1,1 is taken\"}))
    ;=> \"conflict: 1,1 is taken\"
    (:message (ex-data (typed-error :unresolved {:iri \"urn:x\"})))
    ;=> \"no endpoint resolved for urn:x\""
  [kind fields]
  (when-not (contains? error-kinds kind)
    (throw (ex-info (str "not a typed error kind: " (pr-str kind)) {:kind kind})))
  (let [data (assoc fields
                    :ikigai.error/kind kind
                    :message (bare-message kind fields))]
    (ex-info (display data) data)))

(defn typed-error?
  "True when `e` is a typed error (its data names an `:ikigai.error/kind`)."
  [e]
  (boolean (:ikigai.error/kind (ex-data e))))

(defn- http-status
  [{kind :ikigai.error/kind :as data}]
  (case kind
    :denied 403
    (:not-found :unresolved) 404
    :conflict 409
    (:missing-argument :invalid-argument) 400
    :unavailable 503
    :timeout 504
    :retry-after 429
    :over-budget 422
    :refused 502
    :unsupported (if (:verb data) 405 501)
    500))

(defn retry-after-seconds
  "The `Retry-After` header's delay-seconds for a typed error: the delay
  rounded up and never 0, or nil when the error carries no delay.

    (map #(retry-after-seconds {:ikigai.error/kind :retry-after :delay-ms %}) [nil 0 2 1500])
    ;=> (nil 1 1 2)"
  [error]
  (let [{kind :ikigai.error/kind ms :delay-ms} (error-data error)]
    (when (and (= kind :retry-after) ms)
      (max 1 (quot (+ ms 999) 1000)))))

(defn failure-class
  "How a typed error fails: a map of `:kind`, `:class` (a class word such as
  `transient`), `:transient` (a boolean), `:retry-after-ms`, `:wire-tag` and
  `:http` (`{:status s :retry-after r}`), each equal to the vector case's
  field of the same name.

    (failure-class {:ikigai.error/kind :timeout :message \"slow\"})
    ;=> {:kind \"timeout\", :class \"transient\", :transient true, :retry-after-ms nil,
    ;    :wire-tag 6, :http {:status 504, :retry-after nil}}"
  [error]
  (let [{kind :ikigai.error/kind :as data} (error-data error)
        {:keys [tag class transient]} (error-kinds kind)]
    {:kind (name kind)
     :class class
     :transient (boolean transient)
     :retry-after-ms (when (= kind :retry-after) (:delay-ms data))
     :wire-tag tag
     :http {:status (http-status data) :retry-after (retry-after-seconds data)}}))

(defn downgrade-error
  "The typed error `error` as a peer speaking `peer-version` can receive it.
  A v7 peer has no `Conflict` tag, so it gets `Endpoint(\"conflict: …\")`;
  a v7 or v8 peer has no tags 9 to 13, so it gets `Endpoint` of the error's
  Display. Every other error is unchanged.

    (ex-message (downgrade-error (typed-error :conflict {:message \"x\"}) 7))
    ;=> \"endpoint error: conflict: x\""
  [error peer-version]
  (let [{kind :ikigai.error/kind} (ex-data error)]
    (cond
      (and (= kind :conflict) (< peer-version conflict-since))
      (typed-error :endpoint {:message (str "conflict: " (:message (ex-data error)))})

      (and (v9-kinds kind) (< peer-version issue-with-since))
      (typed-error :endpoint {:message (display error)})

      :else error)))

;; ---------------------------------------------------------------------------
;; Encoding
;; ---------------------------------------------------------------------------

(defn utf8-order
  "Compare two strings in UTF-8 byte order, which is Unicode code point order:
  the order Rust's `BTreeMap<String, _>` iterates in. (Java's `compareTo` is
  UTF-16 order, which differs for characters above U+FFFF.)"
  [^String a ^String b]
  (loop [i 0 j 0]
    (cond
      (and (>= i (.length a)) (>= j (.length b))) 0
      (>= i (.length a)) -1
      (>= j (.length b)) 1
      :else (let [ca (.codePointAt a (int i))
                  cb (.codePointAt b (int j))]
              (if (= ca cb)
                (recur (+ i (Character/charCount ca)) (+ j (Character/charCount cb)))
                (compare ca cb))))))

(defn- sorted-keys [m] (sort utf8-order (keys m)))

(defn- varint [n] (pc/encode-varint n))
(defn- string [s] (pc/encode-string s))

(defn- option-varint [n]
  (pc/encode-option pc/encode-varint n))

(defn- arg-ref [arg]
  (cond
    (contains? arg :reference) [(varint 0) (string (:reference arg))]
    (contains? arg :inline) [(varint 1) (pc/encode-bytes (:inline arg))]
    (contains? arg :content) [(varint 2) (string (:content arg))]
    :else (throw (ex-info (str "not an argument: " (pr-str arg)) {:arg arg}))))

(defn- request-parts [{:keys [verb target args]}]
  (concat [(varint (verb-index verb)) (string target) (varint (count args))]
          (mapcat (fn [k] (cons (string k) (arg-ref (get args k)))) (sorted-keys args))))

(defn- capability-parts [capability]
  (if (= capability :root)
    [(varint 0)]
    (let [scopes (sort utf8-order capability)]
      (concat [(varint 1) (varint (count scopes))] (map string scopes)))))

(defn- expiry-parts [expiry]
  (cond
    (= expiry :always) [(varint 0)]
    (= expiry :never) [(varint 2)]
    (map? expiry) [(varint 1) (varint (:at expiry))]
    :else (throw (ex-info (str "not an expiry: " (pr-str expiry)) {:expiry expiry}))))

(defn- representation-parts [{:keys [media-type params bytes expiry]}]
  (let [params (or params {})]
    (concat [(string media-type) (varint (count params))]
            (mapcat (fn [k] [(string k) (string (get params k))]) (sorted-keys params))
            [(pc/encode-bytes bytes)]
            (expiry-parts (or expiry :always)))))

(def ^:private cache-statuses [:hit :miss :uncacheable])

(defn- cache-status-part [status]
  (varint (or (first (keep-indexed (fn [i s] (when (= s status) i)) cache-statuses))
              (throw (ex-info (str "not a cache status: " (pr-str status)) {:status status})))))

(defn- space-entry-parts [{:keys [pattern endpoint origin]}]
  [(string pattern) (string endpoint) (pc/encode-option pc/encode-string origin)])

(defn- wire-error-parts [error]
  (let [{kind :ikigai.error/kind :as data} (ex-data error)
        tag (varint (get-in error-kinds [kind :tag]))]
    (case kind
      :unresolved [tag (string (:iri data))]
      :missing-argument [tag (string (:name data))]
      :invalid-argument [tag (string (:name data)) (string (:detail data))]
      :depth-exceeded [tag (varint (:depth data)) (string (:target data))]
      :retry-after [tag (option-varint (:delay-ms data)) (string (:detail data))]
      :over-budget [tag (string (:detail data))]
      :unsupported [tag (option-varint (some-> (:verb data) verb-index)) (string (:detail data))]
      [tag (string (:message data))])))

(defn- trace-event-parts [{:keys [target thread started ended cache-hit span parent capability notes]}]
  (concat [(string target) (string thread) (option-varint started) (option-varint ended)
           (pc/encode-bool cache-hit) (varint (or span 0)) (option-varint parent)]
          (if (nil? capability)
            [(byte-array [0])]
            (concat [(byte-array [1]) (varint (count capability))] (map string capability)))
          [(varint (count notes))]
          (mapcat (fn [[k v]] [(string k) (string v)]) notes)))

(defn- trace-parts [{:keys [trace-id parent-span]}]
  [(varint trace-id) (option-varint parent-span)])

(defn- issue-with-parts [{:keys [trace retries time-left-ms]}]
  (concat (if trace (cons (byte-array [1]) (trace-parts trace)) [(byte-array [0])])
          [(option-varint retries) (option-varint time-left-ms)]))

(defn encode-call
  "Encode a call (a map naming its variant and fields, as ikigai-wire's
  `Call`) to its postcard payload. A `:issue-with` call to a peer that spoke a
  version below 9 is first downgraded to the `:issue-traced` or `:issue-as` it
  extends (`downgrade-call`).

    (vec (encode-call {:call :entries} 9))  ;=> [2]"
  ^bytes [call peer-version]
  (let [{:keys [request capability] :as call} (if (and (= :issue-with (:call call))
                                                       (< (or peer-version 0) issue-with-since))
                                                (if-let [trace (get-in call [:context :trace])]
                                                  {:call :issue-traced :request (:request call)
                                                   :capability (:capability call) :trace trace}
                                                  {:call :issue-as :request (:request call)
                                                   :capability (:capability call)})
                                                call)]
    (pc/concat-bytes
     (case (:call call)
       :issue (cons (varint 0) (request-parts request))
       :is-cached (cons (varint 1) (request-parts request))
       :entries [(varint 2)]
       :issue-as (concat [(varint 3)] (request-parts request) (capability-parts capability))
       :issue-traced (concat [(varint 4)] (request-parts request) (capability-parts capability)
                             (trace-parts (:trace call)))
       :issue-with (concat [(varint 5)] (request-parts request) (capability-parts capability)
                           (issue-with-parts (:context call)))
       (throw (ex-info (str "not a call: " (pr-str call)) {:call call}))))))

(defn encode-reply
  "Encode a reply (a representation, a typed error, or another variant of
  ikigai-wire's `Reply`) for a peer that spoke `peer-version`. To a v7 peer a
  conflict goes as `Endpoint(\"conflict: …\")`, the bytes a v7 peer expects
  (`downgrade-error`).

    (vec (encode-reply {:reply :error-typed
                        :error (typed-error :conflict {:message \"x\"})} 9))
    ;=> [5 8 1 120]"
  ^bytes [reply peer-version]
  (let [reply (if (= :error-typed (:reply reply))
                (update reply :error downgrade-error (or peer-version protocol-version))
                reply)]
    (pc/concat-bytes
     (case (:reply reply)
       :resolved (concat [(varint 0)] (representation-parts (:representation reply))
                         [(cache-status-part (:cache-status reply))])
       :cached [(varint 1) (pc/encode-bool (:cached reply))]
       :entries (if (nil? (:entries reply))
                  [(varint 2) (byte-array [0])]
                  (concat [(varint 2) (byte-array [1]) (varint (count (:entries reply)))]
                          (mapcat space-entry-parts (:entries reply))))
       :error [(varint 3) (string (:message reply))]
       :resolved-traced (concat [(varint 4)] (representation-parts (:representation reply))
                                [(cache-status-part (:cache-status reply))
                                 (varint (count (:events reply)))]
                                (mapcat trace-event-parts (:events reply)))
       :error-typed (cons (varint 5) (wire-error-parts (:error reply)))
       (throw (ex-info (str "not a reply: " (pr-str reply)) {:reply reply}))))))

;; ---------------------------------------------------------------------------
;; Decoding: each reader takes the payload and an offset, returns [value next]
;; ---------------------------------------------------------------------------

(defn- version-mismatch [what variant]
  (protocol-error
   (str "unknown " what " variant " variant ": this side speaks ikigai wire protocol v"
        protocol-version " (and down to v" min-protocol-version "), and the required hello"
        " exchange rules out a peer outside that range, so this is a corrupt frame")))

(defn- get-many
  "Read a count, then that many values with `reader`, into a vector."
  [reader bs at]
  (let [[n at] (pc/decode-count bs at)]
    (loop [i 0 at at out (transient [])]
      (if (= i n)
        [(persistent! out) at]
        (let [[v at] (reader bs at)]
          (recur (inc i) at (conj! out v)))))))

(defn- get-arg-ref [bs at]
  (let [[variant at] (pc/decode-varint bs at 32)]
    (case (long variant)
      0 (let [[s at] (pc/decode-string bs at)] [{:reference s} at])
      1 (let [[b at] (pc/decode-bytes bs at)] [{:inline b} at])
      2 (let [[s at] (pc/decode-string bs at)] [{:content s} at])
      (throw (version-mismatch "ArgRef" variant)))))

(defn- get-verb [bs at]
  (let [[variant at] (pc/decode-varint bs at 32)]
    (if (< variant (count verbs))
      [(nth verbs variant) at]
      (throw (version-mismatch "Verb" variant)))))

(defn- get-request [bs at]
  (let [[verb at] (get-verb bs at)
        [target at] (pc/decode-string bs at)
        [n at] (pc/decode-count bs at)]
    (loop [i 0 at at args {}]
      (if (= i n)
        [{:verb verb :target target :args args} at]
        (let [[k at] (pc/decode-string bs at)
              _ (when (contains? args k)
                  ;; A map holds one value per key: keeping either would answer a
                  ;; call nobody sent.
                  (throw (pc/decode-error (str "duplicate argument key `" k "`") {})))
              [v at] (get-arg-ref bs at)]
          (recur (inc i) at (assoc args k v)))))))

(defn- get-capability [bs at]
  (let [[variant at] (pc/decode-varint bs at 32)]
    (case (long variant)
      0 [:root at]
      1 (let [[scopes at] (get-many pc/decode-string bs at)] [(set scopes) at])
      (throw (version-mismatch "Capability" variant)))))

(defn- get-expiry [bs at]
  (let [[variant at] (pc/decode-varint bs at 32)]
    (case (long variant)
      0 [:always at]
      1 (let [[ms at] (pc/decode-varint bs at)] [{:at ms} at])
      2 [:never at]
      (throw (version-mismatch "Expiry" variant)))))

(defn- get-pair [bs at]
  (let [[k at] (pc/decode-string bs at)
        [v at] (pc/decode-string bs at)]
    [[k v] at]))

(defn- get-representation [bs at]
  (let [[media-type at] (pc/decode-string bs at)
        [params at] (get-many get-pair bs at)
        [data at] (pc/decode-bytes bs at)
        [expiry at] (get-expiry bs at)]
    [{:media-type media-type :params (into {} params) :bytes data :expiry expiry} at]))

(defn- get-cache-status [bs at]
  (let [[variant at] (pc/decode-varint bs at 32)]
    (if (< variant (count cache-statuses))
      [(nth cache-statuses variant) at]
      (throw (version-mismatch "CacheStatus" variant)))))

(defn- get-space-entry [bs at]
  (let [[pattern at] (pc/decode-string bs at)
        [endpoint at] (pc/decode-string bs at)
        [origin at] (pc/decode-option pc/decode-string bs at)]
    [{:pattern pattern :endpoint endpoint :origin origin} at]))

(defn- get-message-error [kind bs at]
  (let [[s at] (pc/decode-string bs at)]
    [(typed-error kind {:message s}) at]))

(defn- get-wire-error [^bytes bs at]
  (let [[variant at] (pc/decode-varint bs at 32)]
    (case (long variant)
      0 (let [[iri at] (pc/decode-string bs at)] [(typed-error :unresolved {:iri iri}) at])
      1 (let [[n at] (pc/decode-string bs at)] [(typed-error :missing-argument {:name n}) at])
      2 (let [[n at] (pc/decode-string bs at)
              [d at] (pc/decode-string bs at)]
          [(typed-error :invalid-argument {:name n :detail d}) at])
      3 (get-message-error :endpoint bs at)
      4 (get-message-error :denied bs at)
      5 (get-message-error :not-found bs at)
      6 (get-message-error :timeout bs at)
      7 (get-message-error :unavailable bs at)
      8 (get-message-error :conflict bs at)
      9 (let [[depth at] (pc/decode-varint bs at 32)
              [target at] (pc/decode-string bs at)]
          [(typed-error :depth-exceeded {:depth depth :target target}) at])
      10 (let [[ms at] (pc/decode-option pc/decode-varint bs at)
               [detail at] (pc/decode-string bs at)]
           [(typed-error :retry-after {:delay-ms ms :detail detail}) at])
      11 (let [[detail at] (pc/decode-string bs at)]
           [(typed-error :over-budget {:detail detail}) at])
      12 (get-message-error :refused bs at)
      13 (let [[verb at] (pc/decode-option
                          (fn [bs at]
                            (let [[v at] (pc/decode-varint bs at 32)]
                              (if (< v (count verbs))
                                [(nth verbs v) at]
                                (throw (pc/decode-error (str "unknown verb in Unsupported: " v)
                                                        {})))))
                          bs at)
               [detail at] (pc/decode-string bs at)]
           [(typed-error :unsupported {:detail detail :verb verb}) at])
      ;; A NEWER peer's taxonomy addition: its payload layout is unknowable, so the
      ;; rest of the frame is consumed and the error degrades to Endpoint, loudly named.
      [(typed-error :endpoint
                    {:message (str "unknown wire error variant " variant " from a newer peer"
                                   " (this side speaks wire protocol v" protocol-version
                                   "; the failure's message could not be decoded)")})
       (alength bs)])))

(defn- get-trace-event [bs at]
  (let [[target at] (pc/decode-string bs at)
        [thread at] (pc/decode-string bs at)
        [started at] (pc/decode-option pc/decode-varint bs at)
        [ended at] (pc/decode-option pc/decode-varint bs at)
        [cache-hit at] (pc/decode-bool bs at)
        [span at] (pc/decode-varint bs at)
        [parent at] (pc/decode-option pc/decode-varint bs at)
        [capability at] (pc/decode-option (partial get-many pc/decode-string) bs at)
        [notes at] (get-many get-pair bs at)]
    [{:target target :thread thread :started started :ended ended :cache-hit cache-hit
      :span span :parent parent :capability capability :notes notes}
     at]))

(defn- get-trace [bs at]
  (let [[trace-id at] (pc/decode-varint bs at)
        [parent-span at] (pc/decode-option pc/decode-varint bs at)]
    [{:trace-id trace-id :parent-span parent-span} at]))

(defn- get-issue-with [bs at]
  (let [[trace at] (pc/decode-option get-trace bs at)
        [retries at] (pc/decode-option #(pc/decode-varint %1 %2 32) bs at)
        [time-left at] (pc/decode-option pc/decode-varint bs at)]
    [{:trace trace :retries retries :time-left-ms time-left} at]))

(defn- decode-whole
  "Run `reader` over all of `payload`, refusing trailing bytes, and turn a
  decode refusal into a protocol one naming `what`."
  [what ^bytes payload reader]
  (try
    (let [[value at] (reader payload 0)]
      (when (not= at (alength payload))
        (throw (pc/decode-error (str (- (alength payload) at) " trailing bytes after message")
                                {})))
      value)
    (catch clojure.lang.ExceptionInfo e
      (if (= :decode (:ikigai.wire/error (ex-data e)))
        (throw (ex-info (str "undecodable " what " (wire protocol v" protocol-version "): "
                             (ex-message e))
                        {:ikigai.wire/error :protocol} e))
        (throw e)))))

(defn decode-call
  "Decode a call payload into the map `encode-call` takes. Refuse an unknown
  variant by naming the protocol version, and refuse trailing bytes.

    (decode-call (byte-array [2]))  ;=> {:call :entries}"
  [payload]
  (decode-whole
   "Call" payload
   (fn [bs at]
     (let [[variant at] (pc/decode-varint bs at 32)]
       (case (long variant)
         0 (let [[r at] (get-request bs at)] [{:call :issue :request r} at])
         1 (let [[r at] (get-request bs at)] [{:call :is-cached :request r} at])
         2 [{:call :entries} at]
         3 (let [[r at] (get-request bs at)
                 [c at] (get-capability bs at)]
             [{:call :issue-as :request r :capability c} at])
         4 (let [[r at] (get-request bs at)
                 [c at] (get-capability bs at)
                 [t at] (get-trace bs at)]
             [{:call :issue-traced :request r :capability c :trace t} at])
         5 (let [[r at] (get-request bs at)
                 [c at] (get-capability bs at)
                 [w at] (get-issue-with bs at)]
             [{:call :issue-with :request r :capability c :context w} at])
         (throw (version-mismatch "Call" variant)))))))

(defn decode-reply
  "Decode a reply payload. A typed error comes back as the `ex-info` that
  `typed-error` builds, not thrown: the caller decides whether to throw it.

    (ex-message (:error (decode-reply (byte-array [5 8 1 120]))))  ;=> \"conflict: x\""
  [payload]
  (decode-whole
   "Reply" payload
   (fn [bs at]
     (let [[variant at] (pc/decode-varint bs at 32)]
       (case (long variant)
         0 (let [[r at] (get-representation bs at)
                 [s at] (get-cache-status bs at)]
             [{:reply :resolved :representation r :cache-status s} at])
         1 (let [[b at] (pc/decode-bool bs at)] [{:reply :cached :cached b} at])
         2 (let [[es at] (pc/decode-option (partial get-many get-space-entry) bs at)]
             [{:reply :entries :entries es} at])
         3 (let [[m at] (pc/decode-string bs at)] [{:reply :error :message m} at])
         4 (let [[r at] (get-representation bs at)
                 [s at] (get-cache-status bs at)
                 [es at] (get-many get-trace-event bs at)]
             [{:reply :resolved-traced :representation r :cache-status s :events es} at])
         5 (let [[e at] (get-wire-error bs at)] [{:reply :error-typed :error e} at])
         (throw (version-mismatch "Reply" variant)))))))

;; ---------------------------------------------------------------------------
;; Representations
;; ---------------------------------------------------------------------------

(defn representation
  "A representation of the byte array `bs` as `media-type`, which may carry
  parameters (`text/plain;charset=utf-8`): they are split out into `:params`.
  `opts` may give `:params` (merged over those) and `:expiry` (`:always` by
  default).

    (dissoc (representation (byte-array 0) \"text/plain;charset=utf-8\") :bytes)
    ;=> {:media-type \"text/plain\", :params {\"charset\" \"utf-8\"}, :expiry :always}"
  ([bs media-type] (representation bs media-type {}))
  ([bs media-type {:keys [params expiry]}]
   (let [[base & pieces] (str/split media-type #";")
         parsed (into {} (for [piece pieces
                               :let [[k v] (str/split piece #"=" 2)]]
                           [(str/trim k) (str/trim (or v ""))]))]
     {:media-type (str/trim base)
      :params (merge parsed params)
      :bytes bs
      :expiry (or expiry :always)})))

(defn media-type
  "The canonical media type of `rep`: its base type, then `;k=v` per parameter
  in sorted order.

    (media-type {:media-type \"text/plain\" :params {\"charset\" \"utf-8\"}})
    ;=> \"text/plain;charset=utf-8\""
  [rep]
  (apply str (:media-type rep)
         (for [k (sort (keys (:params rep)))] (str ";" k "=" (get-in rep [:params k])))))

;; ---------------------------------------------------------------------------
;; The hello and framing
;; ---------------------------------------------------------------------------

(def hello-magic
  "The four bytes a hello starts with: `IKWH`."
  "IKWH")

(defn- u32-be ^bytes [n]
  (byte-array (map #(unchecked-byte (bit-and (bit-shift-right n %) 0xff)) [24 16 8 0])))

(defn- read-u32-be [^bytes bs at]
  (reduce (fn [acc i] (bit-or (bit-shift-left acc 8) (bit-and (aget bs (int (+ at i))) 0xff)))
          0 (range 4)))

(defn encode-hello
  "Encode a hello for `version` and `mode` (`:verbatim`, the default, or
  `:alias`, as ikigai-wire's `Hello`): `IKWH`, the version as u32 big-endian,
  the mode byte.

    (vec (encode-hello 7 :alias))  ;=> [73 75 87 72 0 0 0 7 1]"
  (^bytes [version] (encode-hello version :verbatim))
  (^bytes [version mode]
   (pc/concat-bytes [(pc/utf8-bytes hello-magic) (u32-be version)
                     (byte-array [(if (= mode :alias) 1 0)])])))

(defn decode-hello
  "Decode a hello payload and return `{:version v :mode m}`, or nil when the
  payload does not start with `IKWH`. A missing or unknown mode byte reads as
  `:verbatim` (the mode is a hint). Ignore trailing bytes: they are the
  hello's extension point.

    (decode-hello (encode-hello 9))  ;=> {:version 9, :mode :verbatim}"
  [^bytes payload]
  (when (and (>= (alength payload) 8)
             (= hello-magic (String. payload 0 4 "ISO-8859-1")))
    {:version (read-u32-be payload 4)
     :mode (if (and (> (alength payload) 8) (= 1 (aget payload 8))) :alias :verbatim)}))

(defn accepted-version
  "The version a connection proceeds at when a peer's hello offers `offered`,
  or nil when this side refuses it: 7 through 9 are accepted at the peer's
  version.

    (map accepted-version [6 7 9 10])  ;=> (nil 7 9 nil)"
  [offered]
  (when (<= min-protocol-version offered protocol-version) offered))

(defn connect-version
  "What a dialing side does with the version a server `answered` to its
  hello offering `offered`: `:proceed` when the server accepted the offer; the
  older version to redial with when the server is older but within reach;
  `:refuse` otherwise.

    (map #(apply connect-version %) [[9 9] [9 7] [9 6] [8 9]])  ;=> (:proceed 7 :refuse :refuse)"
  [offered answered]
  (cond
    (= answered offered) :proceed
    (and (< answered offered) (= answered (accepted-version answered))) answered
    :else :refuse))

(defn frame
  "The frame of `payload`: its length as u32 big-endian, then the bytes.
  Refuse a payload above 64 MiB.

    (vec (frame (byte-array [2])))  ;=> [0 0 0 1 2]"
  ^bytes [^bytes payload]
  (when (> (alength payload) max-frame)
    (throw (protocol-error (str "message of " (alength payload) " bytes exceeds the "
                                max-frame "-byte limit"))))
  (pc/concat-bytes [(u32-be (alength payload)) payload]))

(defn write-frame
  "Write `payload` to the `java.io.OutputStream` `out` as one frame and flush.
  Refuse a payload above 64 MiB."
  [^java.io.OutputStream out payload]
  (.write out (frame payload))
  (.flush out))

(defn- read-exact
  "Read exactly `n` bytes into `buf` from `start`, or refuse a stream that ends
  first. Returns the count read before a clean end when `eof-ok?` and nothing
  was read."
  [^java.io.InputStream in ^bytes buf start n]
  (loop [got 0]
    (if (= got n)
      got
      (let [r (.read in buf (int (+ start got)) (int (- n got)))]
        (if (neg? r)
          got
          (recur (+ got r)))))))

(defn read-frame
  "Read one frame from the `java.io.InputStream` `in` and return its payload,
  or nil at a clean end of stream before a header. Refuse a header above
  64 MiB before allocating, and a stream that ends mid-frame."
  [^java.io.InputStream in]
  (let [header (byte-array 4)
        got (read-exact in header 0 4)]
    (cond
      (zero? got) nil
      (< got 4) (throw (protocol-error (str "connection closed mid-frame (" got "/4 bytes)")))
      :else
      (let [n (read-u32-be header 0)]
        (when (> n max-frame)
          (throw (protocol-error (str "framed message of " n " bytes exceeds the " max-frame
                                      "-byte limit"))))
        (let [payload (byte-array n)
              got (read-exact in payload 0 n)]
          (when (< got n)
            (throw (protocol-error (str "connection closed mid-frame (" got "/" n " bytes)"))))
          payload)))))
