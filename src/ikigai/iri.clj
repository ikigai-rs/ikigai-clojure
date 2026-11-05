(ns ikigai.iri
  "IRIs as core spells and checks them: `ikigai_core::Iri::parse`,
  `encode_name` and `escape_iri_fragment`.

  `iri?` answers exactly what core's `Iri::parse` answers. Core validates with
  oxiri 0.2.11 (RFC 3987, absolute form), and this is that parser's checked
  path with no base, state for state, keeping only the accept or refuse
  answer, ported from ikigai-python's `contract.py` (`_IriParser`), which ports
  oxiri. Where oxiri is stricter or looser than the RFC, so is this: the only
  thing that matters is answering exactly as core does.

  The one piece written here rather than ported is the IPv6 literal check,
  which oxiri hands to Rust's `Ipv6Addr::from_str` and ikigai-python to its
  standard library: the JDK's only IPv6 parser (`InetAddress`) also resolves
  host names, so `ipv6?` states Rust's grammar directly (see its docstring).

  Everything works on Unicode code points, as the Rust and Python originals do."
  (:require [clojure.string :as str]))

(defn code-points
  "The code points of `s`, as a vector of ints.

    (code-points \"a😀\")  ;=> [97 128512]"
  [^String s]
  (vec (.toArray (.codePoints s))))

(defn- in-ranges? [cp ranges]
  (boolean (some (fn [[lo hi]] (<= lo cp hi)) ranges)))

(def ^:private ucschar
  (concat [[0xA0 0xD7FF] [0xF900 0xFDCF] [0xFDF0 0xFFEF]]
          (for [plane (range 0x10000 0xE0000 0x10000)] [plane (+ plane 0xFFFD)])
          [[0xE1000 0xEFFFD]]))

(def ^:private iprivate [[0xE000 0xF8FF] [0xF0000 0xFFFFD] [0x100000 0x10FFFD]])

(defn- ch? [cp chars] (and cp (< cp 0x80) (str/includes? chars (str (char cp)))))
(defn- ascii-alpha? [cp] (and cp (or (<= 0x41 cp 0x5A) (<= 0x61 cp 0x7A))))
(defn- ascii-digit? [cp] (and cp (<= 0x30 cp 0x39)))
(defn- ascii-alnum? [cp] (or (ascii-alpha? cp) (ascii-digit? cp)))
(defn- ascii-hex? [cp] (and cp (or (ascii-digit? cp) (<= 0x41 cp 0x46) (<= 0x61 cp 0x66))))

(defn- unreserved-or-sub-delims? [cp]
  (or (ascii-alnum? cp) (ch? cp "!$&'()*+,-.;=_~")))

(defn- iunreserved-or-sub-delims? [cp]
  (or (unreserved-or-sub-delims? cp) (and cp (in-ranges? cp ucschar))))

(defn- pchar? [cp] (or (iunreserved-or-sub-delims? cp) (ch? cp ":@")))
(defn- query-char? [cp] (or (pchar? cp) (ch? cp "/?") (and cp (in-ranges? cp iprivate))))
(defn- fragment-char? [cp] (or (pchar? cp) (ch? cp "/?")))
(defn- userinfo-char? [cp] (or (iunreserved-or-sub-delims? cp) (ch? cp ":")))

(defn- ipv4? [s]
  ;; Rust's Ipv4Addr: four decimal octets, each 0 to 255, with no leading zero.
  (let [parts (str/split s #"\." -1)]
    (and (= 4 (count parts))
         (every? #(and (re-matches #"[0-9]{1,3}" %)
                       (or (= "0" %) (not (str/starts-with? % "0")))
                       (<= (parse-long %) 255))
                 parts))))

(defn ipv6?
  "True when `s` is an IPv6 address as Rust's `Ipv6Addr::from_str` reads one:
  eight groups of one to four hex digits separated by `:`, where one `::` may
  stand for one or more zero groups, and the last two groups may be written as
  a dotted IPv4 address. No zone (`%…`), no brackets.

    (map ipv6? [\"::1\" \"1:2:3:4:5:6:7::\" \"::ffff:1.2.3.4\" \"1::2::3\" \"fe80::1%en0\"])
    ;=> (true true true false false)"
  [s]
  (let [group? #(re-matches #"[0-9A-Fa-f]{1,4}" %)
        ;; The groups on one side of `::` (or the whole address), counting an IPv4
        ;; tail as two; nil when malformed. An empty side is no groups.
        side (fn [text v4-ok?]
               (if (= "" text)
                 0
                 (let [parts (str/split text #":" -1)
                       lead (butlast parts)
                       tail (last parts)]
                   (when (every? group? lead)
                     (cond
                       (group? tail) (count parts)
                       (and v4-ok? (ipv4? tail)) (inc (count parts))
                       :else nil)))))]
    (if-let [at (str/index-of s "::")]
      (let [head (subs s 0 at)
            tail (subs s (+ at 2))]
        (boolean (when-not (str/includes? tail "::")
                   (let [h (side head false)
                         t (side tail true)]
                     (and h t (<= (+ h t) 7))))))
      (= 8 (side s true)))))

(defn- ip-v-future? [ip]
  (let [cps (code-points ip)]
    (and (seq cps) (ch? (first cps) "vV")
         (let [[version rest-of] (split-with #(not= % (int \.)) (rest cps))]
           (and (seq version) (every? ascii-hex? version)
                (seq rest-of) (seq (rest rest-of))
                (every? #(or (unreserved-or-sub-delims? %) (ch? % ":")) (rest rest-of)))))))

(defn iri?
  "True when core's `Iri::parse` accepts `s`: an absolute IRI by RFC 3987, as
  core's oxiri (0.2.11) checks it.

    (map iri? [\"urn:cap:core:read\" \"urn:cls:a%3E%20b\" \"not%20a%20class\"
               \"http://ok%3E%20.%20%3Curn:z\"])
    ;=> (true true false false)"
  [^String s]
  (let [cps (code-points s)
        n (count cps)
        at (fn [i] (when (< i n) (nth cps i)))
        ;; Each state takes the position and returns whether the rest parses. A
        ;; `%` must be followed by two hex digits.
        echar (fn [i valid?]
                (let [c (at i)]
                  (cond
                    (valid? c) (inc i)
                    (= c (int \%)) (when (and (ascii-hex? (at (inc i))) (ascii-hex? (at (+ i 2))))
                                     (+ i 3))
                    :else nil)))]
    (letfn [(fragment [i]
              (loop [i i]
                (or (>= i n) (when-let [j (echar i fragment-char?)] (recur j)))))
            (query [i]
              (loop [i i]
                (cond
                  (>= i n) true
                  (= (at i) (int \#)) (fragment (inc i))
                  :else (when-let [j (echar i query-char?)] (recur j)))))
            (path [i]
              (loop [i i]
                (let [c (at i)]
                  (cond
                    (nil? c) true
                    (= c (int \/)) (recur (inc i))
                    (= c (int \?)) (query (inc i))
                    (= c (int \#)) (fragment (inc i))
                    :else (when-let [j (echar i pchar?)] (recur j))))))
            ;; `c` is the character at `i - 1` that ended the host or port (nil at the end).
            (path-start [c i]
              (cond
                (nil? c) true
                (= c (int \?)) (query i)
                (= c (int \#)) (fragment i)
                (= c (int \/)) (path i)
                :else (when-let [j (echar (dec i) pchar?)] (path j))))
            (port [i]
              (loop [i i]
                (let [c (at i)]
                  (cond
                    (or (nil? c) (ch? c "/?#")) (path-start c (inc i))
                    (ascii-digit? c) (recur (inc i))
                    :else false))))
            (host [i]
              (if (= (at i) (int \[))
                (if-let [close (first (filter #(= (at %) (int \])) (range (inc i) n)))]
                  (let [ip (String. (int-array (subvec cps (inc i) close)) 0 (- close i 1))
                        ok (if (ch? (at (inc i)) "vV") (ip-v-future? ip) (ipv6? ip))
                        c (at (inc close))]
                    (and ok
                         (cond
                           (= c (int \:)) (port (+ close 2))
                           (or (nil? c) (ch? c "/?#")) (path-start c (+ close 2))
                           :else false)))
                  false)
                (loop [i i]
                  (let [c (at i)]
                    (cond
                      (= c (int \:)) (port (inc i))
                      (or (nil? c) (ch? c "/?#")) (path-start c (inc i))
                      :else (when-let [j (echar i iunreserved-or-sub-delims?)] (recur j)))))))
            (authority [scheme-end i]
              (loop [i i]
                (let [c (at i)]
                  (cond
                    (= c (int \@)) (host (inc i))
                    ;; No userinfo: rescan from just after `//` as the host.
                    (or (nil? c) (ch? c "[/?#")) (host (+ scheme-end 2))
                    :else (when-let [j (echar i userinfo-char?)] (recur j))))))
            (scheme [i]
              (loop [i i]
                (let [c (at i)]
                  (cond
                    (or (ascii-alnum? c) (ch? c "+-.")) (recur (inc i))
                    (= c (int \:)) (let [scheme-end (inc i)]
                                     (if (= (at scheme-end) (int \/))
                                       (if (= (at (inc scheme-end)) (int \/))
                                         (authority scheme-end (+ scheme-end 2))
                                         (path (inc scheme-end)))
                                       (path scheme-end)))
                    ;; A relative reference: never absolute.
                    :else false))))]
      (boolean (and (ascii-alpha? (at 0)) (scheme 0))))))

(defn percent-encode-code-point
  "The code point `cp`'s UTF-8 bytes as `%XX` escapes, uppercase.

    (percent-encode-code-point 0x20)  ;=> \"%20\""
  [cp]
  (let [^bytes b (.getBytes (String. (Character/toChars cp)) "UTF-8")]
    (apply str (map #(format "%%%02X" (bit-and % 0xff)) b))))

(defn- forbidden? [cp]
  (or (<= cp 0x20) (ch? cp "<>\"{}|^`\\")))

(defn- non-ucschar? [cp]
  (if (< cp 0x80) (= cp 0x7F) (not (in-ranges? cp ucschar))))

(defn- must-encode-name? [cp]
  (or (= cp (int \%)) (forbidden? cp) (ch? cp "[]#?") (non-ucschar? cp)))

(defn- encode-with [pred s]
  (apply str (map (fn [cp] (if (pred cp) (percent-encode-code-point cp) (String. (Character/toChars cp))))
                  (code-points s))))

(defn encode-name
  "`ikigai_core::encode_name`: percent-encode a NAME for an IRI position,
  injectively: `%`, what a Turtle IRIREF cannot carry, `[`, `]`, `#`, `?`, DEL
  and every code point outside RFC 3987's ucschar. Everything else, ordinary
  Unicode included, is written as it is.

    (encode-name \"50% off\")         ;=> \"50%25%20off\"
    (encode-name \"http://[::1]/x\")  ;=> \"http://%5B::1%5D/x\"
    (encode-name \"naïve\")           ;=> \"naïve\""
  [s]
  (encode-with must-encode-name? s))

(defn escape-iri-fragment
  "`ikigai_core::escape_iri_fragment`: percent-encode what a Turtle IRIREF
  cannot carry (a space or control, and `<>\"{}|^`\\`), leaving `%` alone, so it
  is not injective, by core's design.

    (escape-iri-fragment \"a b<c>\")  ;=> \"a%20b%3Cc%3E\""
  [s]
  (encode-with forbidden? s))

(defn decode-segment
  "Decode the percent escapes in `s` (each exactly two hex digits, no sign) and
  read the bytes as strict UTF-8; nil when an escape is malformed or the bytes
  are not UTF-8.

    (decode-segment \"%7Bx%7d\")  ;=> \"{x}\"
    (decode-segment \"%+1\")      ;=> nil"
  [^String s]
  (let [raw (.getBytes s "UTF-8")
        n (alength raw)
        out (java.io.ByteArrayOutputStream.)
        hex (fn [i] (when (< i n) (Character/digit (char (bit-and (aget raw i) 0xff)) 16)))]
    (loop [i 0]
      (if (>= i n)
        (let [decoder (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
                        (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
                        (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
          (try (str (.decode decoder (java.nio.ByteBuffer/wrap (.toByteArray out))))
               (catch java.nio.charset.CharacterCodingException _ nil)))
        (let [b (bit-and (aget raw i) 0xff)]
          (if (= b 0x25)
            (let [hi (hex (inc i)) lo (hex (+ i 2))]
              (when (and hi lo (not= -1 hi) (not= -1 lo)
                         (< (bit-and (aget raw (inc i)) 0xff) 0x80)
                         (< (bit-and (aget raw (+ i 2)) 0xff) 0x80))
                (.write out (int (+ (* 16 hi) lo)))
                (recur (+ i 3))))
            (do (.write out (int b)) (recur (inc i)))))))))
