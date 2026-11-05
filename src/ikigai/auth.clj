(ns ikigai.auth
  "`Authorization` header values: `ikigai_http::auth`.

  Build `Basic` (RFC 7617) and `Bearer` (RFC 6750) credentials, and recognize
  the scheme of a header value (RFC 9110 section 11). A module that sends or
  reads an `Authorization` header calls these rather than formatting
  `\"Basic \"` and base64 by hand.

  A refusal is an `ex-info` whose message and data name the broken rule by a
  stable code (`:ikigai.auth/error`, such as `basic-user-id-colon`), and never
  hold the user id, the password or the token. A built header value is a plain
  string: it is a credential, so do not log it.

  Proved by `test/vectors/auth_vectors.json` (`basic`, `basic_refusals`,
  `bearer`, `bearer_refusals`, `scheme`)."
  (:require [clojure.string :as str]
            [ikigai.base64 :as base64]))

(defn- refuse [code]
  (throw (ex-info code {:ikigai.auth/error code})))

(def ^:private control
  "C0, DEL and C1."
  #"[\x00-\x1f\x7f-\x9f]")

(defn basic
  "The `Basic` header value for `user` and `password`: base64 of
  `user:password` in UTF-8. Refused in this order, so every face reports the
  same code for the same input: a colon in the user id
  (`basic-user-id-colon`); a control character (C0, DEL or C1) in the user id
  (`basic-user-id-control`), then in the password (`basic-password-control`).
  Empty parts are legal.

    (basic \"Aladdin\" \"open sesame\")  ;=> \"Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==\""
  [user password]
  (cond
    (str/includes? user ":") (refuse "basic-user-id-colon")
    (re-find control user) (refuse "basic-user-id-control")
    (re-find control password) (refuse "basic-password-control")
    :else (str "Basic " (base64/encode (.getBytes (str user ":" password) "UTF-8")))))

(defn bearer
  "The `Bearer` header value for `token` (RFC 6750 section 2.1: `b64token`).
  Refuse anything else as `bearer-token-syntax`.

    (bearer \"mF_9.B5f-4.1JqM\")  ;=> \"Bearer mF_9.B5f-4.1JqM\""
  [token]
  (if (re-matches #"[A-Za-z0-9\-._~+/]+=*" token)
    (str "Bearer " token)
    (refuse "bearer-token-syntax")))

(defn scheme
  "Split an incoming `Authorization` value into its scheme and the credentials
  after it: `{:scheme s :name n :credentials c}`, where `:scheme` is `basic`,
  `bearer` or `other` and `:name` the registered spelling of a known scheme
  (the spelling that arrived otherwise). Spaces and tabs around the value and
  between scheme and credentials are skipped; the credentials come back
  unvalidated and possibly empty. Nil when there is no scheme or it is not an
  RFC 9110 token.

    (scheme \"basic QWxh\")  ;=> {:scheme \"basic\", :name \"Basic\", :credentials \"QWxh\"}"
  [value]
  (let [value (-> value (str/replace #"^[ \t]+" "") (str/replace #"[ \t]+$" ""))
        [_ name rest-of] (re-matches #"(?s)([^ \t]*)(.*)" value)]
    (when (re-matches #"[A-Za-z0-9!#$%&'*+\-.^_`|~]+" name)
      (merge (case (str/lower-case name)
               "basic" {:scheme "basic" :name "Basic"}
               "bearer" {:scheme "bearer" :name "Bearer"}
               {:scheme "other" :name name})
             {:credentials (str/replace rest-of #"^[ \t]+" "")}))))
