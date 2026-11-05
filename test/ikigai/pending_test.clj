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

(deftest contract-vectors
  (let [f "contract_vectors.json"]
    (section! f "contracts" '[ikigai.contract/contract-id ikigai.contract/contract-iri])
    (section! f "matches" '[ikigai.contract/match-iri])
    (section! f "parses" '[ikigai.contract/parse-match-iri
                           ikigai.contract/parse-contract-iri
                           ikigai.contract/canonical-iri])
    (section! f "doors" '[ikigai.serve/action-specs ikigai.serve/description->turtle])))

(deftest validation-vectors
  (section! "validation_vectors.json" "cases" '[ikigai.check/check]))
