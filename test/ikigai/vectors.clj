(ns ikigai.vectors
  "Reading the shared vectors under `test/vectors/`.

  JSON is read with `clojure.data.json`, which babashka bundles and the JVM
  gets from the `:test` alias, with object keys kept as the strings the files
  spell, so a row reads exactly as its file does. Paths are relative to the
  repository root, where `bb test` and `clojure -X:test` both run."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def dir
  "The directory that holds the vectors."
  "test/vectors")

(defn file
  "The vector file `name`, as a `java.io.File`."
  [name]
  (io/file dir name))

(defn text
  "The vector file `name` decoded as UTF-8."
  [name]
  (slurp (file name) :encoding "UTF-8"))

(defn read-json
  "The vector file `name`, parsed, with string keys."
  [name]
  (json/read-str (text name)))

(defn rows
  "The rows of `section` in the JSON vector file `name`."
  [name section]
  (get (read-json name) section))

(defn- unescape
  "Replace each `\\u{HEX}` in `s` with the code point it names; no other
  backslash is special (the README's rule for the template cases)."
  [s]
  (str/replace s #"\\u\{([0-9A-Fa-f]+)\}"
               (fn [[_ hex]] (String. (Character/toChars (Integer/parseInt hex 16))))))

(defn template-cases
  "The tic-tac-toe template cases, one map per line: `:kind` (`:fill` or
  `:refuse`), `:template`, and `:expected` (what a `fill` fills to, or how a
  `refuse` fails: `\"malformed\"`, `\"failed\"` or nil). Split as ikigai-python
  splits them: the kind word, leading spaces, then the template up to the
  first tab. A line that is neither kind, or a `fill` with no tab, is an
  error, so a bad copy fails loudly."
  []
  (vec
   (for [line (str/split-lines (text "ttt_template_cases.txt"))]
     (let [kind (some #(when (str/starts-with? line %) %) ["fill" "refuse"])
           _ (when-not kind
               (throw (ex-info "a template case is neither fill nor refuse" {:line line})))
           rest-of-line (str/triml (subs line (count kind)))
           tab (str/index-of rest-of-line "\t")]
       (when (and (= kind "fill") (nil? tab))
         (throw (ex-info "a fill case has no tab" {:line line})))
       {:kind (keyword kind)
        :template (unescape (if tab (subs rest-of-line 0 tab) rest-of-line))
        :expected (when tab (unescape (subs rest-of-line (inc tab))))}))))
