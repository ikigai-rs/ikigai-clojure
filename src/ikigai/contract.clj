(ns ikigai.contract
  "The two identities an action has, mirroring `ikigai_core::contract`.

  A MATCH IRI names where an action answers: `urn:ikigai:match:{verb}:{pattern}`.
  A CONTRACT IRI names what the action promises, by its content:
  `urn:ikigai:contract:{endpoint-id}:{verb}:b3:{hex}`, where `{hex}` is the
  BLAKE3 digest of the contract's canonical form (`ikigai:contract:v1`, or
  `ikigai:contract:v2` when the contract carries what only v2 can say).
  Identical contracts share one IRI and different ones never do, so the digest
  must be byte for byte the one the Rust kernel computes.

  Verbs are keywords here (`:source`, `:sink`, `:exists`, `:delete`, `:meta`);
  the vectors spell them `Source`, `Sink`, and so on. An action is the map the
  vectors carry under `action`, with string keys as the JSON has them, until
  this face chooses its own shape and converts at the edge.

  Proved by `test/vectors/contract_vectors.json` (`contracts`, `matches` and
  `parses`). Read alongside ikigai-python `src/ikigai/contract.py` and
  ikigai-deno `src/contract.ts`. BLAKE3 is not in the JDK; see ROADMAP.md,
  step 2, for the choice to make."
  (:require [ikigai.scaffold :as scaffold]))

(def ^:private proof "test/vectors/contract_vectors.json")

(defn contract-id
  "The digest part of an action's contract IRI for the endpoint
  `endpoint-id`: `b3:` and 64 lowercase hex digits of BLAKE3 over the canonical
  form the action's content selects. Declaration order and duplicates in the
  action never move it."
  [endpoint-id action]
  (scaffold/not-implemented `contract-id proof {:endpoint-id endpoint-id :action action}))

(defn contract-iri
  "The contract IRI of `action` on the endpoint `endpoint-id`: each
  `contracts` row's `contract`, from its `id` and `action`."
  [endpoint-id action]
  (scaffold/not-implemented `contract-iri proof {:endpoint-id endpoint-id :action action}))

(defn match-iri
  "The match IRI of `verb` at the IRI pattern `pattern`: each `matches` row
  gives one per verb, under `iris`."
  [verb pattern]
  (scaffold/not-implemented `match-iri proof {:verb verb :pattern pattern}))

(defn parse-match-iri
  "Parse a match IRI into `[verb pattern]`, or nil when `iri` is not one:
  each `parses` row's `match`."
  [iri]
  (scaffold/not-implemented `parse-match-iri proof {:iri iri}))

(defn parse-contract-iri
  "Parse a contract IRI into `[endpoint-id verb digest]`, or nil when `iri`
  is not one: each `parses` row's `contract`."
  [iri]
  (scaffold/not-implemented `parse-contract-iri proof {:iri iri}))

(defn canonical-iri
  "The canonical spelling of a match or contract IRI, or nil when `iri` is
  neither: each `parses` row's `canonical`."
  [iri]
  (scaffold/not-implemented `canonical-iri proof {:iri iri}))
