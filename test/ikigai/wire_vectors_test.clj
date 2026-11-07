(ns ikigai.wire-vectors-test
  "The normative postcard bytes and IPC exchanges shared by the wire's faces.

  Composite types use the codec's private component readers and writers, since
  those types have no standalone public API. JSON adaptation lives only here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ikigai.postcard :as pc]
            [ikigai.test-util :refer [bs plain unhex ->hex]]
            [ikigai.vectors :as vectors]
            [ikigai.wire :as wire]))

(def wire-vectors (vectors/read-json "wire_vectors.json"))

(defn- kebab [s]
  (-> s (str/replace #"([a-z])([A-Z])" "$1-$2") (str/replace "_" "-") str/lower-case keyword))

(defn- fields [v]
  (into {} (map (fn [[k v]] [(kebab k) v])) v))

(defn- byte-value [v]
  (if-let [hex (get v "hex")] (unhex hex) (pc/utf8-bytes (get v "utf8"))))

(declare value)

(defn- variant [v]
  (if (string? v) [v nil] (first v)))

(defn- value
  "Translate the shared JSON's typed value into this face's data model."
  [type v]
  (case type
    ("u32" "u64") (bigint v)
    ("u8" "bool" "string" "Iri" "option<u32>") v
    "bytes" (byte-value v)
    ("Verb" "CacheStatus") (kebab v)
    "ArgRef" (let [[tag payload] (variant v)]
               {(kebab tag) (if (= tag "Inline") (byte-value payload) payload)})
    "Request" (-> (fields v)
                  (update :verb #(value "Verb" %))
                  (update :args #(update-vals % (partial value "ArgRef"))))
    "Capability" (if (= v "root") :root (set (get v "scoped")))
    "ReprType" (fields v)
    "Expiry" (if (string? v) (kebab v) {:at (get v "At")})
    "Representation" (assoc (value "ReprType" (get v "repr_type"))
                            :bytes (byte-value (get v "bytes"))
                            :expiry (value "Expiry" (get v "expiry")))
    ("SpaceEntry" "TraceEvent" "TraceContext") (fields v)
    "IssueWith" (update (fields v) :trace #(when % (value "TraceContext" %)))
    "WireError" (let [[tag payload] (variant v)
                      kind (kebab tag)
                      data (case kind
                             :unresolved {:iri payload}
                             :missing-argument {:name payload}
                             (:invalid-argument :depth-exceeded :retry-after) (fields payload)
                             :unsupported (update (fields payload) :verb #(when % (value "Verb" %)))
                             :over-budget {:detail payload}
                             {:message payload})]
                  (wire/typed-error kind data))
    "Call" (let [[tag payload] (variant v)
                 kind (kebab tag)
                 [request capability context] (when (vector? payload) payload)]
             (case kind
               :entries {:call kind}
               (:issue :is-cached) {:call kind :request (value "Request" payload)}
               (:issue-as :issue-traced :issue-with)
               (cond-> {:call kind :request (value "Request" request)
                        :capability (value "Capability" capability)}
                 (= kind :issue-traced) (assoc :trace (value "TraceContext" context))
                 (= kind :issue-with) (assoc :context (value "IssueWith" context)))))
    "Reply" (let [[tag payload] (variant v)
                  kind (kebab tag)]
              (case kind
                (:resolved :resolved-traced)
                (cond-> {:reply kind :representation (value "Representation" (first payload))
                         :cache-status (value "CacheStatus" (second payload))}
                  (= kind :resolved-traced) (assoc :events (mapv #(value "TraceEvent" %) (nth payload 2))))
                :cached {:reply kind :cached payload}
                :entries {:reply kind :entries (when payload (mapv #(value "SpaceEntry" %) payload))}
                :error {:reply kind :message payload}
                :error-typed {:reply kind :error (value "WireError" payload)}))))

(defn- parts-codec [reader writer]
  [reader (comp pc/concat-bytes writer)])

(def codecs
  {"u32" [#(pc/decode-varint %1 %2 32) pc/encode-varint]
   "u64" [pc/decode-varint pc/encode-varint]
   ;; u8 is a single raw byte in postcard, not a varint.
   "u8" [(fn [b at] [(bit-and (aget ^bytes b at) 0xff) (inc at)]) bs]
   "bool" [pc/decode-bool pc/encode-bool]
   "string" [pc/decode-string pc/encode-string]
   "Iri" [pc/decode-string pc/encode-string]
   "bytes" [pc/decode-bytes pc/encode-bytes]
   "option<u32>" [(partial pc/decode-option #(pc/decode-varint %1 %2 32))
                    (partial pc/encode-option pc/encode-varint)]
   "Verb" [#'wire/get-verb (comp pc/encode-varint wire/verb-index)]
   "ArgRef" (parts-codec #'wire/get-arg-ref #'wire/arg-ref)
   "Request" (parts-codec #'wire/get-request #'wire/request-parts)
   "Capability" (parts-codec #'wire/get-capability #'wire/capability-parts)
   ;; ReprType is the prefix of Representation, before bytes and expiry.
   "ReprType" [(fn [b at]
                 (let [[media-type at] (pc/decode-string b at)
                       [params at] (#'wire/get-many #'wire/get-pair b at)]
                   [{:media-type media-type :params (into {} params)} at]))
               (fn [v]
                 (let [encoded (pc/concat-bytes (#'wire/representation-parts
                                                 (assoc v :bytes (bs) :expiry :always)))]
                   (java.util.Arrays/copyOf encoded (- (alength encoded) 2))))]
   "Expiry" (parts-codec #'wire/get-expiry #'wire/expiry-parts)
   "Representation" (parts-codec #'wire/get-representation #'wire/representation-parts)
   "CacheStatus" [#'wire/get-cache-status #'wire/cache-status-part]
   "SpaceEntry" (parts-codec #'wire/get-space-entry #'wire/space-entry-parts)
   "TraceEvent" (parts-codec #'wire/get-trace-event #'wire/trace-event-parts)
   "TraceContext" (parts-codec #'wire/get-trace #'wire/trace-parts)
   "IssueWith" (parts-codec #'wire/get-issue-with #'wire/issue-with-parts)
   "WireError" (parts-codec #'wire/get-wire-error #'wire/wire-error-parts)
   "Call" [(fn [b at] (is (zero? at)) [(wire/decode-call b) (alength ^bytes b)])
           #(wire/encode-call % (get wire-vectors "protocol_version"))]
   "Reply" [(fn [b at] (is (zero? at)) [(wire/decode-reply b) (alength ^bytes b)])
            #(wire/encode-reply % (get wire-vectors "protocol_version"))]})

(deftest every-postcard-vector-decodes-as-its-type-and-reencodes-exactly
  (let [rows (get wire-vectors "postcard")]
    (is (seq rows))
    (is (= (set (keys codecs)) (set (map #(get % "type") rows))))
    (doseq [row rows]
      (testing (get row "name")
        (let [type (get row "type")
              [decode encode] (get codecs type)
              encoded (unhex (get row "hex"))
              [decoded at] (decode encoded 0)
              expected (value type (get row "value"))]
          (is (= (alength encoded) at) "the typed decoder consumes every byte")
          (is (= (plain expected) (plain decoded)))
          (is (= (get row "hex") (->hex (encode decoded))))
          (is (= (get row "hex") (->hex (encode expected)))))))))

(defn- server-hello [version]
  ;; The server omits the optional mode byte; encode-hello builds the prefix.
  (java.util.Arrays/copyOf (wire/encode-hello version) 8))

(deftest every-hello-payload-and-frame-is-exact
  (doseq [row (get wire-vectors "hello")]
    (testing (get row "name")
      (let [version (get row "version")
            mode (kebab (get row "mode"))
            payload (if (= "server/answer" (get row "name"))
                      (server-hello version)
                      (wire/encode-hello version mode))]
        (is (= {:version version :mode mode} (wire/decode-hello (unhex (get row "payload")))))
        (is (= (get row "payload") (->hex payload)))
        (is (= (get row "framed") (->hex (wire/frame payload))))))))

(deftest every-exchange-rebuilds-both-streams-exactly
  (doseq [row (get wire-vectors "exchanges")]
    (testing (get row "name")
      (let [peer (get row "peer_version")
            call (wire/encode-call (value "Call" (get row "call")) peer)
            reply (wire/encode-reply (wire/decode-reply (unhex (get row "reply_payload"))) peer)]
        (is (= (get row "call_payload") (->hex call)))
        (is (= (plain (wire/decode-call (unhex (get row "call_payload"))))
               (plain (wire/decode-call call))))
        (is (= (get row "reply_payload") (->hex reply)))
        (doseq [[side hello payload] [["client_writes" (wire/encode-hello peer) call]
                                    ["server_writes" (server-hello peer) reply]]]
          (let [out (java.io.ByteArrayOutputStream.)]
            (wire/write-frame out hello)
            (wire/write-frame out payload)
            (is (= (get row side) (->hex (.toByteArray out))))
            (let [in (java.io.ByteArrayInputStream. (unhex (get row side)))]
              (is (= (vec hello) (vec (wire/read-frame in))))
              (is (= (vec payload) (vec (wire/read-frame in))))
              (is (nil? (wire/read-frame in))))))))))
