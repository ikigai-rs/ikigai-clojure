(ns ikigai.compose
  "The template language ikigai-fn's compose answers, the subset the ikigai
  Book's tic-tac-toe views use: a FILLER, so a Clojure program can render the
  game's views itself.

  The language is specified in ikigai-tutorial `crates/tic-tac-toe/README.md`
  (\"The template language\"), and its normative cases are the block between
  that README's `template-cases` markers, copied verbatim to
  `test/vectors/ttt_template_cases.txt`. This is a port of ikigai-python's
  `examples/tictactoe_app.py` filler (`fill`, `conditional`).

  - Three markers splice an answer: `$h{…}` escaped as HTML text, `$r{…}`
    raw, `$a{…}` filled again as a template, one level down (at most 32 levels,
    ikigai-fn's backstop against a cycle). `$$` is a literal `$`; a marker that
    never closes is literal text.
  - A marker names an argument (`{name}`) or a request: an IRI, then
    `?key=value&…`. `{name}` inside the IRI is percent-encoded the RFC 6570
    simple way; inside an unquoted value it is substituted verbatim; a
    `\"quoted\"` value is literal text, with `\\\"` and `\\\\` escapes.
  - `urn:iki:fn:conditional` is answered by the filler itself: source `if`,
    and if its trimmed text equals `equals` (or, without `equals`, reads as a
    true boolean), source and answer `then`, else `else` (or nothing).

  The order is ikigai-fn's, level by level: every marker is parsed before any
  resolves, so a malformed marker is refused as malformed (`compose: …`,
  an Endpoint error) whatever an earlier marker's request would have done;
  then every request is answered in document order; then each is spliced in
  document order. The first failure in document order is the one raised.

  Errors are typed errors (`ikigai.wire/typed-error`), as a kernel's compose
  would answer them.

  The game's views (which template each view fills) are not here: that is the
  tic-tac-toe example's to write, on top of `fill`."
  (:require [clojure.string :as str]
            [ikigai.html :as html]
            [ikigai.text :refer [trim]]
            [ikigai.wire :as wire]))

(def conditional-iri
  "The one function the templates call, answered by the filler itself."
  "urn:iki:fn:conditional")

(def max-depth
  "How deep `$a` may nest before it is a cycle: 32."
  32)

(defn- refused [detail]
  (wire/typed-error :endpoint {:message (str "compose: " detail)}))

(defn- unquoted
  "The index in `text`, from `i`, of the first `}` that closes nothing opened
  after `i` (or of `sep`, if given) outside a `\"…\"` span, where `\\\"` and
  `\\\\` are escapes; -1 when there is none."
  [^String text i sep]
  (let [n (count text)]
    (loop [i i depth 0 quoted false]
      (if (>= i n)
        -1
        (let [c (.charAt text (int i))]
          (cond
            (and quoted (= c \\)) (recur (+ i 2) depth quoted)
            (= c \") (recur (inc i) depth (not quoted))
            quoted (recur (inc i) depth quoted)
            sep (if (.startsWith text ^String sep (int i)) i (recur (inc i) depth quoted))
            (= c \{) (recur (inc i) (inc depth) quoted)
            (= c \}) (if (zero? depth) i (recur (inc i) (dec depth) quoted))
            :else (recur (inc i) depth quoted)))))))

(defn- split-unquoted [^String text sep]
  (loop [text text parts []]
    (let [at (unquoted text 0 sep)]
      (if (neg? at)
        (conj parts text)
        (recur (subs text (+ at (count sep))) (conj parts (subs text 0 at)))))))

(defn- scan
  "`template` as literal text and markers (`[letter body]`, the body trimmed)."
  [^String template]
  (let [n (count template)]
    (loop [i 0 text (StringBuilder.) out []]
      (if (>= i n)
        (conj out (str text))
        (let [c (.charAt template (int i))
              marker? (and (= c \$) (< (inc i) n) (#{\a \r \h} (.charAt template (int (inc i)))))
              end (if (and marker? (< (+ i 2) n) (= \{ (.charAt template (int (+ i 2)))))
                    (unquoted template (+ i 3) nil)
                    -1)]
          (cond
            (.startsWith template "$$" (int i)) (recur (+ i 2) (.append text \$) out)
            (and marker? (>= end 0))
            (recur (inc end) (StringBuilder.)
                   (conj out (str text) [(str (.charAt template (int (inc i))))
                                         (trim (subs template (+ i 3) end))]))
            :else (recur (inc i) (.append text c) out)))))))

(def ^:private arg-name #"[A-Za-z_][A-Za-z0-9_-]*")

(defn- argument [args name]
  (if (contains? args name)
    (get args name)
    (throw (wire/typed-error :missing-argument {:name name}))))

(defn- placeholders
  "Why `text` is malformed: a `{` that does not open a `{name}` argument; nil
  when it is not."
  [^String text]
  (loop [rest-of text]
    (when-let [start (str/index-of rest-of "{")]
      (if-let [end (str/index-of rest-of "}" start)]
        (let [name (subs rest-of (inc start) end)]
          (if (re-matches arg-name name)
            (recur (subs rest-of (inc end)))
            (str "`{" name "}` is not an argument name")))
        "a `{` is never closed"))))

(defn- substitute
  "`template` with each `{name}` replaced by that argument, percent-encoded
  (in an IRI) or verbatim (in an argument value)."
  [^String template args encode?]
  (loop [rest-of template out ""]
    (if-let [start (str/index-of rest-of "{")]
      (let [end (str/index-of rest-of "}" start)
            value (argument args (subs rest-of (inc start) end))]
        (recur (subs rest-of (inc end))
               (str out (subs rest-of 0 start) (if encode? (html/url-segment value) value))))
      (str out rest-of))))

(defn- unquote-value [^String word]
  (when (and (>= (count word) 2) (str/starts-with? word "\"") (str/ends-with? word "\""))
    (str/replace (subs word 1 (dec (count word))) #"(?s)\\(.)" "$1")))

(defn- parse-marker
  "One marker's body, or the template's refusal: `{:mode :body :argument}` or
  `{:mode :body :iri :query [[key text literal?] …]}`."
  [mode ^String body]
  (let [malformed #(refused (str "marker `" body "`: " %))]
    (when (= "" body) (throw (malformed "an empty alternative")))
    (when (> (count (split-unquoted body "||")) 1)
      (throw (malformed "this filler has no `||` fallbacks")))
    (let [name (when (and (str/starts-with? body "{") (str/ends-with? body "}"))
                 (subs body 1 (dec (count body))))]
      (if (and name (re-matches arg-name name))
        (if (= mode "a")
          (throw (malformed (str "`{" name "}` is an argument, a value and never a template"
                                 " — splice it with `$h` or `$r`, not `$a`")))
          {:mode mode :body body :argument name})
        (let [q (str/index-of body "?")
              iri (trim (if q (subs body 0 q) body))
              query (if q (subs body (inc q)) "")]
          (when-let [detail (placeholders iri)] (throw (malformed detail)))
          {:mode mode :body body :iri iri
           :query (vec (for [pair (map trim (split-unquoted query "&"))
                             :when (not= "" pair)]
                         (let [eq (str/index-of pair "=")]
                           (when-not eq
                             (throw (refused (str "marker argument `" pair "` is not key=value"))))
                           (let [word (trim (subs pair (inc eq)))
                                 text (unquote-value word)]
                             (when-not text
                               (when-let [detail (placeholders word)] (throw (malformed detail))))
                             [(trim (subs pair 0 eq)) (or text word) (some? text)]))))})))))

(def ^:private boolean-words
  {"true" true "1" true "yes" true "on" true
   "false" false "0" false "no" false "off" false "" false})

(defn conditional
  "`urn:iki:fn:conditional` over the arguments `given`: source `if`; if its
  trimmed text equals `equals` (or, without `equals`, reads as a true boolean:
  true, 1, yes, on), source and answer `then`, else `else` (or nothing). Only
  the chosen side is sourced. `source` is a function of an IRI and an argument
  map that returns text.

    (conditional {\"if\" \"urn:t:on\" \"then\" \"urn:t:yes\"}
                 (fn [iri _] ({\"urn:t:on\" \" On\\n\" \"urn:t:yes\" \"Y\"} iri)))
    ;=> \"Y\""
  [given source]
  (let [test-iri (argument given "if")
        then (argument given "then")
        verdict (trim (source test-iri {}))
        taken (if (contains? given "equals")
                (= verdict (get given "equals"))
                (let [b (get boolean-words (str/lower-case verdict) ::none)]
                  (when (= b ::none)
                    (let [shown (str "\"" (-> (str/lower-case verdict)
                                              (str/replace "\\" "\\\\")
                                              (str/replace "\"" "\\\""))
                                     "\"")]
                      (throw (wire/typed-error
                              :endpoint
                              {:message (str "conditional: `" test-iri "` returned " shown
                                             ", not a boolean (true/false/1/0/yes/no)")}))))
                  b))
        chosen (if taken then (get given "else"))]
    (if (nil? chosen) "" (source chosen {}))))

(defn- answer
  "What `marker` names, or the typed error that stopped it, kept until the
  marker's turn to splice."
  [marker args source]
  (try
    (if-let [name (:argument marker)]
      (argument args name)
      (let [request (substitute (:iri marker) args true)
            given (into {} (for [[k t literal?] (:query marker)]
                             [k (if literal? t (substitute t args false))]))]
        (if (= request conditional-iri)
          (conditional given source)
          (source request given))))
    (catch clojure.lang.ExceptionInfo e
      (if (wire/typed-error? e) {::failed e} (throw e)))))

(defn fill
  "Fill `template` with the arguments `args` (a map of name to text),
  resolving each marker through `source`, a function of an IRI and an
  argument map that returns text (or throws a typed error). Return the filled
  text, or refuse: with compose's own error (`compose: …`, an Endpoint error)
  when the template is malformed, or with the failing marker's error.

    (fill \"<p>$h{{message}} $r{urn:t:cell:{x}:1}</p>\" {\"message\" \"a<b\" \"x\" \"2\"}
          (fn [iri _] (str \"[\" iri \"]\")))
    ;=> \"<p>a&lt;b [urn:t:cell:2:1]</p>\""
  ([template args source] (fill template args source 0))
  ([template args source depth]
   (when (>= depth max-depth)
     (throw (refused (str "recursion limit (" max-depth ") exceeded — cyclic transclusion?"))))
   (let [parsed (mapv #(if (string? %) % (apply parse-marker %)) (scan template))
         answers (mapv #(if (string? %) % (answer % args source)) parsed)]
     (apply str
            (map (fn [p a]
                   (cond
                     (string? p) p
                     (::failed a) (throw (::failed a))
                     (= "a" (:mode p)) (fill a args source (inc depth))
                     (= "h" (:mode p)) (html/html-text a)
                     :else a))
                 parsed answers)))))
