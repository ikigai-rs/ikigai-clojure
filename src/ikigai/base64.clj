(ns ikigai.base64
  "One base64 for the ecosystem: `ikigai_http::base64`, RFC 4648 section 4.

  The standard alphabet (`+` and `/`), padding required on decode, and only
  the canonical encoding accepted. `java.util.Base64` is the obvious engine;
  check its decoder against the refusal cases, which are stricter than its
  defaults.

  Proved by `test/vectors/auth_vectors.json` (`base64`, `base64_refusals`).
  Read alongside ikigai-python `src/ikigai/base64.py`."
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private proof "test/vectors/auth_vectors.json")

(defn encode
  "Encode the byte array `bs`: each `base64` case's `encoded`, from its
  `text` (as UTF-8) or its `hex`."
  [bs]
  (scaffold/not-implemented `encode proof {:bs bs}))

(defn decode
  "Decode the string `s` to a byte array: each `base64` case's `text` or
  `hex`. Refuse as each `base64_refusals` case's `error` says."
  [s]
  (scaffold/not-implemented `decode proof {:s s}))
