(ns ikigai.postcard
  "The postcard primitives the wire needs, and nothing more.

  Postcard (https://postcard.jamesmunns.com) is a compact, non-self-describing
  binary format: the reader must know the shape it expects. The wire uses
  unsigned LEB128 varints for integers and lengths, a length-prefixed UTF-8
  string, a length-prefixed byte string, a one-byte bool and a one-byte Option
  tag. Every decode is strict: a truncated input, a varint longer than a u64,
  invalid UTF-8, or a bool or Option tag other than 0 or 1 is refused.

  Bytes are Java byte arrays. A decoder takes the array and an offset and
  returns `[value next-offset]`, so decoders compose without a mutable reader.
  A refusal is an `ex-info` whose data carries `:ikigai.wire/error :decode`.

  Integers above `Long/MAX_VALUE` (a u64 can reach 2^64 - 1) are
  `clojure.lang.BigInt`s; everything smaller is a long.

  Pinned by the byte literals ikigai-python's `tests/test_postcard.py` pins,
  copied into `test/ikigai/postcard_test.clj`."
  (:import (java.nio ByteBuffer CharBuffer)
           (java.nio.charset CodingErrorAction StandardCharsets)))

(set! *warn-on-reflection* true)

(def ^:private u64-max
  "2^64 - 1, the largest varint a u64 holds."
  18446744073709551615N)

(defn decode-error
  "The `ex-info` a decoder throws: `message`, with `:ikigai.wire/error :decode`
  and any `data`.

    (ex-message (decode-error \"varint too long\" {}))  ;=> \"varint too long\""
  [message data]
  (ex-info message (assoc data :ikigai.wire/error :decode)))

(defn concat-bytes
  "One byte array holding each of `parts` (byte arrays) in order.

    (vec (concat-bytes [(byte-array [1]) (byte-array [2 3])]))  ;=> [1 2 3]"
  ^bytes [parts]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [^bytes p parts] (.write out p 0 (alength p)))
    (.toByteArray out)))

(defn encode-varint
  "Encode the non-negative integer `n` (at most 2^64 - 1) as an unsigned
  LEB128 varint and return the bytes. 0 is `00`, 127 is `7f`, 128 is `80 01`,
  300 is `ac 02`, and 2^64 - 1 is ten bytes ending `01`. Refuse a negative
  `n` or one above 2^64 - 1.

    (vec (encode-varint 300))  ;=> [-84 2]"
  ^bytes [n]
  (when-not (integer? n)
    (throw (ex-info "postcard varints are integers" {:n n})))
  (when (neg? n)
    (throw (ex-info "postcard varints are unsigned" {:n n})))
  (when (> n u64-max)
    (throw (ex-info "postcard varints fit a u64" {:n n})))
  ;; A u64 above Long/MAX_VALUE is held as the long with the same 64 bits, and the
  ;; unsigned shift below reads it as unsigned.
  (let [bits (if (> n Long/MAX_VALUE) (.longValue (biginteger n)) (long n))
        out (java.io.ByteArrayOutputStream. 10)]
    (loop [v bits]
      (let [group (bit-and v 0x7f)
            more (unsigned-bit-shift-right v 7)]
        (if (zero? more)
          (.write out (int group))
          (do (.write out (int (bit-or group 0x80)))
              (recur more)))))
    (.toByteArray out)))

(defn- byte-at
  "The unsigned byte at `offset`, or a truncation refusal."
  ^long [^bytes bytes ^long offset]
  (when (>= offset (alength bytes))
    (throw (decode-error (str "truncated message: wanted 1 bytes at offset " offset
                              ", have " (max 0 (- (alength bytes) offset)))
                         {:offset offset})))
  (bit-and (aget bytes offset) 0xff))

(defn decode-varint
  "Decode an unsigned LEB128 varint from `bytes` at `offset` and return
  `[n next-offset]`. Refuse a truncated varint and one whose value does not
  fit a u64 (or `max-bits` bits, when given: an enum discriminant is a u32).

    (decode-varint (byte-array [0xac 0x02]) 0)  ;=> [300 2]"
  ([bytes offset] (decode-varint bytes offset 64))
  ([^bytes bytes offset max-bits]
   (let [max-bits (long max-bits)]
     (loop [n 0 shift 0 i (long offset)]
       (let [b (byte-at bytes i)
             n (if (< shift 64) (bit-or n (bit-shift-left (bit-and b 0x7f) shift)) n)
             ;; Bits that a shift of 63 pushes past bit 63, or any group at or past 64,
             ;; mean the value does not fit a u64.
             lost? (or (and (= shift 63) (> (bit-and b 0x7f) 1))
                       (and (>= shift 64) (pos? (bit-and b 0x7f))))]
         (cond
           (zero? (bit-and b 0x80))
           (let [value (if (neg? n) (bigint (Long/toUnsignedString n)) n)]
             (when (or lost? (>= value (.shiftLeft BigInteger/ONE max-bits)))
               (throw (decode-error (str "varint exceeds u" max-bits) {:offset offset})))
             [value (inc i)])

           (>= (+ shift 7) (+ max-bits 7))
           (throw (decode-error "varint too long" {:offset offset}))

           :else (recur n (+ shift 7) (inc i))))))))

(defn- take-bytes
  "The `n` bytes at `offset`, refusing a length that runs past the end."
  ^bytes [^bytes bytes ^long offset n]
  (let [have (- (alength bytes) offset)]
    (when (> n have)
      (throw (decode-error (str "truncated message: wanted " n " bytes at offset " offset
                                ", have " have)
                           {:offset offset})))
    (java.util.Arrays/copyOfRange bytes (int offset) (int (+ offset n)))))

(defn encode-bytes
  "Encode the byte array `bs` as its length (a varint) followed by its bytes.

    (vec (encode-bytes (byte-array [104 105])))  ;=> [2 104 105]"
  ^bytes [^bytes bs]
  (concat-bytes [(encode-varint (alength bs)) bs]))

(defn decode-bytes
  "Decode a length-prefixed byte string from `bytes` at `offset` and return
  `[byte-array next-offset]`. Refuse a length that runs past the end.

    (let [[b next] (decode-bytes (byte-array [2 104 105]) 0)] [(vec b) next])
    ;=> [[104 105] 3]"
  [bytes offset]
  (let [[n at] (decode-varint bytes offset)]
    [(take-bytes bytes at n) (+ at n)]))

(defn utf8-bytes
  "The UTF-8 bytes of the string `s`.

    (vec (utf8-bytes \"hi\"))  ;=> [104 105]"
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn utf8-decode
  "Decode `bs` as strict UTF-8: return the string, or `{:invalid offset}` where
  `offset` is the byte at which the first invalid sequence starts. Overlong
  forms, surrogates and truncated sequences are invalid, never replaced.

    (utf8-decode (byte-array [104 105]))         ;=> \"hi\"
    (utf8-decode (byte-array [104 (unchecked-byte 0xff)]))  ;=> {:invalid 1}"
  [^bytes bs]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))
        in (ByteBuffer/wrap bs)
        out (CharBuffer/allocate (alength bs))
        result (.decode decoder in out true)]
    (if (.isError result)
      {:invalid (.position in)}
      (let [flushed (.flush decoder out)]
        (if (.isError flushed)
          {:invalid (.position in)}
          (str (.flip out)))))))

(defn encode-string
  "Encode the string `s` as its UTF-8 bytes, length-prefixed.

    (vec (encode-string \"hi\"))  ;=> [2 104 105]"
  ^bytes [s]
  (encode-bytes (utf8-bytes s)))

(defn decode-string
  "Decode a length-prefixed UTF-8 string from `bytes` at `offset` and return
  `[string next-offset]`. Refuse invalid UTF-8 rather than replacing it.

    (decode-string (byte-array [2 104 105]) 0)  ;=> [\"hi\" 3]"
  [bytes offset]
  (let [[raw next] (decode-bytes bytes offset)
        text (utf8-decode raw)]
    (when (map? text)
      (throw (decode-error (str "invalid utf-8 in string at byte " (:invalid text))
                           {:offset offset})))
    [text next]))

(defn encode-bool
  "Encode `b` as one byte: `00` for false, `01` for true.

    (vec (encode-bool true))  ;=> [1]"
  ^bytes [b]
  (byte-array [(if b 1 0)]))

(defn decode-bool
  "Decode one bool byte from `bytes` at `offset` and return
  `[boolean next-offset]`. Refuse any byte other than `00` or `01`.

    (decode-bool (byte-array [1]) 0)  ;=> [true 1]"
  [bytes offset]
  (let [b (byte-at bytes offset)]
    (case b
      0 [false (inc offset)]
      1 [true (inc offset)]
      (throw (decode-error (format "invalid bool byte 0x%02x" b) {:offset offset})))))

(defn encode-option
  "Encode an Option: `00` for nil, otherwise `01` followed by
  `(encode-value value)`.

    (vec (encode-option encode-varint 5))  ;=> [1 5]
    (vec (encode-option encode-varint nil))  ;=> [0]"
  ^bytes [encode-value value]
  (if (nil? value)
    (byte-array [0])
    (concat-bytes [(byte-array [1]) (encode-value value)])))

(defn decode-option
  "Decode an Option from `bytes` at `offset`: tag `00` gives
  `[nil next-offset]`, tag `01` gives what `(decode-value bytes (inc offset))`
  gives. Refuse any other tag.

    (decode-option decode-varint (byte-array [1 5]) 0)  ;=> [5 2]"
  [decode-value bytes offset]
  (let [b (byte-at bytes offset)]
    (case b
      0 [nil (inc offset)]
      1 (decode-value bytes (inc offset))
      (throw (decode-error (format "invalid Option tag 0x%02x" b) {:offset offset})))))

(defn decode-count
  "Decode a sequence or map length at `offset` and return `[n next-offset]`,
  refusing a count larger than the bytes left: every element takes at least one
  byte, so a larger count is a lie, and refusing it before the loop bounds the
  work a peer's frame can ask for.

    (decode-count (byte-array [2 0 0]) 0)  ;=> [2 1]"
  [^bytes bytes offset]
  (let [[n at] (decode-varint bytes offset)
        left (- (alength bytes) (long at))]
    (when (> n left)
      (throw (decode-error (str "truncated message: a count of " n " at offset " offset
                                " exceeds the " left " bytes left")
                           {:offset offset})))
    [n at]))
