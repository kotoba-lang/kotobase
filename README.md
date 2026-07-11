# kotobase

[![CI](https://github.com/kotoba-lang/kotobase/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kotobase/actions/workflows/ci.yml)

**The datom database of the kotoba stack — the _Datomic_ to kotoba's _Clojure_**
(ADR-2607032500). kotobase persists, indexes, Datalog-queries, and
time-versions the datom model that the [**`kotoba`**](https://github.com/kotoba-lang/kotoba)
language defines (`kotoba.kgraph`'s `[e a v]`), and is built _on_ kotoba — it
depends on the language, never the reverse. Like Datomic on Clojure: the db
value is kotoba data, the query is kotoba data.

**Datomic reimagined for the distributed web.** Where Datomic centralizes on
a single transactor peer over SQL/DynamoDB storage, kotobase is
content-addressed and network-native from the ground up: facts are
blake3/CIDv1 blocks (`ipld`/`multiformats`/`dag-cbor`), the index structure is
a content-addressed Prolly Tree instead of a B-tree, history is an immutable
commit DAG rather than a single log, and the "peer" is an edge runtime
(`kotobase-cljc-worker` / kotobase.net) reachable over CACAO-authenticated
HTTP rather than a JVM process with a direct storage connection. Same
datom/EAVT/Datalog model as Datomic, but the storage and replication substrate
is the distributed web (IPFS-shaped content addressing) instead of a
single-writer database.

"kotobase" is the umbrella over the datom-plane repos (bottom-up): content
addressing (`ipld`/`multiformats`/`dag-cbor`) → content-addressed storage
(`prolly-tree`) → immutable commit chain / time (`commit-dag`) → 4 covering
indexes (`quad-store`) → Datalog query (`kqe`) → transact/datoms/q/pull
(`kotobase-engine`) → CACAO client (`kotobase-client`) → edge runtime
(`kotobase-cljc-worker` = the kotobase.net PDS). **This repo (`kotobase-clj`) is
kotobase's client seam** — the `IStore` port below.

**Disambiguation (ADR-2607050900):** this repo, [`kotobase-client`](https://github.com/kotoba-lang/kotobase-client),
and [`kotoba-client`](https://github.com/kotoba-lang/kotoba-client) are three
distinct repos with no functional overlap, despite the similar names:
`kotobase-client` is the CACAO-authed ClojureScript client for the
`kotobase.net` tenant Datom plane specifically (linked above in the pipeline
diagram); `kotoba-client` is a separate, *generic, non-CACAO* CID-verified
block ingest/hydrate client over kotoba's content graph, consumed by `p2p`.
Neither previously cross-referenced the other two by name alone.

---

## Data model — an incidence merkle graph

kotobase does not treat IPLD as a generic tree/DAG encoding — every IPLD
block is an **incidence structure**, in the sense of `com-junkawasaki/inc`'s
Theory of Incidence: a relation `i` whose **boundary** `∂(i)` is a finite
list of labelled, oriented **endpoints** (`Endpoint = {i, role, sign, mult}`,
`Boundary = List Endpoint`). Content-addressing hashes that boundary into the
node's own identity, so the incidence structure *is* the Merkle graph, not a
separate encoding layered on top of one:

- **endpoint = IPLD link.** Every `ipld/link` (the `ipld` repo's tag-42
  CID link) occupies a labelled position in its parent block — a map key
  (`"children"`, `"prev"`, `"index-roots/spo"`) or array index — and points
  one direction, parent → child. That is exactly an `Endpoint`: `role` is
  the label, `sign` is the orientation (always outbound in an IPLD DAG —
  links never point back up), `mult` is how many times that role repeats
  (a prolly-tree internal node holds many `children` endpoints under one
  role).
- **boundary = the block's link set.** `ipld/links` decodes a block and
  returns exactly `∂(i)` — the generic, schema-free walk every hydrate/GC
  loop in `kotoba-client` relies on.
- **the CID *is* the relation's identity.** A block encodes as canonical
  DAG-CBOR, so `CID(i) = hash(content(i))`, and `content(i)` includes `∂(i)`
  verbatim: two nodes are the same relation iff their boundaries (labels,
  orientation, multiplicities, and the CIDs they point at) are identical.
  Mutating one endpoint changes the owning node's CID, which changes every
  ancestor's CID up to the head — append-only, tamper-evident, structurally
  shareable.

Every layer in the umbrella pipeline above is this same incidence-merkle
graph at a different granularity:

| layer | the incidence relation | its labelled endpoints |
|---|---|---|
| `prolly-tree` node | one tree node | `children[i]` — ordered, repeatable |
| `commit-dag` commit | one commit | `prev`, `index-roots/{spo,pso,pos,ocp}` |
| `quad-store` commit | the 4-index snapshot | one endpoint per covering index |
| a datom `[e a v]` | the relation itself | `e`, `a`, `v` — 3 labelled endpoints |

Datoms are the base case, not an exception: `[e a v]` (`kotoba.kgraph`'s EAVT
model) is already a minimal incidence relation with three named endpoints, so
kotobase's Datalog-visible datom shape and its IPLD storage shape share one
vocabulary top to bottom. A *tree* — binary, unlabelled parent/child — is
just the special case of this graph with exactly one anonymous endpoint role.

## `IStore` — the storage seam

The **external-storage port** for com-junkawasaki apps — one `IStore` seam that lets an
app run **standalone (OSS)** on a local backend or, when connected to the cloud, persist
to **kotobase.net** (the kotoba PDS) *without the app code changing*. Zero-dependency,
all `.cljc` (JVM / cljs / Cloudflare Worker / kotoba-WASM), with the network
**host-injected** — the store carries no HTTP client.

Same injection pattern as the actors' `MemStore ‖ DatomicStore` and num-clj's `IBackend`:

```
murakumo / manimani (and any app)
        │ reads/writes through
        ▼
kotobase.store/IStore           put · get · list · append · read(since)
        ├── kotobase.local/LocalStore     pure atom (OSS standalone + the ORACLE)
        └── kotobase.kotobase/KotobaseStore   forwards every op to an injected
                 `(xrpc method params)` → kotobase.net XRPC → the kotoba PDS,
                 which itself backs onto external object storage (git-annex/B2, S3)
```

Two shapes of state cover both apps:
- **docs** — keyed last-writer-wins (`put`/`get`/`list`): a node's latest Heartbeat, a
  triage rule, a config fact.
- **streams** — append-only logs with a monotonic `:seq` cursor (`append`/`read since`):
  manimani's Decision Ledger, murakumo's per-node event feed, the kotoba Datom log. The
  Kafka-offset model — robust across devices and merges.

## Usage

```clojure
(require '[kotobase.local :as local] '[kotobase.kotobase :as kb] '[kotobase.store :as st])

;; OSS standalone — pure, in-process
(def s (local/local-store))
(st/-put s "nodes" "asher" {:role :worker})
(st/-append s "ledger" {:decision :reply :id 1})
(st/-read s "ledger" 0)               ;=> [{:decision :reply :id 1 :seq 1}]

;; Cloud — same calls, persisted to kotobase.net (host injects `xrpc`, e.g. fetch)
(def s (kb/kotobase-store (fn [method params] (call-kotobase! method params))))
```

The contract suite asserts `KotobaseStore ≡ LocalStore` over a faithful transport, so a
live kotobase.net backend is correct iff it passes the same checks
(`MemStore ≡ DatomicStore` discipline).

## Consumers

The cloud API workers [local-murakumo](https://github.com/gftdcojp/local-murakumo)
(formerly `cloud-murakumo`, renamed 2026-07-04) and
[cloud-manimani](https://github.com/gftdcojp/cloud-manimani) (cljs Cloudflare
Workers) inject a `fetch`-based `xrpc` and serve the app API straight off the
`:kotobase` store; the desktop/CLI apps use `:local`.

> **Naming note (2026-07-08):** as of this writing, both cloud-murakumo's and
> cloud-manimani's `deps.edn` actually depend on
> `io.github.com-junkawasaki/kotobase-clj` (a separate repo,
> `orgs/com-junkawasaki/kotobase-clj`, with an identical
> `kotobase.store`/`local`/`kotobase` file layout and contract test) rather
> than on this repo by name. Whether that is a pre-rename copy, a fork, or
> the currently-authoritative artifact is unresolved -- see
> `docs/coverage.edn`'s M5 note. Until a real dependent names
> `kotoba-lang/kotobase` specifically, treat the description above as the
> intended architecture, not a confirmed dependency graph.

```bash
clojure -M:test     # LocalStore + KotobaseStore both satisfy the IStore contract
```
