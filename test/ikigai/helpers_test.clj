(ns ikigai.helpers-test
  "The helpers against their shared vectors: `ikigai.capability`
  (`capability_vectors.json`), `ikigai.html` (`html_vectors.json`), `ikigai.auth`
  and `ikigai.base64` (`auth_vectors.json`), plus `ikigai.iri`'s own cases."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ikigai.auth :as auth]
            [ikigai.base64 :as base64]
            [ikigai.capability :as cap]
            [ikigai.html :as html]
            [ikigai.iri :as iri]
            [ikigai.test-util :refer [thrown-data unhex]]
            [ikigai.vectors :as vectors]
            [ikigai.wire :as wire]))

;; --- capability_vectors.json -------------------------------------------------------

(def ^:private capability-file "capability_vectors.json")

(defn- rows [file section] (vectors/rows file section))

(deftest deny-scopes-are-cores
  (doseq [row (rows capability-file "deny_scopes")]
    (is (= (get row "is_deny_scope") (cap/deny-scope? (get row "scope"))) (get row "scope"))))

(deftest coverage-is-cores
  (doseq [row (rows capability-file "coverage")
          :let [c (cap/capability (get row "capability"))
                scope (get row "scope")]]
    (testing [(get row "capability") scope]
      (is (= (get row "allows") (cap/allows? c scope)))
      (is (= (get row "satisfies") (cap/satisfies? c scope))))))

(deftest principals-are-cores
  (doseq [row (rows capability-file "principals")]
    (is (= (get row "principal") (cap/principal (cap/capability (get row "capability"))))
        (pr-str (get row "capability")))))

(deftest floors-are-cores
  (doseq [floor (rows capability-file "floors")
          row (get floor "verbs")]
    (testing [(get floor "name") (get row "verb")]
      (is (= (get row "required_scopes")
             (cap/required-scopes (get floor "description") (wire/parse-verb (get row "verb"))))))))

(deftest denials-are-cores
  (doseq [row (rows capability-file "denials")
          :let [answer (cap/refusal (get row "description") (wire/parse-verb (get row "verb"))
                                    (get row "target") (cap/capability (get row "capability")))]]
    (testing (get row "name")
      (is (= (get row "denied") (:denied answer)))
      (is (= (get row "unresolved") (:unresolved answer)))
      (when-let [denied (:denied answer)]
        (is (= (str "denied: " denied)
               (ex-message (wire/typed-error :denied {:message denied}))))))))

(deftest the-vectors-pin-concealment
  (let [concealed (filter #(contains? % "unresolved") (rows capability-file "denials"))]
    (is (>= (count concealed) 12))
    (is (= #{"Source" "Sink" "Exists" "Delete" "Meta"} (set (map #(get % "verb") concealed))))))

(deftest a-family-denial-names-what-satisfies-it
  (is (= (str "capability does not grant `urn:cap:script:run:open-*` (declared by"
              " `urn:script:open-door`; any grant under `urn:cap:script:run:open-` satisfies it)")
         (cap/denial-message "urn:cap:script:run:open-*" "urn:script:open-door")))
  (is (= "capability does not grant `*` (declared by `urn:x`; any grant at all satisfies it)"
         (cap/denial-message "*" "urn:x"))))

;; --- html_vectors.json -------------------------------------------------------------

(def ^:private html-file "html_vectors.json")

(deftest escape
  (doseq [{given "in" want "out"} (rows html-file "escape")]
    (is (= want (html/html-text given)))
    (is (= want (html/attr given)))))

(deftest url-segment-and-back
  (doseq [{given "in" want "out"} (rows html-file "url_segment")]
    (is (= want (html/url-segment given)))
    (is (= given (html/percent-decode want false)))
    (is (= given (html/percent-decode want true)))))

(deftest safe-href
  (doseq [{given "in" want "out"} (rows html-file "safe_href")]
    (is (= want (html/safe-href given)) (pr-str given))))

(deftest json-attr
  (doseq [{given "in" want "out"} (rows html-file "json_attr")]
    (is (= want (html/json-attr given)))))

(deftest percent-decode
  (doseq [{given "in" plus "plus_is_space" :as row} (rows html-file "percent_decode")]
    (testing (pr-str given plus)
      (case (get row "error")
        nil (do (is (= (get row "out") (html/percent-decode given plus)))
                (is (= (get row "bytes") (mapv #(bit-and % 0xff) (html/percent-decode-bytes given plus)))))
        "malformed_escape"
        (doseq [f [html/percent-decode html/percent-decode-bytes]]
          (is (= [:malformed-escape (get row "offset")]
                 ((juxt :ikigai.html/error :offset) (thrown-data #(f given plus))))))
        "invalid_utf8"
        (do (is (= (get row "bytes") (mapv #(bit-and % 0xff) (html/percent-decode-bytes given plus))))
            (is (= :invalid-utf8 (:ikigai.html/error (thrown-data #(html/percent-decode given plus))))))))))

(deftest form-pairs
  (doseq [{given "in" :as row} (rows html-file "form_pairs")]
    (testing (pr-str given)
      (if-let [error (get row "error")]
        (is (= [(keyword (str/replace error "_" "-")) (get row "offset")]
               ((juxt :ikigai.html/error :offset) (thrown-data #(html/form-pairs given)))))
        (is (= (get row "out") (html/form-pairs given)))))))

(deftest the-refusals-read-as-ikigai-fns-display
  (is (= "percent_decode: malformed percent-escape at byte 3"
         (:ikigai.test-util/message (thrown-data #(html/percent-decode "日%0g" true)))))
  (is (= "percent_decode: decoded bytes are not UTF-8"
         (:ikigai.test-util/message (thrown-data #(html/percent-decode "%FF" false)))))
  (is (= "form_pairs: malformed percent-escape at byte 12"
         (:ikigai.test-util/message (thrown-data #(html/form-pairs "日=ok&x=%41%")))))
  (is (= "form_pairs: decoded bytes are not UTF-8 at byte 12"
         (:ikigai.test-util/message (thrown-data #(html/form-pairs "ok=1&bad=%41%FF")))))
  (is (= [-1 32 43] (vec (html/percent-decode-bytes (.getBytes "%FF+%2b" "UTF-8") true)))))

;; --- auth_vectors.json -------------------------------------------------------------

(def ^:private auth-file "auth_vectors.json")

(deftest basic
  (doseq [{:strs [user password expect]} (rows auth-file "basic")]
    (is (= expect (auth/basic user password)))))

(deftest basic-refusals
  (doseq [{:strs [name user password error]} (rows auth-file "basic_refusals")
          :let [data (thrown-data #(auth/basic user password))]]
    (is (= error (:ikigai.auth/error data)) name)
    (is (not (re-find #"(?i)sesame" (pr-str data))) "the refusal never holds the credential")))

(deftest bearer
  (doseq [{:strs [token expect]} (rows auth-file "bearer")]
    (is (= expect (auth/bearer token))))
  (doseq [{:strs [name token error]} (rows auth-file "bearer_refusals")]
    (is (= error (:ikigai.auth/error (thrown-data #(auth/bearer token)))) name)))

(deftest scheme
  (doseq [{:strs [name value expect]} (rows auth-file "scheme")]
    (is (= (when expect {:scheme (get expect "scheme") :name (get expect "name")
                         :credentials (get expect "credentials")})
           (auth/scheme value))
        name)))

(deftest base64
  (doseq [{:strs [name encoded text hex]} (rows auth-file "base64")
          :let [data (if hex (unhex hex) (.getBytes ^String text "UTF-8"))]]
    (is (= encoded (base64/encode data)) name)
    (is (= (vec data) (vec (base64/decode encoded))) name))
  (doseq [{:strs [name encoded error]} (rows auth-file "base64_refusals")]
    (is (= error (:ikigai.base64/error (thrown-data #(base64/decode encoded)))) name))
  (testing "nonzero pad bits, which the JDK decoder accepts, and non-ASCII text"
    (is (= "base64-invalid" (:ikigai.base64/error (thrown-data #(base64/decode "Zh==")))))
    (is (= "base64-invalid" (:ikigai.base64/error (thrown-data #(base64/decode "Zm9é")))))))

;; --- ikigai.iri ---------------------------------------------------------------------

(deftest iri-is-cores-oxiri
  (is (= [true true false false] (map iri/iri? ["urn:cap:core:read" "urn:cls:a%3E%20b"
                                                 "not%20a%20class" "http://ok%3E%20.%20%3Curn:z"])))
  (is (iri/iri? "http://[::1]/x"))
  (is (iri/iri? "http://u@h:80/p?q#f"))
  (is (not (iri/iri? "urn:x:two words")))
  (is (not (iri/iri? "http://[::1/x")) "an unclosed bracket"))

(deftest ipv6-is-rusts-grammar
  (is (= [true true true false false true false true]
         (map iri/ipv6? ["::1" "1:2:3:4:5:6:7::" "::ffff:1.2.3.4" "1::2::3" "fe80::1%en0"
                         "1:2:3:4:5:6:7:8" "1:2:3:4:5:6:7:8:9" "::"]))))

(deftest encode-name-is-cores
  (is (= "toUpper" (iri/encode-name "toUpper")))
  (is (= "naïve" (iri/encode-name "naïve")))
  (is (= "50%25%20off" (iri/encode-name "50% off")))
  (is (= "http://%5B::1%5D/x" (iri/encode-name "http://[::1]/x")))
  (is (= "a%7Fb%C2%85c" (iri/encode-name (str "a" (char 0x7f) "b" (char 0x85) "c"))))
  (is (= "%EE%80%80%EF%BF%BF" (iri/encode-name (str (char 0xe000) (char 0xffff)))))
  (is (= "a%23b%3Fc" (iri/encode-name "a#b?c"))))
