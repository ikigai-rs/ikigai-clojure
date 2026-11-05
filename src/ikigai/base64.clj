(ns ikigai.base64
  "One base64 for the ecosystem: `ikigai_http::base64`, RFC 4648 section 4.

  The standard alphabet (`+` and `/`), padding required on decode, and only
  the canonical encoding accepted: nonzero pad bits are refused (section 3.5),
  and so is any character outside the alphabet, whitespace included (section
  3.3). URL-safe is not offered.

  `java.util.Base64` is the engine. Its decoder is more lenient than the rule
  (it accepts missing padding and nonzero pad bits), so `decode` re-encodes
  what it decoded and compares it with the input, as ikigai-python does with
  its standard library. A refusal is an `ex-info` whose data carries
  `:ikigai.base64/error \"base64-invalid\"` and never the input.

  Proved by `test/vectors/auth_vectors.json` (`base64`, `base64_refusals`)."
  (:import (java.util Base64)))

(set! *warn-on-reflection* true)

(def ^:private code "base64-invalid")

(defn encode
  "Standard-alphabet base64 of the byte array `bs`, padded: each `base64`
  case's `encoded`, from its `text` (as UTF-8) or its `hex`.

    (encode (.getBytes \"foo\" \"UTF-8\"))  ;=> \"Zm9v\""
  [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn decode
  "Decode the string `s` to a byte array: each `base64` case's `text` or
  `hex`. Refuse, as each `base64_refusals` case's `error` says, anything that
  is not canonical padded standard base64.

    (String. (decode \"Zm9v\") \"UTF-8\")  ;=> \"foo\""
  ^bytes [^String s]
  (let [refuse #(throw (ex-info code {:ikigai.base64/error code}))
        out (if (every? #(< (int %) 128) s)
              (try (.decode (Base64/getDecoder) s)
                   (catch IllegalArgumentException _ (refuse)))
              (refuse))]
    (if (= s (encode out)) out (refuse))))
