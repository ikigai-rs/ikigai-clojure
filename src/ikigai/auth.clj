(ns ikigai.auth
  "`Authorization` header values: `ikigai_http::auth`.

  Build `Basic` (RFC 7617) and `Bearer` (RFC 6750) credentials, and recognize
  the scheme of a header value. A refusal names the broken rule by a stable
  code (such as `basic-user-id-colon`) and never holds the credential.

  Proved by `test/vectors/auth_vectors.json` (`basic`, `basic_refusals`,
  `bearer`, `bearer_refusals`, `scheme`). Read alongside ikigai-python
  `src/ikigai/auth.py`."
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private proof "test/vectors/auth_vectors.json")

(defn basic
  "The `Basic` header value for `user` and `password`: each `basic` case's
  `expect`. Refuse as each `basic_refusals` case's `error` says, with that
  code in the `ex-info` data and neither credential in the message."
  [user password]
  (scaffold/not-implemented `basic proof {:user user :password (when password :redacted)}))

(defn bearer
  "The `Bearer` header value for `token`: each `bearer` case's `expect`.
  Refuse as each `bearer_refusals` case's `error` says."
  [token]
  (scaffold/not-implemented `bearer proof {:token (when token :redacted)}))

(defn scheme
  "Recognize the scheme of the header value `value` and return
  `{:scheme s :name n :credentials c}`, or nil: each `scheme` case's
  `expect`."
  [value]
  (scaffold/not-implemented `scheme proof {:value (when value :redacted)}))
