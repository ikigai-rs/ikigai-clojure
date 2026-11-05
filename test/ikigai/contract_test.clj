(ns ikigai.contract-test
  "`ikigai.contract` against `contract_vectors.json` (`contracts`, `matches`,
  `parses`), and `ikigai.check` against `validation_vectors.json`. Every
  expectation comes from core's files; nothing is asserted from this face's own
  output.

  A contract digest needs BLAKE3, which only the JVM has here
  (`ikigai.contract`'s docstring says why): under babashka the digest cases are
  reported as skipped with that reason, and the refusal itself is asserted."
  (:require [clojure.test :refer [deftest is testing]]
            [ikigai.check :as check]
            [ikigai.contract :as contract]
            [ikigai.pending :as pending]
            [ikigai.test-util :refer [->hex thrown-data]]
            [ikigai.vectors :as vectors]
            [ikigai.wire :as wire]))

(def ^:private file "contract_vectors.json")
(def ^:private contracts (vectors/rows file "contracts"))
(def ^:private by-name (into {} (map (juxt #(get % "name") identity)) contracts))

(defn- verb [s] (some-> s wire/parse-verb))

(deftest the-vectors-are-for-the-forms-this-face-computes
  (is (= contract/contract-form (get (vectors/read-json file) "form")))
  (is (= contract/contract-form-v2 (get (vectors/read-json file) "form_v2"))))

(deftest a-contract-is-digested-under-the-form-its-content-chooses
  (let [v2 (filter #(or (.startsWith ^String (get % "name") "v2/")
                        (get-in % ["action" "existence_sensitive"]))
                   contracts)]
    (is (>= (count v2) 12))
    (is (every? #(contract/uses-v2? (get % "action")) v2))
    (is (not (contract/uses-v2? (get-in by-name ["to-upper (the renamed builtin id)" "action"]))))))

(deftest every-contract-iri-is-cores
  (if-not (contract/digest-available?)
    (do (pending/skip! "contract_vectors.json contracts" (count contracts) contract/no-blake3-message)
        (is (= :no-blake3 (:ikigai.contract/error
                           (thrown-data #(contract/contract-iri "x" {"verb" "Source"}))))))
    (do
      (is (= "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"
             (->hex (contract/blake3 (byte-array 0))))
          "the BLAKE3 test vector for the empty input")
      (doseq [{:strs [name id action] :as row} contracts]
        (testing name
          (let [iri (contract/contract-iri id action)]
            (is (= (get row "contract") iri))
            (is (.endsWith ^String iri (str ":" (contract/contract-id id action))))
            (is (= [id (verb (get action "verb")) (contract/contract-id id action)]
                   (contract/parse-contract-iri (get row "contract")))))))
      (testing "declaration order and duplicates never move the digest; the id does"
        (is (= (get-in by-name ["set-order-a" "contract"])
               (get-in by-name ["set-order-b (same digest as a)" "contract"])))
        (is (not= (get-in by-name ["empty-source" "contract"])
                  (get-in by-name ["empty-source-other-id" "contract"])))))))

(deftest every-match-iri-is-cores
  (doseq [{:strs [pattern iris]} (vectors/rows file "matches")
          {v "verb" m "match"} iris]
    (is (= m (contract/match-iri (verb v) pattern)))
    (is (= [(verb v) pattern] (contract/parse-match-iri m)))))

(deftest parsing-agrees-with-core-and-says-back-the-canonical-spelling
  (doseq [{:strs [iri match canonical] :as row} (vectors/rows file "parses")
          :let [want-contract (get row "contract")]]
    (testing iri
      (is (= (when match [(verb (first match)) (second match)]) (contract/parse-match-iri iri)))
      (is (= (when want-contract [(first want-contract) (verb (second want-contract)) (nth want-contract 2)])
             (contract/parse-contract-iri iri)))
      (let [said (contract/canonical-iri iri)]
        (is (= canonical said))
        (when said
          (is (= said (contract/canonical-iri said)) "the canonical spelling is a fixed point"))))))

(deftest a-signed-or-spaced-escape-is-refused
  (doseq [escape ["%+1" "%+F" "%-1" "a%+1b" "% 1" "%_1"]]
    (is (nil? (contract/parse-match-iri (str "urn:ikigai:match:source:" escape))))
    (is (nil? (contract/parse-contract-iri (str "urn:ikigai:contract:" escape ":source:b3:"
                                                (apply str (repeat 32 "ab"))))))))

(deftest a-name-round-trips-through-encode-and-parse
  (doseq [nm ["http://[::1]/x" (str "a" (char 0x85) "b") "a#b?c#d" "100%"
              (str "del" (char 0x7f) " pua" (char 0xe000) " nonchar" (char 0xfdd0) (char 0xffff))]]
    (let [m (contract/match-iri :source nm)]
      (is (= [:source nm] (contract/parse-match-iri m)))
      (is (= m (contract/canonical-iri m))))))

(deftest the-group-segment-is-cores
  (is (= "group:exactly-one:a,b%2Cc" (contract/group-segment {"kind" "exactly-one"
                                                              "members" ["b,c" "a" "a"]}))))

;; --- validation_vectors.json --------------------------------------------------------

(deftest the-check-agrees-with-core
  (let [cases (vectors/rows "validation_vectors.json" "cases")]
    (is (>= (count cases) 39))
    (doseq [{:strs [name action args violations conforms]} cases
            :let [found (check/check action args)]]
      (testing name
        (is (= violations (mapv (fn [v] {"kind" (:kind v) "path" (:path v)}) found)))
        (is (= conforms (empty? found)))))))

(deftest list-splitting-is-cores
  (is (= ["minor" "info"] (check/split-list "comma" "minor, info,,")))
  (is (= ["urn:a" "urn:b" "urn:c"] (check/split-list "comma-or-whitespace" "urn:a, urn:b  urn:c")))
  (is (= ["a" "b"] (check/split-list "newline" "a\r\nb\n\n")))
  (is (= ["a" (str (char 0x1c)) "b"] (check/split-list "whitespace" (str "a " (char 0x1c) " b")))
      "U+001C is not Unicode White_Space"))

(deftest repeats-of-a-list-are-joined
  (is (= [{"graph" "a b"} []]
         (check/join-repeats {"verb" "Source" "inputs" [{"name" "graph" "list" "whitespace"}]}
                             [["graph" "a"] ["graph" "b"]]))))
