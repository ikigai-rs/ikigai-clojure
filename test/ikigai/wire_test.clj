(ns ikigai.wire-test
  "Wire refusals, version negotiation, and round trips with additional payloads.
  Normative bytes come from wire_vectors_test; typed error behavior is also
  checked against failure_class_vectors.json."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ikigai.postcard :as pc]
            [ikigai.test-util :refer [bs plain thrown-data]]
            [ikigai.vectors :as vectors]
            [ikigai.wire :as wire]))

;; The shared request target is also used in the malformed-payload tests.
(def shared-iri "urn:test:upper")
(def shared-iri-bytes (bs 0x0e "urn:test:upper"))

(defn- upper-request []
  {:verb :source :target shared-iri :args {"in" {:inline (bs "hi")}}})

(defn- message [f] (:ikigai.test-util/message (thrown-data f)))

;; --- version negotiation and the per-peer downgrade ------------------------------

(deftest a-conflict-downgrades-to-endpoint-display
  (is (= "endpoint error: conflict: 1,1 is taken"
         (ex-message (wire/downgrade-error (wire/typed-error :conflict {:message "1,1 is taken"})
                                         7)))))

(deftest a-conflict-stays-typed-for-a-v8-peer
  (let [error (wire/typed-error :conflict {:message "x"})]
    (is (= (plain error)
           (plain (:error (wire/decode-reply
                           (wire/encode-reply {:reply :error-typed :error error} 8))))))))

(deftest accepted-versions-are-the-floor-through-ours
  (is (= [7 9] [wire/min-protocol-version wire/protocol-version]))
  (is (= [9 8 7 nil nil] (map wire/accepted-version [9 8 7 6 10]))))

(deftest a-dialer-redials-only-down-and-only-within-reach
  (is (= :proceed (wire/connect-version 8 8)))
  (is (= 7 (wire/connect-version 8 7)))
  (is (= :proceed (wire/connect-version 7 7)))
  (is (= :refuse (wire/connect-version 8 6)))
  (is (= :refuse (wire/connect-version 8 9)))
  (is (= :refuse (wire/connect-version 7 6))))

(deftest older-alias-and-v8-hellos-beyond-the-shared-cases
  (is (= (vec (bs "IKWH" 0 0 0 7 1)) (vec (wire/encode-hello 7 :alias))))
  (is (= (vec (bs "IKWH" 0 0 0 8 0)) (vec (wire/encode-hello 8)))))

(deftest the-hello-decode-is-prefix-only-and-hint-tolerant
  (is (= {:version 9 :mode :verbatim} (wire/decode-hello (bs (wire/encode-hello 9) "future"))))
  (is (= {:version 9 :mode :verbatim} (wire/decode-hello (bs "IKWH" 0 0 0 9 7))))
  (is (= {:version 9 :mode :alias} (wire/decode-hello (bs "IKWH" 0 0 0 9 1))))
  (is (nil? (wire/decode-hello (wire/encode-call {:call :entries} 9)))))

;; --- round trips over every variant ------------------------------------------------

(def calls
  [{:call :issue :request (upper-request)}
   {:call :is-cached :request (upper-request)}
   {:call :entries}
   {:call :issue-as :request (upper-request) :capability :root}
   {:call :issue-as
    :request {:verb :sink :target "urn:file:notes.txt"
              :args {"ref" {:reference "urn:x"}
                     "cid" {:content (str "b3:" (str/join (repeat 32 "ab")))}
                     "value" {:inline (bs 0x00 0xff)}}}
    :capability #{"urn:cap:fs:write" "urn:cap:demo"}}
   {:call :issue-traced :request (upper-request) :capability :root
    :trace {:trace-id 7 :parent-span nil}}
   {:call :issue-traced :request (upper-request) :capability #{}
    :trace {:trace-id 1099511627776 :parent-span 3}}
   {:call :issue-with :request (upper-request) :capability :root
    :context {:trace {:trace-id 1 :parent-span nil} :retries 2 :time-left-ms 500}}
   {:call :issue-with :request (upper-request) :capability #{"urn:cap:x"}
    :context {:trace nil :retries nil :time-left-ms nil}}])

(defn- rep [b media-type & [opts]] (wire/representation b media-type (or opts {})))

(def replies
  [{:reply :resolved :representation (rep (bs "HI") "text/plain") :cache-status :miss}
   {:reply :resolved
    :representation (rep (bs "{}") "application/json" {:params {"charset" "utf-8"} :expiry :never})
    :cache-status :hit}
   {:reply :resolved :representation (rep (bs "x") "text/plain" {:expiry {:at 1722000000000}})
    :cache-status :uncacheable}
   {:reply :cached :cached true}
   {:reply :cached :cached false}
   {:reply :entries :entries nil}
   {:reply :entries :entries []}
   {:reply :entries :entries [{:pattern shared-iri :endpoint "upper" :origin nil}
                              {:pattern "urn:py:hello" :endpoint "hello" :origin "/tmp/py.sock"}]}
   {:reply :error :message "endpoint error: boom"}
   {:reply :resolved-traced :representation (rep (bs "HI") "text/plain") :cache-status :miss
    :events [{:target shared-iri :thread "ikigai-sched-0" :started nil :ended nil :cache-hit false
              :span 0 :parent nil :capability ["urn:cap:demo"] :notes [["model" "llama3.2:3b"]]}
             {:target "urn:child" :thread "t" :started 1000 :ended 2000 :cache-hit true
              :span 1 :parent 0 :capability nil :notes []}]}])

(def typed-errors
  "Every kind with the payloads the Rust suite's typed-error round trip uses."
  [[:unresolved {:iri "urn:x:y"} "no endpoint resolved for urn:x:y"]
   [:missing-argument {:name "in"} "missing required argument `in`"]
   [:invalid-argument {:name "n" :detail "not a number"} "invalid argument `n`: not a number"]
   [:endpoint {:message "boom"} "endpoint error: boom"]
   [:denied {:message "needs urn:cap:x"} "denied: needs urn:cap:x"]
   [:not-found {:message "no such row"} "not found: no such row"]
   [:timeout {:message "5s elapsed"} "timeout: 5s elapsed"]
   [:unavailable {:message "connection refused"} "unavailable: connection refused"]
   [:conflict {:message "1,1 is taken"} "conflict: 1,1 is taken"]])

(deftest every-call-round-trips
  (doseq [call calls]
    (testing (:call call)
      (is (= (plain call) (plain (wire/decode-call (wire/encode-call call 9))))))))

(deftest every-reply-round-trips
  (doseq [reply (concat replies
                        (for [[kind fields] typed-errors]
                          {:reply :error-typed :error (wire/typed-error kind fields)}))]
    (testing (:reply reply)
      (is (= (plain reply) (plain (wire/decode-reply (wire/encode-reply reply 9))))))))

(deftest only-conflict-changes-for-a-v7-peer
  (doseq [reply replies]
    (is (= (vec (wire/encode-reply reply 9)) (vec (wire/encode-reply reply 7))))))

(deftest an-issue-with-to-a-v8-peer-is-what-it-extends
  (let [[traced bare] (drop 7 calls)]
    (is (= (vec (wire/encode-call {:call :issue-traced :request (:request traced)
                                   :capability :root :trace {:trace-id 1 :parent-span nil}}
                                  9))
           (vec (wire/encode-call traced 8))))
    (is (= (vec (wire/encode-call {:call :issue-as :request (:request bare)
                                   :capability #{"urn:cap:x"}}
                                  9))
           (vec (wire/encode-call bare 8))))))

(deftest arguments-encode-in-btreemap-key-order
  (let [a {:verb :source :target "urn:x" :args (array-map "b" {:inline (bs "2")} "a" {:inline (bs "1")})}
        b {:verb :source :target "urn:x" :args (array-map "a" {:inline (bs "1")} "b" {:inline (bs "2")})}]
    (is (= (vec (wire/encode-call {:call :issue :request a} 9))
           (vec (wire/encode-call {:call :issue :request b} 9))))
    (testing "UTF-8 byte order, not UTF-16: U+FF5E sorts before U+1F600"
      (is (= ["~" "～" "😀"] (sort wire/utf8-order ["😀" "～" "~"]))))))

(deftest every-typed-error-displays-as-the-rust-kernel-does
  (doseq [[kind fields shown] typed-errors
          :let [got (:error (wire/decode-reply
                             (wire/encode-reply {:reply :error-typed
                                                 :error (wire/typed-error kind fields)}
                                                9)))]]
    (is (= shown (wire/display got) (ex-message got)))
    (is (str/ends-with? shown (:message (ex-data got))))
    (is (= (:transient (wire/failure-class got)) (contains? #{:timeout :unavailable} kind)))))

(deftest typed-error-fields-survive-the-wire
  (let [got (ex-data (:error (wire/decode-reply (bs 5 2 1 "n" 1 "x"))))]
    (is (= ["n" "x" "invalid argument `n`: x"] [(:name got) (:detail got) (:message got)])))
  (let [got (ex-data (:error (wire/decode-reply (bs 5 0 5 "urn:x"))))]
    (is (= ["urn:x" "no endpoint resolved for urn:x"] [(:iri got) (:message got)])))
  (let [got (ex-data (:error (wire/decode-reply (bs 5 1 2 "in"))))]
    (is (= ["in" "missing required argument `in`"] [(:name got) (:message got)]))))

;; --- failure modes -----------------------------------------------------------------

(deftest an-unknown-variant-names-the-protocol-version
  (is (str/includes? (message #(wire/decode-call (bs 9))) "v9"))
  (is (str/includes? (message #(wire/decode-reply (bs 0x2a))) "protocol v9"))
  (is (= :protocol (:ikigai.wire/error (thrown-data #(wire/decode-call (bs 9)))))))

(deftest an-unknown-future-error-degrades-to-endpoint-loudly
  (let [got (ex-data (:error (wire/decode-reply (bs 5 0x63 4 0xde 0xad 0xbe 0xef))))]
    (is (= :endpoint (:ikigai.error/kind got)))
    (is (str/includes? (:message got) "unknown wire error variant 99"))))

(deftest a-truncated-payload-is-refused
  (let [encoded (wire/encode-call {:call :issue :request (upper-request)} 9)]
    (is (str/includes? (message #(wire/decode-call (java.util.Arrays/copyOf encoded
                                                                            (dec (alength encoded)))))
                       "truncated"))))

(deftest a-duplicated-argument-key-is-refused
  (let [entry-hi (bs 0x02 "in" 0x01 0x02 "hi")
        entry-yo (bs 0x02 "in" 0x01 0x02 "yo")]
    (is (str/includes? (message #(wire/decode-call (bs 0 0 shared-iri-bytes 2 entry-hi entry-yo)))
                       "duplicate argument key `in`"))
    (is (= {"in" [104 105] "out" [121 111]}
           (update-vals (:args (:request (wire/decode-call
                                          (bs 0 0 shared-iri-bytes 2 entry-hi 3 "out" 1 2 "yo"))))
                        (comp vec :inline))))))

(deftest trailing-bytes-are-refused
  (is (str/includes? (message #(wire/decode-call (bs 2 0))) "trailing")))

(deftest frames-read-back
  (let [out (java.io.ByteArrayOutputStream.)]
    (wire/write-frame out (bs "payload"))
    (wire/write-frame out (bs))
    (let [in (java.io.ByteArrayInputStream. (.toByteArray out))]
      (is (= (vec (bs "payload")) (vec (wire/read-frame in))))
      (is (= [] (vec (wire/read-frame in))))
      (is (nil? (wire/read-frame in)) "a clean end of stream"))))

(deftest a-stream-that-ends-mid-frame-is-refused
  (let [framed (wire/frame (bs "payload"))
        cut (java.util.Arrays/copyOf framed (dec (alength framed)))]
    (is (str/includes? (message #(wire/read-frame (java.io.ByteArrayInputStream. cut))) "mid-frame"))))

(deftest an-oversized-header-is-refused-before-allocating
  (let [header (bs 0x04 0x00 0x00 0x01 0x00)]
    (is (str/includes? (message #(wire/read-frame (java.io.ByteArrayInputStream. header)))
                       "exceeds"))))

(deftest a-representation-media-type-is-canonical
  (let [r (wire/representation (bs) "text/plain;charset=utf-8")]
    (is (= "text/plain" (:media-type r)))
    (is (= "text/plain;charset=utf-8" (wire/media-type r))))
  (is (= "text/plain;boundary=x;charset=utf-8"
         (wire/media-type (wire/representation (bs) "text/plain"
                                               {:params {"charset" "utf-8" "boundary" "x"}})))))

;; --- the typed errors against failure_class_vectors.json --------------------------

(defn- the-error
  "The case's error as this face builds it (not through the wire)."
  [row]
  (let [e (get row "error")
        kind (keyword (get e "kind"))]
    (wire/typed-error
     kind
     (case kind
       :unresolved {:iri (get e "iri")}
       :missing-argument {:name (get e "name")}
       :invalid-argument {:name (get e "name") :detail (get e "detail")}
       :depth-exceeded {:depth (get e "depth") :target (get e "target")}
       :retry-after {:delay-ms (get row "retry_after_ms") :detail (get e "detail")}
       :over-budget {:detail (get e "detail")}
       :unsupported {:detail (get e "detail") :verb (some-> (get e "verb") wire/parse-verb)}
       {:message (get e "message")}))))

(defn- the-payload
  "The case's WireError payload after its tag, written by hand (core's field
  order, strings and varints as postcard has them), never by this face's encoder."
  [row]
  (let [e (get row "error")
        s (fn [k] (bs (count (.getBytes ^String (get e k) "UTF-8")) (get e k)))
        opt (fn [v] (if (nil? v) (bs 0) (bs 1 v)))]
    (case (get e "kind")
      "unresolved" (s "iri")
      "missing-argument" (s "name")
      "invalid-argument" (bs (s "name") (s "detail"))
      "depth-exceeded" (bs (pc/encode-varint (get e "depth")) (s "target"))
      "retry-after" (bs (opt (some-> (get row "retry_after_ms") pc/encode-varint)) (s "detail"))
      "over-budget" (s "detail")
      "unsupported" (bs (opt (some-> (get e "verb") wire/parse-verb wire/verb-index pc/encode-varint))
                        (s "detail"))
      (s "message"))))

(def failure-cases (vectors/rows "failure_class_vectors.json" "cases"))

(deftest the-failure-vectors-have-every-class
  (is (= 19 (count failure-cases)))
  (is (= (set (map #(get % "word") (vectors/rows "failure_class_vectors.json" "classes")))
         (set (map #(get % "class") failure-cases)))))

(deftest every-failure-case-holds
  (doseq [row failure-cases
          :let [error (the-error row)]]
    (testing (get row "name")
      (is (= {:kind (get row "kind")
              :class (get row "class")
              :transient (get row "transient")
              :retry-after-ms (get row "retry_after_ms")
              :wire-tag (get row "wire_tag")
              :http {:status (get-in row ["http" "status"])
                     :retry-after (get-in row ["http" "retry_after"])}}
             (wire/failure-class error)))
      (is (= (get row "display") (wire/display error) (ex-message error)))
      (testing "the wire tag decodes to the same error"
        (let [got (:error (wire/decode-reply (bs 5 (pc/encode-varint (get row "wire_tag"))
                                                 (the-payload row))))]
          (is (= (ex-data error) (ex-data got)))))
      (if (>= (get row "wire_tag") 9)
        (doseq [peer [7 8]]
          (testing (str "a v" peer " peer gets Endpoint(display)")
            (is (= (ex-data (wire/typed-error :endpoint {:message (get row "display")}))
                   (ex-data (:error (wire/decode-reply
                                     (wire/encode-reply {:reply :error-typed :error error} peer))))))))
        (is (= (ex-data error)
               (ex-data (:error (wire/decode-reply
                                 (wire/encode-reply {:reply :error-typed :error error} 9))))))))))

(deftest a-v9-peer-gets-the-typed-tag
  (doseq [row failure-cases
          :when (>= (get row "wire_tag") 9)
          :let [raw (wire/encode-reply {:reply :error-typed :error (the-error row)} 9)]]
    (is (= [5 (get row "wire_tag")] [(aget raw 0) (aget raw 1)]))))

(deftest a-malformed-v9-payload-is-a-protocol-error
  (is (= :protocol (:ikigai.wire/error (thrown-data #(wire/decode-reply (bs 5 13 1 9 1 "x"))))))
  (is (= :protocol (:ikigai.wire/error (thrown-data #(wire/decode-reply (bs 5 10)))))))

(deftest an-unknown-tag-14-still-decodes-as-endpoint
  (let [got (ex-data (:error (wire/decode-reply (bs 5 14 8 "whatever"))))]
    (is (= :endpoint (:ikigai.error/kind got)))
    (is (str/includes? (:message got) "unknown wire error variant 14"))))
