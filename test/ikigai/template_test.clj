(ns ikigai.template-test
  "`ikigai.template`: the cases ikigai-python's `tests/test_template.py` ports
  from core's `grammar.rs`, and the rule's edges core states in prose."
  (:require [clojure.test :refer [deftest is]]
            [ikigai.template :as t]
            [ikigai.test-util :refer [thrown-data]]))

(deftest an-exact-template-matches-only-itself
  (let [x (t/parse "urn:test:to-upper")]
    (is (= {} (t/match-iri x "urn:test:to-upper")))
    (is (nil? (t/match-iri x "urn:test:to-lower")))
    (is (t/exact? x))))

(deftest a-trailing-variable-captures
  (let [x (t/parse "urn:test:echo/{message}")]
    (is (= {"message" "hello"} (t/match-iri x "urn:test:echo/hello")))
    (is (nil? (t/match-iri x "urn:test:echo/")) "an empty capture")
    (is (nil? (t/match-iri x "urn:other:echo/hi")))))

(deftest a-middle-variable-captures
  (let [x (t/parse "urn:r:{id}/data")]
    (is (= {"id" "42"} (t/match-iri x "urn:r:42/data")))
    (is (nil? (t/match-iri x "urn:r:42/other")))
    (is (= "urn:r:7/data" (t/expand x (t/match-iri x "urn:r:7/data"))))
    (is (nil? (t/expand x {})) "a missing variable")))

(deftest malformed-and-ambiguous-templates-are-refused
  (doseq [[source detail] [["urn:{a}{b}" "adjacent variables are ambiguous in `urn:{a}{b}`"]
                           ["urn:{a" "unclosed '{' in `urn:{a`"]
                           ["urn:{}" "invalid variable `{}` in `urn:{}`"]]]
    (is (= (str "invalid URI template: " detail)
           (:ikigai.test-util/message (thrown-data #(t/parse source)))))))

(deftest a-middle-variable-is-lazy-and-the-last-takes-the-rest
  (let [x (t/parse "urn:iki:tutorial:ttt:stored:{x}:{y}")]
    (is (= {"x" "1" "y" "2"} (t/match-iri x "urn:iki:tutorial:ttt:stored:1:2")))
    (is (= {"x" "1" "y" "2:3"} (t/match-iri x "urn:iki:tutorial:ttt:stored:1:2:3")))
    (is (= {"x" "-1" "y" "0"} (t/match-iri x "urn:iki:tutorial:ttt:stored:-1:0")))
    (is (nil? (t/match-iri x "urn:iki:tutorial:ttt:stored::2")))
    (is (nil? (t/match-iri x "urn:iki:tutorial:ttt:stored:1:")))
    (is (nil? (t/match-iri x "urn:iki:tutorial:ttt:stored:12")))
    (is (= {"x" "{x}" "y" "{y}"} (t/match-iri x (:source x))) "a template matches its own source")))

(deftest a-capture-has-no-character-class
  (is (= {"v" "a b/{c}"} (t/match-iri (t/parse "urn:x:{v}") "urn:x:a b/{c}"))))

(deftest a-literal-after-the-last-variable-must-close-the-iri
  (let [x (t/parse "urn:r:{id}/data")]
    (is (nil? (t/match-iri x "urn:r:1/data/more")))
    (is (nil? (t/match-iri x "urn:r:1/data/data")))))

(deftest variable-names-are-ascii-word-characters
  (is (= ["a_1" "B2"] (t/variables (t/parse "urn:{a_1}:{B2}"))))
  (doseq [bad ["urn:{a-b}" "urn:{é}" "urn:{٣}" "urn:{a b}" "urn:{+a}" "urn:{a{b}"]]
    (is (re-find #"invalid variable" (:ikigai.test-util/message (thrown-data #(t/parse bad)))) bad)))

(deftest a-lone-close-brace-is-literal
  (is (= {"x" "1"} (t/match-iri (t/parse "urn:a}b:{x}") "urn:a}b:1"))))
