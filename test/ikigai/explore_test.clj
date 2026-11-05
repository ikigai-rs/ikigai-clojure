(ns ikigai.explore-test
  "`ikigai.explore`, the REPL layer, end to end: a Clojure peer started first,
  a real kernel that mounts it, and the session functions reading, writing,
  looking around, serving Clojure functions, composing them with a built-in,
  and watching a cached answer recompute after a write. This is the guided
  session in `examples/explore.clj`, as assertions.

  Skipped, with the reason, when no `ikigai` is on PATH; a failure under the
  runner's `:require-interop true`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ikigai.explore :as ik]
            [ikigai.interop :as interop]
            [ikigai.wire :as wire]))

(def ^:dynamic *files* "The kernel's file root, or nil when there is no kernel." nil)

(use-fixtures :once
  (fn [run]
    (when-let [ikigai (interop/require-binary! "ikigai.explore-test (the REPL layer)" 6)]
      (let [dir (interop/scratch-dir)
            peer (str (io/file dir "p.sock"))]
        (ik/start-peer! peer)
        (let [kernel (interop/start-kernel ikigai :serve-args ["--mount" (str "urn:clj:=" peer)])]
          (try
            (ik/connect! (:socket kernel))
            (binding [*files* (:files kernel)] (run))
            (finally
              (ik/disconnect!)
              (doseq [iri (ik/served)] (ik/unserve! iri))
              (ik/stop-peer!)
              (interop/stop-kernel kernel)
              (interop/delete-tree dir))))))))

(defn- typed-kind [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:ikigai.error/kind (ex-data e)))))

(deftest reading-and-looking-around
  (when *files*
    (is (= "HI" (ik/source "urn:iki:fn:toUpper" {:in "hi"})))
    (is (= ["urn:iki:fn:toUpper"] (map :pattern (ik/catalog "toUpper"))))
    (is (= "toUpper" (:id (ik/describe "urn:iki:fn:toUpper"))))
    (is (= ["in"] (map :name (:source (ik/args "urn:iki:fn:toUpper")))))
    (is (str/includes? (ik/meta "urn:iki:fn:toUpper") "ik:Endpoint"))
    (is (= "toUpper" (:id (ik/meta "urn:iki:fn:toUpper" :json))))
    (testing "the kernel transrepts a description to JSON-LD, and it is parsed"
      (is (some #(= "urn:ikigai:endpoint:toUpper" (:id %)) (ik/meta "urn:iki:fn:toUpper" :graph))))
    (is (= :unresolved (typed-kind #(ik/source "urn:iki:fn:nope"))))))

(deftest writing-a-file
  (when *files*
    (is (str/includes? (ik/sink "urn:file:hello.txt" "hello from Clojure") "hello.txt"))
    (is (= "hello from Clojure" (slurp (io/file *files* "hello.txt"))))
    (is (= "hello from Clojure" (ik/source "urn:file:hello.txt")))
    (is (true? (ik/exists? "urn:file:hello.txt")))
    (ik/delete! "urn:file:hello.txt")
    (is (false? (ik/exists? "urn:file:hello.txt")))))

(deftest serving-a-clojure-function-to-the-kernel
  (when *files*
    (ik/serve! "urn:clj:greet" {:args ["who"] :summary "Greet someone"}
               (fn [{:keys [who]}] (str "Hello, " who "!")))
    (is (= "Hello, Ada!" (ik/source "urn:clj:greet" {:who "Ada"})) "through the kernel's mount")
    (is (= "Greet someone" (:summary (ik/describe "urn:clj:greet"))))
    (testing "a typed error from Clojure reaches the caller as itself"
      (ik/serve! "urn:clj:note:{id}"
                 {:source (fn [{:keys [id]}]
                            (throw (wire/typed-error :not-found {:message (str "no note " id)})))})
      (is (= :not-found (typed-kind #(ik/source "urn:clj:note:7")))))
    (testing "and it stops again"
      (ik/unserve! "urn:clj:greet")
      (is (= :unresolved (typed-kind #(ik/source "urn:clj:greet" {:who "Ada"})))))))

(deftest composing-a-clojure-resource-with-a-built-in
  (when *files*
    (ik/serve! "urn:clj:motto" (fn [_] "keep it simple"))
    (is (= "KEEP IT SIMPLE" (ik/source "urn:iki:fn:toUpper" {:in {:reference "urn:clj:motto"}})))))

(deftest a-cached-answer-recomputes-after-a-write
  ;; The kernel cuts the answer's thread twice for one write: the Sink cuts it at once, and
  ;; the file root's watcher cuts it again when it sees the file change, a moment later. So
  ;; the read just after a write is always recomputed, and a later one may be too, once:
  ;; `settle` reads until the answer comes from the cache, within a bound.
  (when *files*
    (let [read (fn [] ((juxt :text :cache-status)
                       (ik/source "urn:iki:fn:toUpper" {:in {:reference "urn:file:note.txt"}} {:as :rep})))
          settle (fn [] (loop [n 0]
                          (let [[_ status :as got] (read)]
                            (if (or (= :hit status) (= n 20)) got (do (Thread/sleep 100) (recur (inc n)))))))]
      (ik/sink "urn:file:note.txt" "first")
      (is (= ["FIRST" :miss] (read)))
      (is (= ["FIRST" :hit] (settle)))
      (is (ik/cached? "urn:iki:fn:toUpper" {:in {:reference "urn:file:note.txt"}}))
      (ik/sink "urn:file:note.txt" "second")
      (is (= ["SECOND" :miss] (read)) "the write cut the cached answer's thread")
      (is (= ["SECOND" :hit] (settle))))))

(deftest not-connected-says-what-to-call
  (let [state (ik/kernel)]
    (is state)
    (is (str/includes? (try (#'ik/answer {} :nope) (catch Exception e (ex-message e))) ":as"))))
