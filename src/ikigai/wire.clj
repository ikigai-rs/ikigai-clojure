(ns ikigai.wire
  "The ikigai IPC wire protocol: calls, replies, the hello, framing, and the
  typed errors that cross it.

  Mirrors `ikigai-wire` (Rust) at protocol version 9. The codec is postcard
  (see `ikigai.postcard`), which does not describe itself, so every type here
  restates a Rust layout field for field; the Rust declaration in
  ikigai-cli `crates/ikigai-wire/src/lib.rs` is the normative source.

  A frame is a u32 big-endian length, then the payload; a frame above 64 MiB
  is refused before anything is allocated. The first frame each way is the
  hello: the bytes `IKWH`, a u32 big-endian version and a one-byte mode,
  deliberately not postcard. A peer accepts a hello from version 7 through 9
  and then speaks the peer's version.

  A failure crosses the wire typed (`Reply::ErrorTyped`, tag 5, then the
  error's own tag). Here a typed error is an `ex-info` whose data carries
  `:ikigai.error/kind` (a keyword such as `:denied` or `:conflict`) and the
  fields of that kind.

  Proved by `test/vectors/failure_class_vectors.json` (the typed errors: tag,
  class, display, HTTP status) and, until a shared wire vector file exists, by
  the byte fixtures in ikigai-python `tests/test_wire.py` and
  `tests/test_hello.py`."
  (:require [ikigai.scaffold :as scaffold]))

(def protocol-version
  "The wire version this face speaks: 9, as ikigai-wire's `PROTOCOL_VERSION`."
  9)

(def min-protocol-version
  "The oldest peer version this face still accepts in a hello: 7."
  7)

(def ^:private failure-proof "test/vectors/failure_class_vectors.json")
(def ^:private wire-proof "ikigai-python tests/test_wire.py and tests/test_hello.py")

(defn encode-hello
  "Encode a hello for `version` and `mode` (a mode byte, as ikigai-wire's
  `Hello`): `IKWH`, the version as u32 big-endian, the mode byte."
  [version mode]
  (scaffold/not-implemented `encode-hello wire-proof {:version version :mode mode}))

(defn decode-hello
  "Decode a hello payload and return `{:version v :mode m}`, or nil when the
  payload does not start with `IKWH`. Ignore trailing bytes: they are the
  hello's extension point."
  [payload]
  (scaffold/not-implemented `decode-hello wire-proof {:payload payload}))

(defn encode-call
  "Encode a call (a map naming its variant and fields, as ikigai-wire's
  `Call`) to its postcard payload, for a peer that spoke `peer-version`."
  [call peer-version]
  (scaffold/not-implemented `encode-call wire-proof {:call call :peer-version peer-version}))

(defn decode-call
  "Decode a call payload into the map `encode-call` takes. Refuse an unknown
  variant by naming the protocol version, and refuse trailing bytes."
  [payload]
  (scaffold/not-implemented `decode-call wire-proof {:payload payload}))

(defn encode-reply
  "Encode a reply (a representation, a typed error, or another variant of
  ikigai-wire's `Reply`) for a peer that spoke `peer-version`. To a v7 peer a
  conflict goes as `Endpoint(\"conflict: …\")`, the bytes a v7 peer expects."
  [reply peer-version]
  (scaffold/not-implemented `encode-reply wire-proof {:reply reply :peer-version peer-version}))

(defn decode-reply
  "Decode a reply payload. A typed error comes back as the `ex-info` that
  `typed-error` builds, not thrown: the caller decides whether to throw it."
  [payload]
  (scaffold/not-implemented `decode-reply wire-proof {:payload payload}))

(defn write-frame
  "Write `payload` to the `java.io.OutputStream` `out` as one frame: its
  length as u32 big-endian, then the bytes. Refuse a payload above 64 MiB."
  [out payload]
  (scaffold/not-implemented `write-frame wire-proof {:out out :payload payload}))

(defn read-frame
  "Read one frame from the `java.io.InputStream` `in` and return its payload,
  or nil at a clean end of stream before a header. Refuse a header above
  64 MiB before allocating, and a stream that ends mid-frame."
  [in]
  (scaffold/not-implemented `read-frame wire-proof {:in in}))

(defn typed-error
  "Build the typed error of kind `kind` (`:unresolved`, `:missing-argument`,
  `:invalid-argument`, `:endpoint`, `:denied`, `:not-found`, `:timeout`,
  `:unavailable`, `:conflict` and the v9 kinds) with its `fields`, as an
  `ex-info` whose message is `(display …)` of it. The kinds and their fields
  are the `error` objects of the vector cases."
  [kind fields]
  (scaffold/not-implemented `typed-error failure-proof {:kind kind :fields fields}))

(defn display
  "The text the Rust kernel's `Display` prints for a typed error, word for
  word: each vector case's `display`."
  [error]
  (scaffold/not-implemented `display failure-proof {:error error}))

(defn failure-class
  "How a typed error fails: a map of `:kind`, `:class` (a class word such as
  `transient`), `:transient` (a boolean), `:retry-after-ms`, `:wire-tag` and
  `:http` (`{:status s :retry-after r}`), each equal to the vector case's
  field of the same name."
  [error]
  (scaffold/not-implemented `failure-class failure-proof {:error error}))
