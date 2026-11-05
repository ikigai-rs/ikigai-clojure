(ns ikigai.compose-test
  "`ikigai.compose/fill` against the tic-tac-toe template cases
  (`ttt_template_cases.txt`), filled with the arguments and over the resources
  ikigai-tutorial's README names (ikigai-python `tests/test_tictactoe_app.py`:
  `CASE_ARGUMENTS`, `CASE_RESOURCES`, `CASE_VIEWS`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ikigai.compose :as compose]
            [ikigai.vectors :as vectors]
            [ikigai.wire :as wire]))

(def case-arguments {"x" "1" "y" "-2" "message" "it's <b>"})

(def case-resources
  {"urn:t:mark" "<b>\"&'$a{urn:t:secret}"
   "urn:t:html" "<i>ok</i>"
   "urn:t:dash" " -\n"
   "urn:t:on" " On\n"
   "urn:t:empty" ""
   "urn:t:inner" "[$h{{x}}]"
   "urn:t:note" "($h{{message}})"})

;; Two views, bound as the game binds its own: a template at a name, filled with the
;; view's arguments and what its name captures (which wins).
(def case-views
  [[#"urn:t:view:inner:(.+)" ["x"] "urn:t:inner"]
   [#"urn:t:view:note" [] "urn:t:note"]])

(defn case-source
  "The cases' world: `urn:t:cell:{x}:{y}` is its two coordinates joined by `.`."
  [iri given]
  (or (get case-resources iri)
      (when-let [[_ x y] (re-matches #"urn:t:cell:([^:]+):(.+)" iri)] (str x "." y))
      (some (fn [[pattern names template]]
              (when-let [m (re-matches pattern iri)]
                (compose/fill (get case-resources template)
                              (merge given (zipmap names (if (string? m) [] (rest m))))
                              case-source)))
            case-views)
      (throw (wire/typed-error :unresolved {:iri iri}))))

(defn- malformed? [e]
  (let [{kind :ikigai.error/kind message :message} (ex-data e)]
    (and (= :endpoint kind) (str/starts-with? message "compose:"))))

(deftest every-template-case-holds
  (let [cases (vectors/template-cases)]
    (is (= 38 (count cases)))
    (doseq [[i {:keys [kind template expected]}] (map-indexed vector cases)]
      (testing (str (name kind) "[" i "] " (pr-str template))
        (if (= kind :fill)
          (is (= expected (compose/fill template case-arguments case-source)))
          (let [e (try (compose/fill template case-arguments case-source) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (wire/typed-error? e) "the fill is refused with a typed error")
            (when (and e expected)
              (is (= (= expected "malformed") (malformed? e)) (ex-message e)))))))))

(deftest an-argument-in-an-iri-is-percent-encoded-and-in-a-value-verbatim
  (let [echo (fn [iri given]
               (str/join " " (cons iri (map (fn [[k v]] (str k "=" v)) (sort given)))))
        args {"v" "a b/c:é~._-"}]
    (is (= "urn:t:echo:a%20b%2Fc%3A%C3%A9~._-" (compose/fill "$r{urn:t:echo:{v}}" args echo)))
    (is (= "urn:t:echo e=a\"b\\c in=a b/c:é~._- q={v}"
           (compose/fill "$r{urn:t:echo?in={v}&q=\"{v}\" &e=\"a\\\"b\\\\c\"}" args echo)))))

(deftest a-cycle-stops-at-the-depth-limit
  (let [e (try (compose/fill "$a{urn:t:loop}" {} (fn [_ _] "$a{urn:t:loop}")) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (re-find #"recursion limit \(32\) exceeded" (ex-message e)))))
