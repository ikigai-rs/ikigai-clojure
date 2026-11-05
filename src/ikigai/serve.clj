(ns ikigai.serve
  "Serve Clojure functions as ikigai resources over a Unix socket, so that a
  Rust host can mount them.

  An endpoint is a function plus its description: an id, the verbs it
  answers, its inputs (name, required or optional, XSD class, `one_of`,
  default) and its outputs. The description is not decoration: the host routes
  named arguments by it, the catalog lists it, the floor enforces the
  capabilities it declares (`ikigai.capability`), and its Meta face answers in
  JSON and Turtle.

  Proved by `test/vectors/contract_vectors.json` (`doors`: a description's
  actions with their match and contract IRIs, and its Meta Turtle, byte for
  byte), by `test/vectors/capability_vectors.json` through the floor, and by a
  real host mounting the server (ROADMAP.md, step 5). Read alongside
  ikigai-python `src/ikigai/serve.py` and ikigai-deno `src/serve.ts`."
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private doors "test/vectors/contract_vectors.json")
(def ^:private hosted "a host mounting the server (ROADMAP.md, step 5)")

(defn endpoint
  "An endpoint answering at the IRI pattern `pattern`, described by
  `description` (a map as the vectors' `description`), whose verbs are
  answered by `handlers` (a map of verb keyword to function)."
  [pattern description handlers]
  (scaffold/not-implemented `endpoint hosted
                            {:pattern pattern :description description :handlers handlers}))

(defn action-specs
  "The actions of `description` at `pattern`, one per verb, each with its
  `match` and `contract` IRIs: each `doors` row's `actions`."
  [pattern description]
  (scaffold/not-implemented `action-specs doors {:pattern pattern :description description}))

(defn description->turtle
  "The Meta Turtle of `description` at `pattern`, byte for byte as core
  writes it: each `doors` row's `turtle`."
  [pattern description]
  (scaffold/not-implemented `description->turtle doors
                            {:pattern pattern :description description}))

(defn serve
  "Serve `endpoints` on the Unix socket at `path`: answer hellos, enforce each
  endpoint's floor, and answer calls until `stop` is called on the returned
  server."
  [endpoints path]
  (scaffold/not-implemented `serve hosted {:endpoints endpoints :path path}))

(defn stop
  "Stop the `server` `serve` returned and remove its socket."
  [server]
  (scaffold/not-implemented `stop hosted {:server server}))
