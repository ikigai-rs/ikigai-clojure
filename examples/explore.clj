(ns explore
  "A guided session: ikigai from a Clojure REPL, with `ikigai.explore`.

  You need two terminals and the `ikigai` command (`cargo install ikigai-cli
  --locked`; this repository's CI uses 0.2.8). Evaluate the forms in the
  `comment` below one at a time, in order, in a REPL started at this
  repository's root:

    bb repl                  ; babashka
    clojure -M:test          ; or the JVM (it also has BLAKE3, for contract IRIs)

  Each step says what to expect. The same session runs as assertions in
  `test/ikigai/explore_test.clj`.")

(comment
  ;; 1. Load the exploring layer.
  (require '[ikigai.explore :as ik])

  ;; 2. Start this session's PEER first: the socket your Clojure resources are served on.
  ;;    A kernel dials its mounts when it starts, so the peer must be up before it.
  (ik/start-peer!)
  ;; => {:socket "/tmp/ikigai-clj-<you>/peer.sock",
  ;;     :mount "--mount urn:clj:=/tmp/ikigai-clj-<you>/peer.sock"}

  ;; 3. In the OTHER terminal, start a kernel that mounts it, with a file root to write in:
  ;;
  ;;      mkdir -p /tmp/ik/files
  ;;      ikigai --files /tmp/ik/files serve /tmp/ik/kernel.sock \
  ;;             --mount urn:clj:=/tmp/ikigai-clj-$USER/peer.sock
  ;;
  ;;    It prints `serving on /tmp/ik/kernel.sock`. Every `urn:clj:…` name now resolves
  ;;    through your REPL.

  ;; 4. Connect to it.
  (ik/connect! "/tmp/ik/kernel.sock")
  ;; => {:socket "/tmp/ik/kernel.sock", :wire-version 9, :resources …}

  ;; 5. What is here? The catalog, filtered, and what one resource takes.
  (ik/catalog "fn")
  (ik/args "urn:iki:fn:toUpper")
  ;; => {:source [{:name "in", :required true, :summary "the text to upper-case", …}]}
  (ik/describe "urn:iki:fn:toUpper")

  ;; 6. Read something.
  (ik/source "urn:iki:fn:toUpper" {:in "hello"})
  ;; => "HELLO"
  (ik/source "urn:iki:fn:nope")
  ;; throws: no endpoint resolved for urn:iki:fn:nope   (ex-data: {:ikigai.error/kind :unresolved …})

  ;; 7. Write something, and read it back. The file lands in /tmp/ik/files.
  (ik/sink "urn:file:hello.txt" "hello from Clojure")
  ;; => "wrote 18 bytes to hello.txt"
  (ik/source "urn:file:hello.txt")
  ;; => "hello from Clojure"

  ;; 8. Serve a Clojure function as a resource: a name, its arguments, a function.
  (ik/serve! "urn:clj:greet" {:args ["who"] :summary "Greet someone"}
             (fn [{:keys [who]}] (str "Hello, " who "!")))
  (ik/source "urn:clj:greet" {:who "Ada"})
  ;; => "Hello, Ada!"   (your REPL asked the kernel, and the kernel asked your REPL)
  ;;
  ;;    And from the ikigai REPL, in the other terminal:
  ;;
  ;;      ikigai --connect /tmp/ik/kernel.sock
  ;;      > source urn:clj:greet who=Ada
  ;;      Hello, Ada!
  ;;      > source urn:clj:greet Bo          (a positional value: the kernel read your arguments)
  ;;      Hello, Bo!
  ;;      > describe urn:clj:greet

  ;; 9. Compose it with a built-in: `toUpper` reads your resource BY REFERENCE.
  (ik/serve! "urn:clj:motto" (fn [_] "keep it simple"))
  (ik/source "urn:iki:fn:toUpper" {:in {:reference "urn:clj:motto"}})
  ;; => "KEEP IT SIMPLE"
  ;;    In the ikigai REPL: source urn:iki:fn:toUpper in=<urn:clj:motto>

  ;; 10. Watch a cached answer recompute after a write. The kernel caches toUpper over the
  ;;     file, and a write to the file cuts that answer's golden thread.
  (ik/sink "urn:file:note.txt" "first")
  (ik/source "urn:iki:fn:toUpper" {:in {:reference "urn:file:note.txt"}} {:as :rep})
  ;; => {:text "FIRST", :cache-status :miss, …}
  (ik/source "urn:iki:fn:toUpper" {:in {:reference "urn:file:note.txt"}} {:as :rep})
  ;; => {:text "FIRST", :cache-status :hit, …}
  ;;    (If it says :miss, read once more: the kernel's file watcher can cut the answer again
  ;;    a moment after the write that the Sink already cut.)
  (ik/sink "urn:file:note.txt" "second")
  (ik/source "urn:iki:fn:toUpper" {:in {:reference "urn:file:note.txt"}} {:as :rep})
  ;; => {:text "SECOND", :cache-status :miss, …}   (recomputed: the write cut it)
  (ik/trace "urn:iki:fn:toUpper" {:in {:reference "urn:file:note.txt"}})

  ;; 11. A typed error from Clojure crosses as itself.
  (require '[ikigai.wire :as wire])
  (ik/serve! "urn:clj:note:{id}"
             {:source (fn [{:keys [id]}]
                        (throw (wire/typed-error :not-found {:message (str "no note " id)})))})
  (ik/source "urn:clj:note:7")
  ;; throws: not found: no note 7   (ex-data: {:ikigai.error/kind :not-found …})

  ;; 12. Stop serving, and stop.
  (ik/unserve! "urn:clj:greet")
  (ik/source "urn:clj:greet" {:who "Ada"})
  ;; throws: no endpoint resolved for urn:greet
  ;;    (the name as your peer received it: the mount strips its `urn:clj:` prefix)
  (ik/stop-peer!)
  (ik/disconnect!)
  ;;    Then Ctrl-C the kernel in the other terminal.
  )
