(ns ikigai.test-util
  "Small helpers the tests share: byte literals, and comparing values that hold
  byte arrays or typed errors (neither compares by value)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(defn bs
  "A byte array from integers 0 to 255, strings (as UTF-8) and byte arrays, in
  order: `(bs 0x0e \"urn:test:upper\")`."
  ^bytes [& parts]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [p parts]
      (cond
        (integer? p) (.write out (int p))
        (string? p) (let [^bytes b (.getBytes ^String p "UTF-8")] (.write out b 0 (alength b)))
        (bytes? p) (.write out ^bytes p 0 (alength ^bytes p))
        (sequential? p) (doseq [b p] (.write out (int b)))
        :else (throw (ex-info "not a byte part" {:part p}))))
    (.toByteArray out)))

(defn unhex
  "The bytes the hex string `s` spells."
  ^bytes [s]
  (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16)) (partition 2 s))))

(defn ->hex
  "The bytes of `b` as lowercase hex."
  [b]
  (str/join (map #(format "%02x" (bit-and % 0xff)) b)))

(defn plain
  "`x` with every byte array as a vector of its bytes and every exception as its
  data, so two values compare by value with `=`."
  [x]
  (walk/postwalk (fn [v]
                   (cond
                     (bytes? v) (vec v)
                     (instance? clojure.lang.ExceptionInfo v) {:ex-data (ex-data v)}
                     :else v))
                 x))

(defn thrown-data
  "The `ex-data` of what calling `f` throws, or `::none`."
  [f]
  (try (f) ::none
       (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) ::message (ex-message e)))))
