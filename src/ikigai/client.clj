(ns ikigai.client
  "Drive a running ikigai kernel over its Unix socket.

  `connect` opens the socket, exchanges hellos (redialing once at an older
  server's version when it is within reach) and returns a connection; each
  verb sends one call and waits for its reply. One call is in flight at a time
  per connection, as the wire is strictly call and reply.

  A typed error from the kernel is thrown as the `ex-info`
  `ikigai.wire/typed-error` builds, so a caller can dispatch on
  `:ikigai.error/kind`; its `ex-message` is the text the Rust kernel prints. A
  connection that cannot be made, or is lost, is an `ex-info` carrying
  `:ikigai.wire/error :connection-lost`; a peer that breaks the protocol,
  `:ikigai.wire/error :protocol`.

  An argument value is a string (sent as its UTF-8 bytes), a byte array, a
  boolean or a number (sent as the REPL grammar spells them), or an argument
  map (`{:inline bytes}`, `{:reference iri}`, `{:content id}`). Argument names
  are strings or keywords.

  `java.net.UnixDomainSocketAddress` with `java.nio.channels.SocketChannel`
  (JDK 16 and later) reach the socket, and babashka includes both, so one
  implementation serves both runtimes. Unlike ikigai-python's client there is
  no read timeout: a Unix-domain `SocketChannel` has no socket timeout, so a
  hung server hangs the call until the server or the caller gives up.

  Proved against a real `ikigai` binary (`test/ikigai/interop_test.clj`)."
  (:refer-clojure :exclude [meta])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ikigai.postcard :as pc]
            [ikigai.wire :as wire])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels SocketChannel)))

(set! *warn-on-reflection* true)

(defn- uid
  "The user id, as the Rust CLI names its per-user directory by it."
  []
  (let [^java.util.List argv ["id" "-u"]
        p (.start (ProcessBuilder. argv))]
    (str/trim (slurp (.getInputStream p)))))

(defn default-socket-path
  "The socket a local kernel listens on when none is named, as the Rust CLI
  and ikigai-python's `default_socket_path` find it:
  `<runtime-dir>/ikigai/kernel.sock`, where `<runtime-dir>` is
  `$XDG_RUNTIME_DIR` when set, else `$TMPDIR` (or `/tmp`) plus `ikigai-<uid>`.

    (default-socket-path)  ;=> \"/var/folders/…/T/ikigai-501/ikigai/kernel.sock\""
  []
  (let [runtime (System/getenv "XDG_RUNTIME_DIR")
        base (if (seq runtime)
               (io/file runtime)
               (io/file (or (not-empty (System/getenv "TMPDIR")) "/tmp")
                        (str "ikigai-" (uid))))]
    (str (io/file base "ikigai" "kernel.sock"))))

(defn- connection-lost [message cause]
  (ex-info message {:ikigai.wire/error :connection-lost} cause))

(defn- protocol-error [message]
  (ex-info message {:ikigai.wire/error :protocol}))

(defn- dial [path]
  (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.connect ch (UnixDomainSocketAddress/of (str path)))
      ch
      (catch java.io.IOException e
        (.close ch)
        (throw (connection-lost (str "cannot reach a kernel server at " path " ("
                                     (or (ex-message e) (.getName (class e)))
                                     "); is `ikigai serve` (or the daemon) running?")
                                e))))))

(defn- hello
  "Dial `path` and exchange hellos offering `version`: the open channel, its
  streams, and the version the server answered with."
  [path version mode]
  (let [ch ^SocketChannel (dial path)
        in (Channels/newInputStream ch)
        out (Channels/newOutputStream ch)
        answer (try
                 (wire/write-frame out (wire/encode-hello version mode))
                 (some-> (wire/read-frame in) wire/decode-hello)
                 (catch java.io.IOException e
                   (.close ch)
                   (throw (protocol-error
                           (str "the kernel server at " path " hung up on the version hello ("
                                (ex-message e) "); it may predate wire v6")))))]
    (when-not answer
      (.close ch)
      (throw (protocol-error
              (str "the kernel server at " path " hung up on, or answered with something"
                   " other than, the version hello; it may predate wire v6"))))
    {:channel ch :in in :out out :answered (:version answer)}))

(defn connect
  "Open a connection to the kernel at the socket `path` (the default when nil)
  and exchange hellos. Refuse a kernel whose version is out of reach with a
  sentence naming both versions. `opts` may give `:capability` (calls then go
  as `IssueAs` under it, which the server clamps to its authenticated
  principal) and `:mode` (the hello's hint, `:verbatim` by default).

  Close the connection with `close`, or use `with-connection`.

    (def k (connect \"/tmp/ik/kernel.sock\"))
    (:text (source k \"urn:iki:fn:toUpper\" {\"in\" \"hi\"}))  ;=> \"HI\"
    (close k)"
  ([] (connect nil))
  ([path] (connect path {}))
  ([path {:keys [capability mode] :or {mode :verbatim}}]
   (let [path (or path (default-socket-path))]
     (loop [offered wire/protocol-version]
       (let [{:keys [answered] :as dialed} (hello path offered mode)
             outcome (wire/connect-version offered answered)]
         (cond
           (= :proceed outcome)
           (-> dialed
               (dissoc :answered)
               (assoc :path (str path) :server-version answered :capability capability
                      :lock (Object.)))

           :else
           (do (.close ^SocketChannel (:channel dialed))
               (if (= :refuse outcome)
                 (throw (protocol-error
                         (str "the kernel server speaks wire v" answered ", this client speaks v"
                              wire/protocol-version " (and down to v" wire/min-protocol-version
                              "); update the older side")))
                 (recur outcome)))))))))

(defn close
  "Close the connection `conn`. A closed connection does not redial."
  [conn]
  (.close ^SocketChannel (:channel conn)))

(defmacro with-connection
  "Like `with-open`, for a connection: evaluate `body` with `sym` bound to the
  connection `init` opens, and close it after, however `body` ends.

    (with-connection [k (connect \"/tmp/ik/kernel.sock\")]
      (:text (source k \"urn:iki:fn:toUpper\" {\"in\" \"hi\"})))  ;=> \"HI\""
  [[sym init] & body]
  `(let [~sym ~init]
     (try ~@body (finally (close ~sym)))))

;; ---------------------------------------------------------------------------
;; Calls
;; ---------------------------------------------------------------------------

(defn argument
  "A value as a wire argument: a string as its UTF-8 bytes, a byte array as
  is, a boolean as `true`/`false`, a number as its decimal text, and an
  argument map (`{:inline b}`, `{:reference iri}`, `{:content id}`) as is.

    (vec (:inline (argument true)))  ;=> [116 114 117 101]"
  [value]
  (cond
    (and (map? value) (some #{:inline :reference :content} (keys value))) value
    (bytes? value) {:inline value}
    (string? value) {:inline (pc/utf8-bytes value)}
    (boolean? value) {:inline (pc/utf8-bytes (str value))}
    (number? value) {:inline (pc/utf8-bytes (str value))}
    :else (throw (ex-info (str "cannot send " (pr-str (type value))
                               " as an argument (use a string or bytes)")
                          {:value value}))))

(defn request
  "The request for `verb` on `iri` with the arguments `args` (a map of name
  to value).

    (:args (request :source \"urn:x\" {:in \"hi\"}))  ;=> {\"in\" {:inline #bytes[…]}}"
  [verb iri args]
  {:verb verb :target iri
   :args (into {} (for [[k v] args] [(if (keyword? k) (name k) (str k)) (argument v)]))})

(defn- round-trip [conn call]
  ;; One call in flight per connection: the lock is the connection's own, made by
  ;; `connect`, so every caller sharing the connection takes the same one.
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  (locking (:lock conn)
    (try
      (wire/write-frame (:out conn) (wire/encode-call call (:server-version conn)))
      (let [payload (wire/read-frame (:in conn))]
        (when-not payload
          (throw (connection-lost "the kernel server closed the connection" nil)))
        (wire/decode-reply payload))
      (catch java.io.IOException e
        (throw (connection-lost (str "the kernel server is unreachable: " (ex-message e)) e))))))

(defn- reply-error
  "The typed error a reply carries, or nil for a success. A flat v6 `Error`
  string becomes an Endpoint error with its `endpoint error: ` prefix removed."
  [reply]
  (case (:reply reply)
    :error-typed (:error reply)
    :error (wire/typed-error :endpoint {:message (str/replace-first (:message reply)
                                                                    #"^endpoint error: " "")})
    nil))

(defn- issue-call [conn req]
  (if-let [cap (:capability conn)]
    {:call :issue-as :request req :capability cap}
    {:call :issue :request req}))

(defn- with-text
  "`rep` with `:text`: its bytes as strict UTF-8, or nil when they are not."
  [rep]
  (let [text (pc/utf8-decode (:bytes rep))]
    (assoc rep :text (when (string? text) text))))

(defn issue
  "Send `req` (a request map) and return the representation the kernel
  answers, with `:cache-status` and `:text`, or throw its typed error."
  [conn req]
  (let [reply (round-trip conn (issue-call conn req))]
    (if (= :resolved (:reply reply))
      (with-text (assoc (:representation reply) :cache-status (:cache-status reply)))
      (throw (or (reply-error reply)
                 (protocol-error (str "unexpected reply to a call: " (:reply reply))))))))

(defn source
  "Read the resource `iri` with the named arguments `args` (a map of name to
  value) and return its representation: `{:bytes b :media-type m :params p
  :expiry e :cache-status c :text t}`, where `:text` is the bytes as UTF-8
  (nil when they are not).

    (:text (source k \"urn:iki:fn:toUpper\" {\"in\" \"hi\"}))  ;=> \"HI\""
  ([conn iri] (source conn iri {}))
  ([conn iri args] (issue conn (request :source iri args))))

(defn sink
  "Write `content` to the resource `iri` with the named arguments `args` and
  return the kernel's answer. `content` rides as the `content` argument, the
  wire's convention for the piped value; nil sends none.

    (sink k \"urn:iki:ledger:comment\" \"a note\" {\"item\" \"418\"})"
  ([conn iri content] (sink conn iri content {}))
  ([conn iri content args]
   (issue conn (request :sink iri (cond-> args (some? content) (assoc "content" content))))))

(defn exists?
  "True when the kernel answers Exists on `iri` with `true`.

    (exists? k \"urn:iki:fn:toUpper\")  ;=> true"
  ([conn iri] (exists? conn iri {}))
  ([conn iri args]
   (= "true" (some-> (:text (issue conn (request :exists iri args))) str/trim))))

(defn delete!
  "Delete the resource `iri` and return the kernel's answer.

    (delete! k \"urn:iki:tutorial:ttt:stored:1:1\")"
  ([conn iri] (delete! conn iri {}))
  ([conn iri args] (issue conn (request :delete iri args))))

(defn meta
  "The resource `iri`'s description, in the media type `as` (for example
  `application/json` or `text/turtle`; nil for the server's default face).

    (:text (meta k \"urn:iki:fn:toUpper\" \"text/turtle\"))"
  ([conn iri] (meta conn iri nil))
  ([conn iri as]
   (issue conn (request :meta iri (if as {"as" as} {})))))

(defn describe
  "The resource `iri`'s description as Clojure data (its Meta rendered as
  JSON, parsed, keys as strings), or nil when it has none.

    (get (describe k \"urn:iki:fn:toUpper\") \"id\")  ;=> \"toUpper\""
  [conn iri]
  (try
    (some-> (meta conn iri "application/json") :text json/read-str)
    (catch clojure.lang.ExceptionInfo e
      (if (wire/typed-error? e) nil (throw e)))))

(defn entries
  "The catalog: every binding the server's space enumerates (already scoped to
  the connection's capability), as `{:pattern p :endpoint id :origin o}`
  maps; nil when the space does not enumerate.

    (some #(= \"urn:iki:fn:toUpper\" (:pattern %)) (entries k))  ;=> true"
  [conn]
  (let [reply (round-trip conn {:call :entries})]
    (if (= :entries (:reply reply))
      (:entries reply)
      (throw (or (reply-error reply)
                 (protocol-error (str "unexpected reply to Entries: " (:reply reply))))))))

(defn cached?
  "True when sourcing `iri` with `args` would be answered from the server's
  cache. (The probe runs under the server's own authority.)"
  ([conn iri] (cached? conn iri {}))
  ([conn iri args]
   (let [reply (round-trip conn {:call :is-cached :request (request :source iri args)})]
     (and (= :cached (:reply reply)) (:cached reply)))))

(defn source-traced
  "Source `iri` and record the resolution: `[representation events]`, where
  each event is a span of the execution tree (`:target`, `:span`, `:parent`,
  `:cache-hit`, `:capability`, `:notes`).

    (map :target (second (source-traced k \"urn:iki:fn:toUpper\" {\"in\" \"hi\"})))"
  ([conn iri] (source-traced conn iri {}))
  ([conn iri args]
   (let [reply (round-trip conn {:call :issue-traced :request (request :source iri args)
                                 :capability (or (:capability conn) :root)
                                 :trace {:trace-id 1 :parent-span nil}})]
     (if (= :resolved-traced (:reply reply))
       [(with-text (assoc (:representation reply) :cache-status (:cache-status reply)))
        (:events reply)]
       (throw (or (reply-error reply)
                  (protocol-error (str "unexpected reply to IssueTraced: " (:reply reply)))))))))
