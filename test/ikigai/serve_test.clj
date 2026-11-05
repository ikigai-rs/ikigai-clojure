(ns ikigai.serve-test
  "`ikigai.serve`: the `doors` vectors (a description's actions and its Meta
  Turtle, byte for byte as core writes them), and the served space's rules,
  answered in-process through `dispatch`. A real host mounting the server is
  `ikigai.serve-interop-test`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ikigai.contract :as contract]
            [ikigai.pending :as pending]
            [ikigai.postcard :as pc]
            [ikigai.serve :as serve]
            [ikigai.vectors :as vectors]
            [ikigai.wire :as wire]))

(def ^:private doors (vectors/rows "contract_vectors.json" "doors"))

(deftest every-door-is-described-as-core-describes-it
  (if-not (contract/digest-available?)
    (pending/skip! "contract_vectors.json doors" (count doors) contract/no-blake3-message)
    (doseq [{:strs [name pattern description actions turtle]} doors]
      (testing name
        (is (= actions (serve/action-specs pattern description)))
        (is (= turtle (serve/description->turtle pattern description)))
        (is (not (str/includes? turtle ":action:")) "no door mints the retired action IRI")))))

;; --- the served space, in-process ------------------------------------------------

(def ^:private hello
  (serve/endpoint "urn:clj:hello"
                  {"id" "hello" "title" "" "summary" "Greet someone" "verbs" ["Source" "Meta"]
                   "outputs" ["text/plain;charset=utf-8"]
                   "inputs" [{"name" "who" "required" true "source" "argument" "summary" "the name to greet"}]}
                  {:source (fn [{:keys [args]}] (str "Hello, " (args "who") "!"))}
                  {:cacheable true}))

(def ^:private store (atom {}))

(def ^:private cell
  (serve/endpoint "urn:clj:cell:{x}"
                  {"id" "clj-cell" "title" "" "summary" "" "verbs" ["Source" "Sink" "Delete" "Meta"]
                   "inputs" [] "outputs" []
                   "actions" [{"verb" "Source" "summary" "" "outputs" ["text/plain"] "requires" ["urn:cap:cell:read"]
                               "inputs" [{"name" "x" "required" true "source" "binding" "summary" ""}]}
                              {"verb" "Sink" "summary" "" "outputs" ["text/plain"] "requires" ["urn:cap:cell:write"]
                               "inputs" [{"name" "x" "required" true "source" "binding" "summary" ""}
                                         {"name" "content" "required" true "source" "argument" "summary" ""}]}
                              {"verb" "Delete" "summary" "" "outputs" ["text/plain"] "requires" ["urn:cap:cell:write"]
                               "inputs" [{"name" "x" "required" true "source" "binding" "summary" ""}]}]}
                  {:source (fn [{:keys [bindings]}]
                             (or (get @store (bindings "x"))
                                 (throw (wire/typed-error :not-found {:message (str "no mark at " (bindings "x"))}))))
                   :sink (fn [{:keys [bindings content]}]
                           (when (str/blank? content)
                             (throw (wire/typed-error :invalid-argument {:name "content" :detail "an empty mark"})))
                           (swap! store assoc (bindings "x") (str/trim content)) nil)
                   :delete (fn [{:keys [bindings]}] (swap! store dissoc (bindings "x")) nil)}))

(def ^:private space (serve/space [hello cell]))

(defn- call [verb target args & [capability]]
  (let [req {:verb verb :target target
             :args (into {} (for [[k v] args] [k {:inline (pc/utf8-bytes v)}]))}]
    (serve/dispatch space (if capability
                            {:call :issue-as :request req :capability capability}
                            {:call :issue :request req})
                    false)))

(defn- text [reply] (String. ^bytes (get-in reply [:representation :bytes]) "UTF-8"))
(defn- error [reply] (ex-data (:error reply)))

(deftest a-flat-door-answers-and-is-cacheable
  (let [reply (call :source "urn:clj:hello" {"who" "Ada"})]
    (is (= "Hello, Ada!" (text reply)))
    (is (= [:miss :never] [(:cache-status reply) (get-in reply [:representation :expiry])]))
    (is (= "text/plain;charset=utf-8" (wire/media-type (:representation reply)))))
  (is (= [:missing-argument "who"] ((juxt :ikigai.error/kind :name) (error (call :source "urn:clj:hello" {})))))
  (is (= "true" (text (call :exists "urn:clj:hello" {}))) "a flat door exists")
  (is (= :endpoint (:ikigai.error/kind (error (call :sink "urn:clj:hello" {"content" "x"}))))))

(deftest a-family-reads-writes-and-deletes-by-its-bindings
  (reset! store {})
  (is (= :not-found (:ikigai.error/kind (error (call :source "urn:clj:cell:a" {})))))
  (is (= "false" (text (call :exists "urn:clj:cell:a" {}))))
  (let [w (call :sink "urn:clj:cell:a" {"content" " X\n"})]
    (is (= ["ok" :uncacheable] [(text w) (:cache-status w)])))
  (is (= "X" (text (call :source "urn:clj:cell:a" {}))))
  (is (= "true" (text (call :exists "urn:clj:cell:a" {}))))
  (is (= [:invalid-argument "content"]
         ((juxt :ikigai.error/kind :name) (error (call :sink "urn:clj:cell:a" {"content" "  "})))))
  (call :delete "urn:clj:cell:a" {})
  (is (= "false" (text (call :exists "urn:clj:cell:a" {})))))

(deftest a-name-bound-nowhere-is-unresolved
  (is (= {:ikigai.error/kind :unresolved :iri "urn:clj:nope" :message "no endpoint resolved for urn:clj:nope"}
         (error (call :source "urn:clj:nope" {})))))

(deftest the-floor-runs-before-the-handler
  (reset! store {"a" "X"})
  (is (= :denied (:ikigai.error/kind (error (call :sink "urn:clj:cell:a" {"content" "O"} #{"urn:cap:cell:read"})))))
  (is (= "X" (get @store "a")) "the refused write never ran")
  (is (= "X" (text (call :source "urn:clj:cell:a" {} #{"urn:cap:cell:read"}))))
  (is (= "capability does not grant `urn:cap:cell:read` (declared by `urn:clj:cell:a`)"
         (:message (error (call :source "urn:clj:cell:a" {} #{})))))
  (is (= :resolved (:reply (call :meta "urn:clj:cell:a" {"as" "application/json"} #{}))) "Meta is never gated"))

(deftest meta-answers-json-text-and-the-alias
  (is (= "hello" (get (json/read-str (text (call :meta "urn:clj:hello" {"as" "application/json"}))) "id")))
  (is (str/starts-with? (text (call :meta "urn:clj:hello" {"as" "text/plain"})) "hello — \nGreet someone\nverbs: Source, Meta"))
  (is (= "Hello, Bo!" (text (call :source "urn:hello" {"who" "Bo"}))) "an alias mount's stripped name")
  (is (= ["urn:hello" "urn:cell:{x}"]
         (map :pattern (:entries (serve/dispatch space {:call :entries} true)))))
  (is (= ["urn:clj:hello" "urn:clj:cell:{x}"]
         (map :pattern (:entries (serve/dispatch space {:call :entries} false))))))

(deftest a-trace-records-one-span
  (let [reply (serve/dispatch space {:call :issue-traced :capability :root
                                     :trace {:trace-id 1 :parent-span nil}
                                     :request {:verb :source :target "urn:clj:hello"
                                               :args {"who" {:inline (pc/utf8-bytes "x")}}}}
                              false)]
    (is (= :resolved-traced (:reply reply)))
    (is (= ["urn:clj:hello"] (map :target (:events reply))))))

(deftest two-doors-with-one-pattern-are-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two endpoints answer urn:clj:hello"
                        (serve/space [hello hello (assoc hello :description {"id" "other"})])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no handler"
                        (serve/endpoint "urn:x" {"id" "x" "verbs" ["Source"] "inputs" [] "outputs" []} {}))))
