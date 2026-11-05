(ns ikigai.interop-test
  "The client against a real `ikigai` kernel (ROADMAP.md, step 4): read a
  built-in, write a file and read it back, see the cache and a trace, and
  receive the kernel's typed errors as `ex-info` with their
  `:ikigai.error/kind`.

  Skipped, with the reason, when no `ikigai` is on PATH; a failure under the
  runner's `:require-interop true` (CI's interop job). One kernel serves the
  whole namespace, in a scratch config home and file root."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [ikigai.client :as client]
            [ikigai.interop :as interop]
            [ikigai.test-util :refer [thrown-data]]
            [ikigai.wire :as wire]))

(def ^:dynamic *kernel* "The kernel this namespace's tests drive, or nil." nil)

(use-fixtures :once
  (fn [run]
    (if-let [ikigai (interop/require-binary! "ikigai.interop-test (client against a kernel)" 7)]
      (let [kernel (interop/start-kernel ikigai)]
        (try (binding [*kernel* kernel] (run))
             (finally (interop/stop-kernel kernel))))
      nil)))

(defn- on-kernel
  "Call `f` with a connection to this namespace's kernel, when there is one."
  [f]
  (when *kernel*
    (client/with-connection [k (client/connect (:socket *kernel*))] (f k))))

(deftest the-client-drives-the-rust-kernel
  (on-kernel
   (fn [k]
     (is (<= wire/min-protocol-version (:server-version k) wire/protocol-version))
     (let [rep (client/source k "urn:iki:fn:toUpper" {"in" "hi"})]
       (is (= "HI" (:text rep)))
       (is (str/starts-with? (wire/media-type rep) "text/plain")))
     (is (some #(= "toUpper" (:endpoint %)) (client/entries k)))
     (let [description (client/describe k "urn:iki:fn:toUpper")]
       (is (= "toUpper" (get description "id")))
       (is (some #(= "in" (get % "name")) (get description "inputs")))))))

(deftest the-client-writes-a-file-and-reads-it-back
  (on-kernel
   (fn [k]
     (is (str/includes? (:text (client/sink k "urn:file:notes.txt" "hello from clojure")) "notes.txt"))
     (is (= "hello from clojure" (slurp (io/file (:files *kernel*) "notes.txt"))))
     (is (= "hello from clojure" (:text (client/source k "urn:file:notes.txt"))))
     (is (true? (client/exists? k "urn:file:notes.txt")))
     (client/delete! k "urn:file:notes.txt")
     (is (false? (client/exists? k "urn:file:notes.txt"))))))

(deftest the-client-sees-the-cache-and-a-trace
  (on-kernel
   (fn [k]
     (is (= :miss (:cache-status (client/source k "urn:iki:fn:toUpper" {"in" "cache me"}))))
     (is (= :hit (:cache-status (client/source k "urn:iki:fn:toUpper" {"in" "cache me"}))))
     (is (client/cached? k "urn:iki:fn:toUpper" {"in" "cache me"}))
     (let [[rep events] (client/source-traced k "urn:iki:fn:toUpper" {"in" "hi"})]
       (is (= "HI" (:text rep)))
       (is (some #(= "urn:iki:fn:toUpper" (:target %)) events))))))

(deftest the-kernels-errors-arrive-typed
  (on-kernel
   (fn [k]
     (let [e (thrown-data #(client/source k "urn:iki:fn:nope"))]
       (is (= :unresolved (:ikigai.error/kind e)))
       (is (= "urn:iki:fn:nope" (:iri e)))
       (is (= "no endpoint resolved for urn:iki:fn:nope" (:ikigai.test-util/message e)))
       (is (false? (:transient (wire/failure-class e)))))
     (let [e (thrown-data #(client/source k "urn:iki:fn:toUpper"))]
       (is (= [:missing-argument "in"] ((juxt :ikigai.error/kind :name) e)))))))

(deftest the-alias-canonicalizes-before-the-name-is-observed
  (on-kernel
   (fn [k]
     (is (= "HI" (:text (client/source k "urn:fn:toUpper" {"in" "hi"}))))
     (is (= "urn:iki:fn:nope" (:iri (thrown-data #(client/source k "urn:fn:nope"))))))))

(deftest a-capability-is-clamped-and-a-denial-is-typed
  (when *kernel*
    (client/with-connection [k (client/connect (:socket *kernel*) {:capability #{"urn:cap:nothing"}})]
      (let [e (thrown-data #(client/sink k "urn:file:a.txt" "z"))]
        (is (= :denied (:ikigai.error/kind e)))
        (is (str/includes? (:message e) "capability does not grant `urn:cap:fs:write:*`")))
      (is (= "A" (:text (client/source k "urn:iki:fn:toUpper" {"in" "a"})))
          "a built-in that declares nothing still answers"))))

(deftest a-closed-connection-is-final
  (on-kernel
   (fn [k]
     (client/close k)
     (is (= :connection-lost (:ikigai.wire/error (thrown-data #(client/source k "urn:iki:fn:toUpper" {"in" "x"}))))))))
