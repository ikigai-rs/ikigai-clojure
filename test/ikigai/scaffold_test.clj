(ns ikigai.scaffold-test
  "The skeleton holds together: every namespace loads, every public var says
  what it must do, and the stub exception carries what the pending tests read."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ikigai.scaffold :as scaffold]))

(def namespaces
  "Every namespace of the face and its examples."
  '[ikigai.scaffold ikigai.postcard ikigai.wire ikigai.contract ikigai.capability
    ikigai.check ikigai.template ikigai.html ikigai.auth ikigai.base64 ikigai.iri ikigai.compose ikigai.text
    ikigai.client ikigai.serve
    ikigai.example.tictactoe-store ikigai.example.tictactoe-template])

(deftest every-namespace-loads-and-says-what-it-is
  (doseq [ns-sym namespaces]
    (require ns-sym)
    (is (not (str/blank? (:doc (meta (find-ns ns-sym)))))
        (str ns-sym " has no namespace docstring"))))

(deftest every-public-var-says-what-it-must-do
  (doseq [ns-sym namespaces
          :let [_ (require ns-sym)]
          [sym v] (ns-publics ns-sym)]
    (is (not (str/blank? (:doc (meta v))))
        (str ns-sym "/" sym " has no docstring"))))

(deftest the-stub-exception-names-the-function-and-its-proof
  (let [e (try (scaffold/not-implemented `example "test/vectors/x.json" {:a 1})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "not implemented yet" (ex-message e)))
    (is (= `example (:ikigai/not-implemented (ex-data e))))
    (is (= "test/vectors/x.json" (:ikigai/proved-by (ex-data e))))
    (is (= {:a 1} (:ikigai/args (ex-data e))))
    (is (scaffold/not-implemented? e))
    (is (not (scaffold/not-implemented? (ex-info "other" {}))))))
