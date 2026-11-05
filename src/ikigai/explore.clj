(ns ikigai.explore
  "ikigai from a Clojure REPL: connect to a kernel in one call, then read,
  write and look around with plain functions that return Clojure data, and
  serve a Clojure function as a resource the kernel can use.

    (require '[ikigai.explore :as ik])
    (ik/connect! \"/tmp/ik/kernel.sock\")         ; a kernel you started
    (ik/catalog \"fn\")                           ; what is here?
    (ik/args \"urn:iki:fn:toUpper\")              ; what does it take?
    (ik/source \"urn:iki:fn:toUpper\" {:in \"hi\"}) ;=> \"HI\"

  Everything works on one session connection, set by `connect!`; the
  functions in `ikigai.client` take a connection explicitly when you want more
  than one. A kernel's typed error is thrown as an `ex-info` whose data
  carries `:ikigai.error/kind` (`:unresolved`, `:denied`, `:not-found`, …)
  and whose message is the kernel's own sentence.

  Serving: `serve!` puts a Clojure function on this session's PEER socket
  (`peer-socket`), and a kernel started with `--mount urn:clj:=<peer socket>`
  resolves every `urn:clj:…` name through it, so the function is a resource
  there, beside the built-ins, composable with them. The peer must be up
  before that kernel starts (a kernel dials its mounts when it starts; it
  redials one that goes away and comes back), so call `start-peer!` first;
  after that, `serve!` and `unserve!` change what it answers while the kernel
  runs. `examples/explore.clj` walks through all of it.

  Results: text comes back as a string; ask for `:json` and it is parsed; ask
  `meta` for `:graph` and the kernel itself turns the description into
  JSON-LD, which is parsed. This face writes no Turtle parser of its own (the
  ecosystem's rule: never reimplement a standard), so Turtle that only comes
  as Turtle stays text."
  (:refer-clojure :exclude [meta])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ikigai.client :as client]
            [ikigai.serve :as serve]))

(defonce ^:private session (atom {:conn nil :peer nil :resources {}}))

;; ---------------------------------------------------------------------------
;; The connection
;; ---------------------------------------------------------------------------

(defn connect!
  "Connect this session to the kernel at the Unix socket `path` (the local
  default, `ikigai.client/default-socket-path`, when omitted), closing any
  connection it held. Returns what it reached.

    (connect! \"/tmp/ik/kernel.sock\")
    ;=> {:socket \"/tmp/ik/kernel.sock\", :wire-version 9, :resources 74}"
  ([] (connect! nil))
  ([path]
   (when-let [old (:conn @session)] (client/close old))
   (let [conn (client/connect path)]
     (swap! session assoc :conn conn)
     {:socket (:path conn)
      :wire-version (:server-version conn)
      :resources (count (client/entries conn))})))

(defn disconnect!
  "Close this session's connection, if it has one.

    (disconnect!)  ;=> nil"
  []
  (when-let [conn (:conn @session)]
    (client/close conn)
    (swap! session assoc :conn nil))
  nil)

(defn kernel
  "This session's connection, for `ikigai.client`'s functions; refuses, saying
  what to call, when there is none.

    (ikigai.client/source (kernel) \"urn:iki:fn:toUpper\" {\"in\" \"hi\"})"
  []
  (or (:conn @session)
      (throw (ex-info "not connected to a kernel: call (connect! \"/path/to/kernel.sock\") first"
                      {:ikigai.explore/error :not-connected}))))

;; ---------------------------------------------------------------------------
;; Reading and writing
;; ---------------------------------------------------------------------------

(defn- answer
  "A representation as `as` asks: `:text` (the default: a string, or the
  bytes when they are not UTF-8), `:json` (parsed, keys as keywords),
  `:bytes`, or `:rep` (the whole representation map, with `:cache-status`)."
  [rep as]
  (case (or as :text)
    :text (or (:text rep) (:bytes rep))
    :json (json/read-str (:text rep) :key-fn keyword)
    :bytes (:bytes rep)
    :rep rep
    (throw (ex-info (str "`:as` is one of :text, :json, :bytes or :rep, not " (pr-str as)) {:as as}))))

(defn source
  "Read the resource `iri`, with the arguments `args` (a map; keys may be
  keywords or strings, values strings, numbers, booleans or bytes), and return
  its text. `opts` may ask for `{:as :json}`, `{:as :bytes}` or `{:as :rep}`
  (the whole representation, `:cache-status` included).

    (source \"urn:iki:fn:toUpper\" {:in \"hi\"})              ;=> \"HI\"
    (:cache-status (source \"urn:iki:fn:toUpper\" {:in \"hi\"} {:as :rep}))  ;=> :hit"
  ([iri] (source iri {} {}))
  ([iri args] (source iri args {}))
  ([iri args {:keys [as]}]
   (answer (client/source (kernel) iri args) as)))

(defn sink
  "Write `content` to the resource `iri` (with `args`, when given) and return
  the kernel's answer as text.

    (sink \"urn:file:hello.txt\" \"hello from Clojure\")  ;=> \"wrote 18 bytes to hello.txt\""
  ([iri content] (sink iri content {}))
  ([iri content args]
   (answer (client/sink (kernel) iri content args) :text)))

(defn delete!
  "Delete the resource `iri` and return the kernel's answer as text.

    (delete! \"urn:file:hello.txt\")  ;=> \"deleted hello.txt\""
  ([iri] (delete! iri {}))
  ([iri args] (answer (client/delete! (kernel) iri args) :text)))

(defn exists?
  "True when the resource `iri` is there.

    (exists? \"urn:file:hello.txt\")  ;=> true"
  ([iri] (exists? iri {}))
  ([iri args] (client/exists? (kernel) iri args)))

(defn meta
  "The resource `iri`'s self-description. `as` is `:turtle` (the default,
  text), `:text` (the human face), `:json` (the description as data, parsed),
  `:graph` (the description as expanded JSON-LD, which the kernel transrepts to,
  parsed: a vector of nodes whose `:id` is the node's IRI, each predicate an
  IRI string key)
  or any media type string (answered as text).

    (:id (meta \"urn:iki:fn:toUpper\" :json))  ;=> \"toUpper\""
  ([iri] (meta iri :turtle))
  ([iri as]
   (let [media (case as
                 :turtle "text/turtle"
                 :text "text/plain"
                 :json "application/json"
                 :graph "application/ld+json"
                 as)
         rep (client/meta (kernel) iri media)]
     (case as
       :json (json/read-str (:text rep) :key-fn keyword)
       ;; JSON-LD's own keys (`@id`, `@value`) become keywords; a predicate stays its IRI.
       :graph (json/read-str (:text rep) :key-fn #(if (str/starts-with? % "@") (keyword (subs % 1)) %))
       (:text rep)))))

(defn cached?
  "True when reading `iri` with `args` would be answered from the kernel's
  cache right now.

    (cached? \"urn:iki:fn:toUpper\" {:in \"hi\"})  ;=> true"
  ([iri] (cached? iri {}))
  ([iri args] (client/cached? (kernel) iri args)))

(defn trace
  "Read `iri` with `args` and return how the kernel resolved it: one map per
  step, `:target`, `:span`, `:parent` and `:cache-hit`.

    (trace \"urn:iki:fn:toUpper\" {:in \"hi\"})
    ;=> [{:target \"urn:iki:fn:toUpper\", :span 0, :parent nil, :cache-hit true}]"
  ([iri] (trace iri {}))
  ([iri args]
   (mapv #(select-keys % [:target :span :parent :cache-hit])
         (second (client/source-traced (kernel) iri args)))))

;; ---------------------------------------------------------------------------
;; What is here
;; ---------------------------------------------------------------------------

(defn catalog
  "What the kernel serves: one map per binding, `:pattern` (an IRI, or a
  template for a family of them), `:id` and `:origin` (the mount it comes
  through, or nil), sorted by pattern. With `text`, only the rows whose pattern
  or id contains it.

    (map :pattern (catalog \"toUpper\"))  ;=> (\"urn:iki:fn:toUpper\")"
  ([] (catalog nil))
  ([text]
   (->> (client/entries (kernel))
        (map (fn [{:keys [pattern endpoint origin]}] {:pattern pattern :id endpoint :origin origin}))
        (filter #(or (nil? text) (str/includes? (:pattern %) text) (str/includes? (str (:id %)) text)))
        (sort-by :pattern)
        vec)))

(defn describe
  "The resource `iri`'s description as data (its Meta as JSON, keys as
  keywords): `:id`, `:title`, `:summary`, `:verbs`, `:inputs`, `:outputs`,
  and `:actions` for a resource whose verbs differ. Nil when it has none.

    (:verbs (describe \"urn:iki:fn:toUpper\"))  ;=> [\"Source\" \"Meta\"]"
  [iri]
  (some-> (client/describe (kernel) iri) (json/write-str) (json/read-str :key-fn keyword)))

(defn args
  "The arguments the resource `iri` takes, per verb: `{verb [{:name :required
  :summary …}]}`, read from its description.

    (args \"urn:iki:fn:toUpper\")
    ;=> {:source [{:name \"in\", :required true, :summary \"the text to upper-case\", …}]}"
  [iri]
  (let [d (describe iri)
        flat (fn [verb] [verb (:inputs d)])]
    (into (sorted-map)
          (if (seq (:actions d))
            (for [a (:actions d)] [(keyword (str/lower-case (:verb a))) (:inputs a)])
            (for [v (:verbs d) :when (not= "Meta" v)] (flat (keyword (str/lower-case v))))))))

;; ---------------------------------------------------------------------------
;; Serving Clojure functions as resources
;; ---------------------------------------------------------------------------

(defn peer-socket
  "The Unix socket this session's peer serves on: `/tmp/ikigai-clj-<user>/peer.sock`
  (under /tmp, so it fits macOS's 104-byte socket path), or the path given to
  `start-peer!`.

    (peer-socket)  ;=> \"/tmp/ikigai-clj-brian/peer.sock\""
  []
  (or (get-in @session [:peer :path])
      (str (io/file "/tmp" (str "ikigai-clj-" (System/getProperty "user.name")) "peer.sock"))))

(defn- arg-spec [arg]
  (let [{:keys [name required summary default one-of]}
        (if (map? arg) arg {:name arg})]
    (cond-> {"name" (clojure.core/name name)
             "summary" (or summary "")
             "required" (if (some? required) (boolean required) (nil? default))
             "source" "argument"}
      (some? default) (assoc "default" (str default))
      (seq one-of) (assoc "one_of" (vec one-of)))))

(defn- default-id [pattern]
  (or (last (remove #(str/includes? % "{") (str/split pattern #":"))) pattern))

(defn- keywordized
  "What a resource function receives: its arguments and the template's
  bindings as keywords, `:content` for a write, and the whole request under
  `:ikigai/request`."
  [request]
  (merge (update-keys (:args request) keyword)
         (update-keys (:bindings request) keyword)
         {:ikigai/request request}))

(defn resource
  "An endpoint for `iri` answered by Clojure functions: the short form of
  `ikigai.serve/endpoint`. `iri` may be a template (`urn:clj:note:{id}`), and
  each `{variable}` reaches the function by name. `f` is a function (it
  answers Source) or a map of verb to function (`{:source f :sink g}`). Each
  function receives ONE map: its arguments and the template's bindings as
  keywords, and `:content` for a write; it returns a string (or bytes, or nil
  from a write) or throws a typed error (`ikigai.wire/typed-error`).

  `opts`: `:args` (names, or maps of `:name`, `:required`, `:summary`,
  `:default`, `:one-of`; an argument with a default is optional), `:summary`,
  `:title`, `:id` (by default the last segment with no variable), `:output`
  (a media type, `text/plain` by default) and `:cacheable` (true when Source's
  answer depends on nothing but the name and arguments).

    (resource \"urn:clj:greet\" {:args [\"who\"] :summary \"Greet someone\"}
              (fn [{:keys [who]}] (str \"Hello, \" who \"!\")))"
  ([iri f] (resource iri {} f))
  ([iri {:keys [summary title id output cacheable] :as opts} f]
   (let [handlers (if (map? f) f {:source f})
         verbs (filter handlers [:source :sink :exists :delete])
         variables (map second (re-seq #"\{([A-Za-z0-9_]+)\}" iri))
         bindings (for [v variables]
                    {"name" v "summary" "" "required" true "source" "binding"})
         inputs (mapv arg-spec (:args opts))
         action (fn [verb]
                  {"verb" (str/capitalize (name verb))
                   "summary" ""
                   "inputs" (vec (concat bindings
                                         inputs
                                         (when (and (= verb :sink)
                                                    (not-any? #(= "content" (get % "name")) inputs))
                                           [{"name" "content" "summary" "the value written"
                                             "required" true "source" "argument"}])))
                   "outputs" [(or output "text/plain")]})
         flat? (and (= verbs [:source]) (empty? variables))
         description (cond-> {"id" (or id (default-id iri))
                              "title" (or title "")
                              "summary" (or summary "")
                              "verbs" (conj (mapv #(str/capitalize (name %)) verbs) "Meta")
                              "inputs" (if flat? inputs [])
                              "outputs" (if flat? [(or output "text/plain")] [])}
                       (not flat?) (assoc "actions" (mapv action verbs)))]
     (serve/endpoint iri description
                     (into {} (for [[verb g] handlers] [verb (fn [request] (g (keywordized request)))]))
                     {:cacheable cacheable}))))

(defn start-peer!
  "Start this session's peer: a server on `path` (`peer-socket` by default)
  that answers every resource `serve!` adds. Start it BEFORE the kernel that
  mounts it. Returns the socket and the flag that kernel takes.

    (start-peer!)
    ;=> {:socket \"/tmp/ikigai-clj-brian/peer.sock\",
    ;    :mount \"--mount urn:clj:=/tmp/ikigai-clj-brian/peer.sock\"}"
  ([] (start-peer! nil))
  ([path]
   (if-let [peer (:peer @session)]
     {:socket (:path peer) :mount (str "--mount urn:clj:=" (:path peer))}
     (let [path (or path (peer-socket))
           peer (serve/serve (vals (:resources @session)) path)]
       (swap! session assoc :peer peer)
       {:socket path :mount (str "--mount urn:clj:=" path)}))))

(defn stop-peer!
  "Stop this session's peer and remove its socket. A kernel that mounts it
  answers its names as unavailable until it starts again.

    (stop-peer!)  ;=> nil"
  []
  (when-let [peer (:peer @session)]
    (serve/stop peer)
    (swap! session assoc :peer nil))
  nil)

(defn- republish! []
  (when-let [peer (:peer @session)]
    (serve/set-endpoints! peer (vals (:resources @session)))))

(defn serve!
  "Serve a Clojure function as the resource `iri` on this session's peer
  (starting the peer when it is not running), replacing anything served there
  before. `opts` and `f` are `resource`'s. A kernel that mounts the peer
  (`--mount urn:clj:=<peer socket>`) resolves `iri` at once.

    (serve! \"urn:clj:greet\" {:args [\"who\"]} (fn [{:keys [who]}] (str \"Hello, \" who \"!\")))
    ;=> {:iri \"urn:clj:greet\", :socket \"/tmp/ikigai-clj-brian/peer.sock\"}
    (source \"urn:clj:greet\" {:who \"Ada\"})  ;=> \"Hello, Ada!\""
  ([iri f] (serve! iri {} f))
  ([iri opts f]
   (let [r (resource iri opts f)]
     (swap! session update :resources assoc iri r)
     (start-peer!)
     (republish!)
     {:iri iri :socket (peer-socket)})))

(defn unserve!
  "Stop serving the resource `iri`; a kernel that mounts the peer then answers
  it as a name bound nowhere.

    (unserve! \"urn:clj:greet\")  ;=> [] (what is still served)"
  [iri]
  (swap! session update :resources dissoc iri)
  (republish!)
  (vec (keys (:resources @session))))

(defn served
  "The IRIs this session's peer serves.

    (served)  ;=> [\"urn:clj:greet\"]"
  []
  (vec (keys (:resources @session))))
