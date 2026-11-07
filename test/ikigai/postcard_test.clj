(ns ikigai.postcard-test
  "Postcard refusals and edge cases beyond wire_vectors.json."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ikigai.postcard :as pc]
            [ikigai.test-util :refer [bs thrown-data]]))

(deftest a-negative-or-oversized-varint-is-refused-at-encode
  (is (thrown? clojure.lang.ExceptionInfo (pc/encode-varint -1)))
  (is (thrown? clojure.lang.ExceptionInfo (pc/encode-varint 18446744073709551616N))))

(deftest a-varint-past-u64-is-refused
  ;; 2^64 does not fit a u64.
  (is (= "varint exceeds u64"
         (:ikigai.test-util/message
          (thrown-data #(pc/decode-varint (bs 0x80 0x80 0x80 0x80 0x80 0x80 0x80 0x80 0x80 0x02) 0)))))
  (is (= "varint too long"
         (:ikigai.test-util/message (thrown-data #(pc/decode-varint (bs (repeat 11 0x80) 0) 0)))))
  (testing "a u32 discriminant"
    (is (= "varint exceeds u32"
           (:ikigai.test-util/message (thrown-data #(pc/decode-varint (bs 0x80 0x80 0x80 0x80 0x10) 0 32)))))))

(deftest a-truncated-read-is-loud
  (let [data (thrown-data #(pc/decode-bytes (bs 0x05 "ab") 0))]
    (is (= :decode (:ikigai.wire/error data)))
    (is (str/includes? (:ikigai.test-util/message data) "truncated"))))

(deftest invalid-utf8-is-a-decode-error
  (is (str/includes? (:ikigai.test-util/message (thrown-data #(pc/decode-string (bs 0x02 0xff 0xfe) 0)))
                     "utf-8")))

(deftest bool-and-option-tags-are-strict
  (is (= "invalid bool byte 0x02" (:ikigai.test-util/message (thrown-data #(pc/decode-bool (bs 2) 0)))))
  (is (= "invalid Option tag 0x07"
         (:ikigai.test-util/message (thrown-data #(pc/decode-option pc/decode-varint (bs 7) 0)))))
  (is (= [5 2] (pc/decode-option pc/decode-varint (bs 1 5) 0))))

(deftest a-count-larger-than-the-bytes-left-is-refused-before-looping
  (is (= [2 1] (pc/decode-count (bs 2 0 0) 0)))
  (is (str/includes? (:ikigai.test-util/message (thrown-data #(pc/decode-count (bs 0xff 0xff 0x03 0) 0)))
                     "exceeds the 1 bytes left")))

(deftest strict-utf8-names-where-it-broke
  (is (= "hi" (pc/utf8-decode (bs "hi"))))
  (is (= {:invalid 1} (pc/utf8-decode (bs 0x68 0xe2 0x82))))
  (is (= {:invalid 0} (pc/utf8-decode (bs 0xc0 0xaf))) "an overlong form")
  (is (= {:invalid 0} (pc/utf8-decode (bs 0xed 0xa0 0x80))) "a surrogate"))
