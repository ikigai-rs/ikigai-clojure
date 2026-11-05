(ns ikigai.client-test
  "`ikigai.client` against fake servers on a Unix socket: the hello's version
  negotiation and its refusals, and the argument coercion. The client against a
  real kernel is `ikigai.interop-test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ikigai.client :as client]
            [ikigai.interop :as interop]
            [ikigai.test-util :refer [thrown-data]]
            [ikigai.wire :as wire])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel)))

(defn- fake-server
  "Listen at a scratch socket and answer each of `sessions` in turn: each is a
  function of the accepted connection's input and output streams. Returns
  `[socket-path done]`, `done` a future of the sessions."
  [& sessions]
  (let [dir (interop/scratch-dir)
        path (str (io/file dir "s.sock"))
        server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of path)))
        done (future
               (try
                 (doseq [session sessions]
                   (with-open [ch (.accept server)]
                     (session (Channels/newInputStream ch) (Channels/newOutputStream ch))))
                 (finally (.close server) (interop/delete-tree dir))))]
    [path done]))

(defn- answer-hello [version]
  (fn [in out]
    (wire/read-frame in)
    (wire/write-frame out (wire/encode-hello version))))

(deftest a-version-out-of-reach-names-both-versions
  (let [[path done] (fake-server (answer-hello 10))
        message (:ikigai.test-util/message (thrown-data #(client/connect path)))]
    @done
    (is (re-find #"v10.*v9" message) message)))

(deftest an-older-server-in-reach-is-redialed-at-its-version
  (let [offers (atom [])
        [path done] (fake-server
                     (fn [in out]
                       (swap! offers conj (:version (wire/decode-hello (wire/read-frame in))))
                       (wire/write-frame out (wire/encode-hello 7)))
                     (fn [in out]
                       (swap! offers conj (:version (wire/decode-hello (wire/read-frame in))))
                       (wire/write-frame out (wire/encode-hello 7))
                       (let [call (wire/decode-call (wire/read-frame in))]
                         (wire/write-frame
                          out (wire/encode-reply
                               {:reply :resolved :cache-status :miss
                                :representation (wire/representation
                                                 (.getBytes (str (get-in call [:request :target]))
                                                            "UTF-8")
                                                 "text/plain")}
                               7)))))]
    (client/with-connection [k (client/connect path)]
      (is (= 7 (:server-version k)))
      (is (= "urn:x:echo" (:text (client/source k "urn:x:echo")))))
    @done
    (is (= [9 7] @offers))))

(deftest a-hang-up-on-the-hello-is-diagnosed
  (let [[path done] (fake-server (fn [in _out] (wire/read-frame in)))
        data (thrown-data #(client/connect path))]
    @done
    (is (= :protocol (:ikigai.wire/error data)))
    (is (str/includes? (:ikigai.test-util/message data) "hung up"))))

(deftest no-server-is-a-lost-connection-that-says-what-to-start
  (let [data (thrown-data #(client/connect "/tmp/ikc-nobody-here.sock"))]
    (is (= :connection-lost (:ikigai.wire/error data)))
    (is (str/includes? (:ikigai.test-util/message data) "ikigai serve"))))

(deftest a-typed-error-from-the-server-is-thrown-as-is
  (let [[path done] (fake-server
                     (fn [in out]
                       ((answer-hello 9) in out)
                       (wire/read-frame in)
                       (wire/write-frame out (wire/encode-reply
                                              {:reply :error-typed
                                               :error (wire/typed-error :conflict {:message "1,1 is taken"})}
                                              9))
                       (wire/read-frame in)
                       (wire/write-frame out (wire/encode-reply {:reply :error :message "endpoint error: boom"} 9))))]
    (client/with-connection [k (client/connect path)]
      (let [e (thrown-data #(client/sink k "urn:t:move" "X"))]
        (is (= [:conflict "conflict: 1,1 is taken"] [(:ikigai.error/kind e) (:ikigai.test-util/message e)])))
      (testing "a flat v6 error string loses its prefix"
        (is (= [:endpoint "boom"] ((juxt :ikigai.error/kind :message) (thrown-data #(client/source k "urn:t:x")))))))
    @done))

(deftest arguments-are-coerced-as-the-repl-grammar-spells-them
  (is (= {"in" "hi" "n" "3" "b" "true" "raw" [1 2 0]}
         (update-vals (:args (client/request :source "urn:x" {:in "hi" "n" 3 "b" true
                                                               "raw" (byte-array [1 2 0])}))
                      (fn [{b :inline}] (if (some zero? b) (vec b) (String. ^bytes b "UTF-8"))))))
  (is (= {:reference "urn:y"} (get-in (client/request :source "urn:x" {"r" {:reference "urn:y"}}) [:args "r"])))
  (is (thrown? clojure.lang.ExceptionInfo (client/request :source "urn:x" {"bad" (Object.)}))))

(deftest the-default-socket-is-the-rust-clis
  (is (str/ends-with? (client/default-socket-path) "/ikigai/kernel.sock")))
