(ns ikigai.vectors-test
  "The vectors are the files their sources wrote, byte for byte, and have the
  shape this face's tests read. These run today, so a bad copy fails now
  rather than when the function that needs it is written."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ikigai.vectors :as vectors]))

;; Each JSON section: the keys every row has, and the keys some rows add.
(def shapes
  {"auth_vectors.json"
   {"basic" [#{"name" "source" "user" "password" "expect"}]
    "basic_refusals" [#{"name" "source" "user" "password" "error"}]
    "bearer" [#{"name" "source" "token" "expect"}]
    "bearer_refusals" [#{"name" "source" "token" "error"}]
    "scheme" [#{"name" "source" "value" "expect"}]
    "base64" [#{"name" "source" "encoded"} #{"text" "hex"}]
    "base64_refusals" [#{"name" "source" "encoded" "error"}]}
   "capability_vectors.json"
   {"deny_scopes" [#{"scope" "is_deny_scope"}]
    "coverage" [#{"capability" "scope" "allows" "satisfies"}]
    "principals" [#{"capability" "principal"}]
    "floors" [#{"name" "description" "verbs"}]
    "denials" [#{"name" "description" "verb" "target" "capability" "denied"} #{"unresolved"}]}
   "contract_cases.json"
   {"contracts" [#{"name" "id" "action"}]
    "doors" [#{"name" "pattern" "description"}]}
   "contract_vectors.json"
   {"contracts" [#{"name" "id" "action" "contract"}]
    "doors" [#{"name" "pattern" "description" "actions" "turtle"}]
    "matches" [#{"pattern" "iris"}]
    "parses" [#{"iri" "match" "contract" "canonical"}]}
   "failure_class_vectors.json"
   {"cases" [#{"name" "error" "kind" "class" "transient" "display"
               "retry_after_ms" "wire_tag" "http"}]
    "classes" [#{"word" "iri"}]}
   "html_vectors.json"
   {"escape" [#{"in" "out"}]
    "url_segment" [#{"in" "out"}]
    "percent_decode" [#{"in" "plus_is_space"} #{"out" "bytes" "error" "offset"}]
    "form_pairs" [#{"in"} #{"out" "error" "offset"}]
    "safe_href" [#{"in" "out" "why"}]
    "json_attr" [#{"in" "out"}]}
   "validation_vectors.json"
   {"cases" [#{"name" "action" "args" "conforms" "violations"}]}})

;; The non-row fields each file must carry, and their values where they are fixed.
(def fields
  {"auth_vectors.json" {"about" string? "version" #{1}}
   "capability_vectors.json" {"about" string? "form" #{"ikigai:capability:v1"}}
   "contract_vectors.json" {"about" string?
                            "form" #{"ikigai:contract:v1"}
                            "form_v2" #{"ikigai:contract:v2"}}
   "failure_class_vectors.json" {"about" string?}
   "html_vectors.json" {"about" string?}
   "validation_vectors.json" {"about" string?}})

(defn- sha-256 [^java.io.File f]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (java.nio.file.Files/readAllBytes (.toPath f)))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- sums
  "SHA256SUMS as a map of file name to digest (`shasum -a 256` lines)."
  []
  (into {}
        (for [line (str/split-lines (vectors/text "SHA256SUMS"))
              :when (not (str/blank? line))]
          (let [[_ digest name] (re-matches #"([0-9a-f]{64})  (\S+)" line)]
            (is digest (str "a SHA256SUMS line is not `<sha256>  <file>`: " line))
            [name digest]))))

(def ^:private vector-files
  "Every vector file in the directory: all but its README and SHA256SUMS."
  (->> (.listFiles (io/file vectors/dir))
       (map #(.getName ^java.io.File %))
       (remove #{"README.md" "SHA256SUMS"})
       sort
       vec))

(deftest every-vector-is-the-copy-sha256sums-records
  (let [recorded (sums)]
    (is (= (sort (keys recorded)) vector-files)
        "SHA256SUMS lists exactly the vector files in test/vectors/")
    (doseq [name vector-files]
      (is (= (get recorded name) (sha-256 (vectors/file name)))
          (str name " differs from the copy SHA256SUMS records: re-copy it from its source"
               " (test/vectors/README.md), never edit it by hand")))))

(deftest every-vector-has-lf-line-endings
  (doseq [name vector-files]
    (is (not (str/includes? (vectors/text name) "\r"))
        (str name " holds a carriage return; check .gitattributes and core.autocrlf"))))

(deftest gitattributes-names-every-vector-lf
  (let [lines (set (map str/trim (str/split-lines (slurp ".gitattributes"))))]
    (doseq [name vector-files]
      (is (some #(re-matches (re-pattern (str "test/vectors/" (java.util.regex.Pattern/quote name)
                                              "\\s+text\\s+eol=lf"))
                             %)
                lines)
          (str ".gitattributes has no `test/vectors/" name " text eol=lf` line")))))

(deftest every-json-vector-has-its-shape
  (doseq [[name sections] shapes
          :let [doc (vectors/read-json name)]]
    (testing name
      (doseq [[field ok?] (get fields name)]
        (is (ok? (get doc field)) (str name " `" field "` is " (pr-str (get doc field)))))
      (doseq [[section [required optional]] sections
              :let [rows (get doc section)]]
        (is (and (sequential? rows) (seq rows)) (str name " has no `" section "` rows"))
        (doseq [[i row] (map-indexed vector rows)
                :let [ks (set (keys row))]]
          (is (set/subset? required ks)
              (str name " " section "[" i "] lacks " (set/difference required ks)))
          (is (set/subset? ks (set/union required optional))
              (str name " " section "[" i "] has unknown keys "
                   (set/difference ks required (or optional #{})))))))))

(deftest a-decode-case-has-an-answer-or-an-error-never-both
  (doseq [section ["percent_decode" "form_pairs"]
          [i row] (map-indexed vector (vectors/rows "html_vectors.json" section))]
    (is (not= (contains? row "out") (contains? row "error"))
        (str "html_vectors.json " section "[" i "] needs exactly one of out and error"))))

(deftest contract-cases-are-the-input-the-contract-vectors-were-computed-from
  (let [cases (vectors/read-json "contract_cases.json")
        computed (vectors/read-json "contract_vectors.json")
        strip (fn [rows ks] (map #(select-keys % ks) rows))]
    (is (= (strip (get cases "contracts") ["name" "id" "action"])
           (strip (get computed "contracts") ["name" "id" "action"])))
    (is (= (strip (get cases "doors") ["name" "pattern" "description"])
           (strip (get computed "doors") ["name" "pattern" "description"])))
    (is (= (get cases "patterns") (map #(get % "pattern") (get computed "matches"))))
    (is (= (get cases "parses") (map #(get % "iri") (get computed "parses"))))))

(deftest the-template-cases-parse
  (let [cases (vectors/template-cases)]
    (is (seq cases))
    (is (every? #{:fill :refuse} (map :kind cases)))
    (is (every? :expected (filter #(= :fill (:kind %)) cases))
        "every fill case says what it fills to")
    (is (every? #(contains? #{nil "malformed" "failed"} %)
                (map :expected (filter #(= :refuse (:kind %)) cases)))
        "a refuse case says malformed, failed or nothing")
    (testing "\\u{…} is the code point it names"
      (is (some #(str/includes? (:template %) "　") cases)))))
