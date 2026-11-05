(ns ikigai.pending-test
  "One pending case per vector case, by vector file: the face's progress bar.

  Each test below names, per section, the functions that must satisfy its
  cases. While those functions are stubs, every case is reported as pending
  (`bb test` and `clojure -X:test` print the count per file). When a function
  is implemented, its section's test fails and says so: replace that call to
  `pending/cases!` with assertions over the same rows, as ikigai-python's
  tests assert them.

  `contract_cases.json` has no pending cases: it is the INPUT core computed
  `contract_vectors.json` from, so its cases are counted there.
  `failure_class_vectors.json`'s `classes` and the scalar fields are checked by
  shape in `ikigai.vectors-test`."
  (:require [clojure.test :refer [deftest]]
            [ikigai.pending :as pending]
            [ikigai.vectors :as vectors]))

(defn- section!
  [vector-file section fn-syms]
  (pending/cases! vector-file section fn-syms (vectors/rows vector-file section)))

(deftest auth-vectors
  (let [f "auth_vectors.json"]
    (section! f "basic" '[ikigai.auth/basic])
    (section! f "basic_refusals" '[ikigai.auth/basic])
    (section! f "bearer" '[ikigai.auth/bearer])
    (section! f "bearer_refusals" '[ikigai.auth/bearer])
    (section! f "scheme" '[ikigai.auth/scheme])
    (section! f "base64" '[ikigai.base64/encode ikigai.base64/decode])
    (section! f "base64_refusals" '[ikigai.base64/decode])))

(deftest capability-vectors
  (let [f "capability_vectors.json"]
    (section! f "deny_scopes" '[ikigai.capability/deny-scope?])
    (section! f "coverage" '[ikigai.capability/allows? ikigai.capability/satisfies?])
    (section! f "principals" '[ikigai.capability/principal])
    (section! f "floors" '[ikigai.capability/required-scopes])
    (section! f "denials" '[ikigai.capability/refusal])))

(deftest contract-vectors
  (let [f "contract_vectors.json"]
    (section! f "contracts" '[ikigai.contract/contract-id ikigai.contract/contract-iri])
    (section! f "matches" '[ikigai.contract/match-iri])
    (section! f "parses" '[ikigai.contract/parse-match-iri
                           ikigai.contract/parse-contract-iri
                           ikigai.contract/canonical-iri])
    (section! f "doors" '[ikigai.serve/action-specs ikigai.serve/description->turtle])))

(deftest failure-class-vectors
  (section! "failure_class_vectors.json" "cases"
            '[ikigai.wire/typed-error ikigai.wire/display ikigai.wire/failure-class]))

(deftest html-vectors
  (let [f "html_vectors.json"]
    (section! f "escape" '[ikigai.html/html-text ikigai.html/attr])
    (section! f "url_segment" '[ikigai.html/url-segment])
    (section! f "percent_decode" '[ikigai.html/percent-decode ikigai.html/percent-decode-bytes])
    (section! f "form_pairs" '[ikigai.html/form-pairs])
    (section! f "safe_href" '[ikigai.html/safe-href])
    (section! f "json_attr" '[ikigai.html/json-attr])))

(deftest validation-vectors
  (section! "validation_vectors.json" "cases" '[ikigai.check/check]))

(deftest ttt-template-cases
  (pending/cases! "ttt_template_cases.txt" "cases"
                  '[ikigai.example.tictactoe-template/fill]
                  (map-indexed (fn [i c] (assoc c "name" (str (name (:kind c)) "[" i "]")))
                               (vectors/template-cases))))
