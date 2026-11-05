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

  No shared vector file pins these yet. Read alongside:
  ikigai-python `src/ikigai/postcard.py` and `tests/test_postcard.py`, and
  ikigai-deno `src/postcard.ts` and `tests/postcard_test.ts`."
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private proof "ikigai-python tests/test_postcard.py")

(defn encode-varint
  "Encode the non-negative integer `n` (at most 2^64 - 1) as an unsigned
  LEB128 varint and return the bytes. 0 is `00`, 127 is `7f`, 128 is `80 01`,
  300 is `ac 02`, and 2^64 - 1 is ten bytes ending `01`. Refuse a negative
  `n` or one above 2^64 - 1."
  [n]
  (scaffold/not-implemented `encode-varint proof {:n n}))

(defn decode-varint
  "Decode an unsigned LEB128 varint from `bytes` at `offset` and return
  `[n next-offset]`. Refuse a truncated varint and one whose value does not
  fit a u64."
  [bytes offset]
  (scaffold/not-implemented `decode-varint proof {:bytes bytes :offset offset}))

(defn encode-bytes
  "Encode the byte array `bs` as its length (a varint) followed by its bytes."
  [bs]
  (scaffold/not-implemented `encode-bytes proof {:bs bs}))

(defn decode-bytes
  "Decode a length-prefixed byte string from `bytes` at `offset` and return
  `[byte-array next-offset]`. Refuse a length that runs past the end."
  [bytes offset]
  (scaffold/not-implemented `decode-bytes proof {:bytes bytes :offset offset}))

(defn encode-string
  "Encode the string `s` as its UTF-8 bytes, length-prefixed."
  [s]
  (scaffold/not-implemented `encode-string proof {:s s}))

(defn decode-string
  "Decode a length-prefixed UTF-8 string from `bytes` at `offset` and return
  `[string next-offset]`. Refuse invalid UTF-8 rather than replacing it."
  [bytes offset]
  (scaffold/not-implemented `decode-string proof {:bytes bytes :offset offset}))

(defn encode-bool
  "Encode `b` as one byte: `00` for false, `01` for true."
  [b]
  (scaffold/not-implemented `encode-bool proof {:b b}))

(defn decode-bool
  "Decode one bool byte from `bytes` at `offset` and return
  `[boolean next-offset]`. Refuse any byte other than `00` or `01`."
  [bytes offset]
  (scaffold/not-implemented `decode-bool proof {:bytes bytes :offset offset}))

(defn encode-option
  "Encode an Option: `00` for nil, otherwise `01` followed by
  `(encode-value value)`."
  [encode-value value]
  (scaffold/not-implemented `encode-option proof {:encode-value encode-value :value value}))

(defn decode-option
  "Decode an Option from `bytes` at `offset`: tag `00` gives
  `[nil next-offset]`, tag `01` gives what `(decode-value bytes (inc offset))`
  gives. Refuse any other tag."
  [decode-value bytes offset]
  (scaffold/not-implemented `decode-option proof
                            {:decode-value decode-value :bytes bytes :offset offset}))
