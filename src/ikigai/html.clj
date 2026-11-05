(ns ikigai.html
  "Escaping and URL rules for HTML built from data: `ikigai_fn::html`.

  An HTML page built from strings has three places a value can break out of:
  text, an attribute, and a URL. These are the ecosystem's one set of rules
  for them; a face calls these rather than writing its own escaper.

  | Where the value goes                           | Function                       |
  |------------------------------------------------|--------------------------------|
  | element text: `<p>{}</p>`                      | `html-text`                    |
  | a quoted attribute: `title=\"{}\"`               | `attr`                         |
  | one path segment or query value                | `url-segment`                  |
  | a whole link target from data: `href=\"{}\"`     | `safe-href`, then `attr`       |
  | htmx's `hx-vals='{}'`                          | `json-attr`                    |

  Percent escapes decode strictly through `percent-decode` (text) or
  `percent-decode-bytes`, and `form-pairs` parses a whole form body or query
  with byte offsets into the original input. A refusal is an `ex-info` whose
  data names `:ikigai.html/error` (`:malformed-escape` or `:invalid-utf8`) and,
  where there is one, `:offset`.

  Nothing here makes an unquoted attribute, an event-handler attribute, or the
  inside of `<script>` or `<style>` safe.

  Proved by `test/vectors/html_vectors.json` (`escape`, `url_segment`,
  `percent_decode`, `form_pairs`, `safe_href`, `json_attr`), whose `about`
  states the rules."
  (:require [clojure.string :as str]
            [ikigai.postcard :as pc]))

(set! *warn-on-reflection* true)

(defn html-text
  "Escape `s` for HTML (or XML) element text: `&`, `<`, `>`, `\"` and `'`
  become `&amp;`, `&lt;`, `&gt;`, `&quot;` and `&#39;`, and a C0 control other
  than tab, LF and CR becomes U+FFFD. Everything else passes through. Not
  idempotent, by design. Each `escape` case's `out`.

    (html-text \"it's <b>\")  ;=> \"it&#39;s &lt;b&gt;\""
  [^String s]
  (let [sb (StringBuilder. (count s))]
    (doseq [c s]
      (case c
        \& (.append sb "&amp;")
        \< (.append sb "&lt;")
        \> (.append sb "&gt;")
        \" (.append sb "&quot;")
        \' (.append sb "&#39;")
        (if (and (< (int c) 0x20) (not (#{\tab \newline \return} c)))
          (.append sb (char 0xFFFD))
          (.append sb c))))
    (str sb)))

(defn attr
  "Escape `s` for a quoted attribute value. The same output as `html-text`,
  under the name that says where it goes: each `escape` case's `out`. The
  attribute must still be quoted, and an `href` or `src` from data goes
  through `safe-href` first.

    (attr \"a\\\"b\")  ;=> \"a&quot;b\""
  [s]
  (html-text s))

(def ^:private unreserved
  (set (map int "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")))

(defn url-segment
  "Percent-encode `s` as one URL path segment or query value: every UTF-8 byte
  outside RFC 3986's unreserved set becomes `%XX` in uppercase hex. Each
  `url_segment` case's `out`.

    (url-segment \"a b/c\")  ;=> \"a%20b%2Fc\""
  [s]
  (apply str (map (fn [b] (let [b (bit-and b 0xff)]
                            (if (unreserved b) (str (char b)) (format "%%%02X" b))))
                  (pc/utf8-bytes s))))

(defn- hex-value ^long [b]
  (cond
    (<= 0x30 b 0x39) (- b 0x30)
    (<= 0x41 b 0x46) (+ 10 (- b 0x41))
    (<= 0x61 b 0x66) (+ 10 (- b 0x61))
    :else -1))

(defn- malformed [offset]
  (ex-info (str "percent_decode: malformed percent-escape at byte " offset)
           {:ikigai.html/error :malformed-escape :offset offset}))

(defn- decode-bytes
  "Decode `%XX` escapes in the byte array `data` (and `+` as a space when
  `plus-is-space`), refusing a malformed escape at its byte offset."
  ^bytes [^bytes data plus-is-space]
  (let [n (alength data)
        out (java.io.ByteArrayOutputStream. n)
        at (fn [i] (if (< i n) (bit-and (aget data i) 0xff) -1))]
    (loop [i 0]
      (when (< i n)
        (let [b (at i)]
          (cond
            (= b 0x25) (let [hi (if (< (inc i) n) (hex-value (at (inc i))) -1)
                             lo (if (< (+ i 2) n) (hex-value (at (+ i 2))) -1)]
                         (when (or (neg? hi) (neg? lo)) (throw (malformed i)))
                         (.write out (int (bit-or (bit-shift-left hi 4) lo)))
                         (recur (+ i 3)))
            (and (= b 0x2B) plus-is-space) (do (.write out 0x20) (recur (inc i)))
            :else (do (.write out (int b)) (recur (inc i)))))))
    (.toByteArray out)))

(defn percent-decode-bytes
  "As `percent-decode`, but return the decoded bytes (each case's `bytes`)
  and refuse only a malformed escape. `s` is a string (read as its UTF-8
  bytes) or a byte array.

    (vec (percent-decode-bytes \"%FF+\" true))  ;=> [-1 32]"
  ^bytes [s plus-is-space]
  (decode-bytes (if (bytes? s) s (pc/utf8-bytes s)) plus-is-space))

(defn percent-decode
  "Decode the percent escapes in `s` exactly once, strictly, reading `+` as a
  space when `plus-is-space` is true (form and query values; false for
  paths). Return the decoded text (each `percent_decode` case's `out`), or
  refuse with an `ex-info` whose data names the error (`:malformed-escape`
  with the input byte `:offset`, or `:invalid-utf8`). Decoding does not make a
  value safe for HTML.

    (percent-decode \"%252B+x\" true)  ;=> \"%2B x\""
  [s plus-is-space]
  (let [decoded (percent-decode-bytes s plus-is-space)
        text (pc/utf8-decode decoded)]
    (if (map? text)
      (throw (ex-info "percent_decode: decoded bytes are not UTF-8"
                      {:ikigai.html/error :invalid-utf8 :decoded-offset (:invalid text)}))
      text)))

(defn- form-component
  "Decode one form component that starts at original-input byte `base`."
  [^String text base]
  (try
    (percent-decode text true)
    (catch clojure.lang.ExceptionInfo e
      (let [{kind :ikigai.html/error :keys [offset decoded-offset]} (ex-data e)
            offset (if (= kind :malformed-escape)
                     offset
                     ;; Escapes are already valid: each `%XX` produced one decoded byte,
                     ;; so walk the source to the byte or escape that starts the bad sequence.
                     (let [data (pc/utf8-bytes text)]
                       (loop [o 0 k 0]
                         (if (= k decoded-offset) o
                             (recur (if (= 0x25 (aget data o)) (+ o 3) (inc o)) (inc k))))))
            at (+ base offset)
            reason (if (= kind :malformed-escape)
                     "malformed percent-escape"
                     "decoded bytes are not UTF-8")]
        (throw (ex-info (str "form_pairs: " reason " at byte " at)
                        {:ikigai.html/error kind :offset at :source (ex-data e)}
                        e))))))

(defn form-pairs
  "Parse a complete `application/x-www-form-urlencoded` body or query into
  ordered `[name value]` pairs: split on literal `&`, skip empty fields, split
  each at its first literal `=` (a missing value is empty), and decode each
  name and value once with `+` as a space. Each `form_pairs` case's `out`, or
  its `error` and `offset` (in the original input) as an `ex-info`.

    (form-pairs \"a=1&&b&c=x+y\")  ;=> [[\"a\" \"1\"] [\"b\" \"\"] [\"c\" \"x y\"]]"
  [^String s]
  (loop [fields (str/split s #"&" -1) base 0 pairs []]
    (if-let [[field & more] (seq fields)]
      (let [next-base (+ base (alength (pc/utf8-bytes field)) 1)]
        (if (= "" field)
          (recur more next-base pairs)
          (let [cut (str/index-of field "=")
                name (if cut (subs field 0 cut) field)
                value (if cut (subs field (inc cut)) "")
                n (form-component name base)
                v (form-component value (+ base (alength (pc/utf8-bytes name)) (if cut 1 0)))]
            (recur more next-base (conj pairs [n v])))))
      pairs)))

(defn safe-href
  "The trimmed link target `s` if it is safe to put in an `href`, or nil when
  it is not a link and the label should be rendered as text: trimmed of
  leading and trailing spaces and C0 controls, holding no C0 control or DEL,
  and starting with `http://` or `https://` in any ASCII case, or with a single
  `/` (not `//` or `/\\`). Each `safe_href` case's `out`. The result is still
  to be escaped with `attr`.

    (map safe-href [\" https://x.org \" \"javascript:alert(1)\" \"//evil\" \"/ok\"])
    ;=> (\"https://x.org\" nil nil \"/ok\")"
  [^String s]
  (let [trim? #(<= (int %) 0x20)
        s (->> s (drop-while trim?) reverse (drop-while trim?) reverse (apply str))
        lower (str/lower-case (subs s 0 (min 8 (count s))))]
    (cond
      (some #(or (< (int %) 0x20) (= (int %) 0x7f)) s) nil
      (or (str/starts-with? lower "https://") (str/starts-with? lower "http://")) s
      (and (str/starts-with? s "/")
           (not (str/starts-with? s "//"))
           (not (str/starts-with? s "/\\"))) s
      :else nil)))

(defn- json-string [^String s]
  (let [sb (StringBuilder. "\"")]
    (doseq [c s]
      (case c
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        \backspace (.append sb "\\b")
        \formfeed (.append sb "\\f")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c))))
    (str (.append sb "\""))))

(defn json-attr
  "A JSON object of strings, escaped for a quoted attribute, from the
  `[key value]` pairs in order, with no whitespace (a duplicate key is written
  as given): htmx's `hx-vals`. Each `json_attr` case's `out`.

    (json-attr [[\"x\" \"1\"]])  ;=> \"{&quot;x&quot;:&quot;1&quot;}\""
  [pairs]
  (attr (str "{" (str/join "," (map (fn [[k v]] (str (json-string k) ":" (json-string v))) pairs))
             "}")))
