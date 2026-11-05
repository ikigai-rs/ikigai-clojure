(ns ikigai.client
  "Drive a running ikigai kernel over its Unix socket.

  `connect` opens the socket, exchanges hellos and returns a connection; each
  verb sends one call and waits for its reply. A typed error from the kernel
  is thrown as the `ex-info` `ikigai.wire/typed-error` builds, so a caller can
  dispatch on `:ikigai.error/kind`.

  On the JVM, `java.net.UnixDomainSocketAddress` and
  `java.nio.channels.SocketChannel` (JDK 16 and later) reach the socket, and
  babashka includes both, so one implementation serves both runtimes.

  No vector file proves this namespace: it is proved against a real `ikigai`
  binary (ROADMAP.md, step 4). Read alongside ikigai-python
  `src/ikigai/client.py` and `tests/test_integration.py`, and ikigai-deno
  `src/client.ts`."
  (:refer-clojure :exclude [meta])
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private proof "an integration test against the ikigai binary (ROADMAP.md, step 4)")

(defn default-socket-path
  "The socket a local kernel listens on when none is named, found as
  ikigai-python's `default_socket_path` finds it."
  []
  (scaffold/not-implemented `default-socket-path proof {}))

(defn connect
  "Open a connection to the kernel at the socket `path` (the default when nil)
  and exchange hellos. Refuse a kernel whose version is out of reach with a
  sentence naming both versions."
  [path]
  (scaffold/not-implemented `connect proof {:path path}))

(defn close
  "Close the connection `conn`."
  [conn]
  (scaffold/not-implemented `close proof {:conn conn}))

(defn source
  "Read the resource `iri` with the named arguments `args` (a map of name to
  value) and return its representation: `{:bytes b :media-type m :text t}`
  and its expiry."
  [conn iri args]
  (scaffold/not-implemented `source proof {:conn conn :iri iri :args args}))

(defn sink
  "Write `content` to the resource `iri` with the named arguments `args` and
  return the kernel's answer."
  [conn iri content args]
  (scaffold/not-implemented `sink proof {:conn conn :iri iri :content content :args args}))

(defn exists?
  "True when the resource `iri` exists."
  [conn iri args]
  (scaffold/not-implemented `exists? proof {:conn conn :iri iri :args args}))

(defn delete!
  "Delete the resource `iri` and return the kernel's answer."
  [conn iri args]
  (scaffold/not-implemented `delete! proof {:conn conn :iri iri :args args}))

(defn meta
  "The resource `iri`'s description, in the media type `as` (for example
  `application/json` or `text/turtle`)."
  [conn iri as]
  (scaffold/not-implemented `meta proof {:conn conn :iri iri :as as}))
