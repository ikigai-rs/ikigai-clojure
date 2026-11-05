(ns ikigai.serve-interop-test
  "A real Rust host mounting endpoints this face serves (ROADMAP.md, step 5):
  `ikigai --mount urn:clj:=<socket>` sources a Clojure endpoint by name and by
  a positional value (which the host routes by the description it reads),
  writes through a family and reads the write back, receives a Clojure typed
  error in the kernel's own words, and describes the door with the same
  contract IRI this face computes.

  Skipped, with the reason, when no `ikigai` is on PATH; a failure under the
  runner's `:require-interop true`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [ikigai.contract :as contract]
            [ikigai.interop :as interop]
            [ikigai.serve :as serve]
            [ikigai.wire :as wire]))

(def ^:private store (atom {}))

(def hello
  "A flat door: one Source with a required argument."
  (serve/endpoint "urn:clj:hello"
                  {"id" "clj-hello" "title" "" "summary" "Greet someone" "verbs" ["Source" "Meta"]
                   "outputs" ["text/plain"]
                   "inputs" [{"name" "who" "required" true "source" "argument" "summary" "the name to greet"}]}
                  {:source (fn [{:keys [args]}] (str "Hello, " (args "who") "!"))}))

(def cell
  "A family: one stored mark per name, read, written and refused."
  (serve/endpoint "urn:clj:cell:{x}"
                  {"id" "clj-cell" "title" "" "summary" "" "verbs" ["Source" "Sink" "Meta"]
                   "inputs" [] "outputs" []
                   "actions" [{"verb" "Source" "summary" "" "outputs" ["text/plain"]
                               "inputs" [{"name" "x" "required" true "source" "binding" "summary" ""}]}
                              {"verb" "Sink" "summary" "" "outputs" ["text/plain"]
                               "inputs" [{"name" "x" "required" true "source" "binding" "summary" ""}
                                         {"name" "content" "required" true "source" "argument" "summary" ""}]}]}
                  {:source (fn [{:keys [bindings]}]
                             (or (get @store (bindings "x"))
                                 (throw (wire/typed-error :not-found {:message (str "no mark at " (bindings "x"))}))))
                   :sink (fn [{:keys [bindings content]}]
                           (if (contains? @store (bindings "x"))
                             (throw (wire/typed-error :conflict {:message (str (bindings "x") " is taken")}))
                             (swap! store assoc (bindings "x") (str/trim content)))
                           nil)}))

(def ^:dynamic *host* "`{:ikigai binary :socket path}` for this namespace's tests, or nil." nil)

(use-fixtures :once
  (fn [run]
    (when-let [ikigai (interop/require-binary! "ikigai.serve-interop-test (a host mounting this face)" 5)]
      (let [dir (interop/scratch-dir)
            sock (str (io/file dir "c.sock"))
            server (serve/serve [hello cell] sock)]
        (try (binding [*host* {:ikigai ikigai :socket sock}] (run))
             (finally (serve/stop server) (interop/delete-tree dir)))))))

(defn- host [& commands]
  (apply interop/run-repl (:ikigai *host*) {:mount (str "urn:clj:=" (:socket *host*))} commands))

(deftest the-host-sources-a-clojure-endpoint
  (when *host*
    (let [{:keys [out exit]} (host "source urn:clj:hello who=Ada")]
      (is (zero? exit) out)
      (is (str/includes? out "Hello, Ada!")))
    (let [{:keys [out exit]} (host "source urn:clj:hello Bo")]
      (is (zero? exit) out)
      (is (str/includes? out "Hello, Bo!") "a positional value, routed by the description"))))

(deftest the-host-writes-through-a-family-and-reads-it-back
  (when *host*
    (reset! store {})
    (let [{:keys [out exit]} (host "sink urn:clj:cell:a X" "source urn:clj:cell:a" "exists urn:clj:cell:a")]
      (is (zero? exit) out)
      (is (str/includes? out "X"))
      (is (str/includes? out "true")))
    (is (= {"a" "X"} @store))))

(deftest a-clojure-typed-error-reaches-the-host-user-in-the-kernels-words
  (when *host*
    (reset! store {"a" "X"})
    (let [{:keys [out exit]} (host "source urn:clj:cell:zz")]
      (is (= 1 exit))
      (is (str/includes? out "not found: no mark at zz") out))
    (let [{:keys [out exit]} (host "sink urn:clj:cell:a O")]
      (is (= 1 exit))
      (is (str/includes? out "conflict: a is taken") out))))

(deftest the-host-describes-the-door-with-this-faces-contract
  (when *host*
    (let [{:keys [out exit]} (host "describe urn:clj:hello")]
      (is (zero? exit) out)
      (is (str/includes? out "ik:id \"clj-hello\""))
      (when (contract/digest-available?)
        (let [[{c "contract"}] (serve/action-specs "urn:clj:hello" (:description hello))]
          (is (str/includes? out (str "<" c "> a ik:Action")) "the host's contract IRI is this face's"))))))

(deftest the-host-lists-the-family
  (when *host*
    (let [{:keys [out exit]} (host "source urn:kernel:catalog")]
      (is (zero? exit))
      (is (str/includes? out "clj-cell")))))
