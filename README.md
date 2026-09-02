# kotobase

[![CI](https://github.com/kotoba-lang/kotobase/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kotobase/actions/workflows/ci.yml)

The primary API is now `kotobase.core`: `open`, `transact!`, `datoms`, `q`,
`query`, and `pull`. The official `kotobase.net` / `kotoba-lang/kotobase`
correctness path is a signed immutable CID commit DAG executed by Kotoba native
and Wasm targets. It requires no mutable ref service and MUST NOT depend on
Durable Objects, D1, PostgreSQL, or Rust. S3/R2 and IPFS are untrusted immutable
block transports. The older injected `kotobase-storage` mutable-ref providers
remain compatibility and migration surfaces only. See
[`docs/storage-architecture.md`](docs/storage-architecture.md).

The document/stream `kotobase.store/IStore` section below is a legacy
compatibility surface; new database backends must not target it.

Every governed cross-protocol execution can be closed by the versioned
`ExecutionManifest`, `RequestEnvelope`, and `ExecutionReceipt` records in
`kotobase.execution-contract`. They bind an exact data commit, policy snapshot,
revocation epoch, semantic query digest, request digest, result root, and
measured cost without making one query language, wire codec, mutable head, or
storage provider canonical. Validation is exact and fail closed; see
[`docs/ADR-execution-contract.md`](docs/ADR-execution-contract.md).

`kotobase.governed-execution` is where those records are produced rather than
described. `execute!` (and `execute-async!` on Workers) binds the signed
`RequestEnvelope` to the query that will actually run, decides expiry, the
current revocation epoch and nonce freshness against host-supplied state,
runs the guarded read, and commits a signed `ExecutionReceipt` before any row
is returned. The identifiers in that receipt are computed, not accepted:
`:execution/manifest`, `:request/digest` and `:result/root` are the canonical
addresses of the manifest, the envelope and the rows that were served, so an
auditor can re-derive each one and a record edited in any field stops matching
what cites it. `kotobase.execution-identity` names that codec
(`kotoba.value.codec/value-cid`); only the physical plan digest and the cost
remain the host's answers. The receipt's signature is verified before the
record is written, and the manifest's before the nonce is spent. A policy refusal produces a deny
receipt on the same plane; an evaluator crash does not, because it is not an
authority decision. `kotobase.causal-commit/execution-receipt-sink` is the
canonical-CID implementation of that commit: it writes at an exact immutable
basis and rereads the record before acknowledging.

`kotobase.evidence` is the one plane those receipts are compared on. It lifts
a source plane's record onto a version 1 `ExecutionReceipt` under a rule it
cannot break — the supplement must be exactly the fields the source does not
carry, so laundering a field the source answers and omitting one it does not
are both refused — which makes the distance from each plane to the contract a
number rather than a claim. Effect admissions and artifact builds are evidence
of a different subject — they have no query plan and no served result — so
they lift onto `kotobase.effect-contract`, which binds an action, a resource,
the code lock the bytes were admitted under and the effects granted. Both
contracts share one vocabulary, so two subjects do not mean two languages. See
[`docs/ADR-evidence-plane.md`](docs/ADR-evidence-plane.md).

`kotobase.governed-read` is the one read path that serves rows and leaves
evidence. It binds the trust decision being exercised — an allow, a read, this
tenant, exactly these resources, and the principal the signed envelope names —
then runs the governed execution with the canonical CID sink wired in, so rows
return only after the ExecutionReceipt has been committed and read back. It
replaces the disclosure read path, which committed a receipt that answered one
of the contract's eight fields.

`kotobase.governed-effect` (and `execute-async!` on Workers) is the
effect-side twin of `governed-read`: it
validates an EffectRequest, binds the envelope's code lock to the package
being admitted, decides runtime authority, runs `kotobase.admission/guard!`
unchanged — so the audit is still durable before the effect runs — and then
commits an EffectReceipt bound to the granted set the admission computed and
the outcome the effect named. A read's receipt can bind its result because
reading is repeatable; an effect's is written afterwards because an outcome
does not exist until the effect has run, which is why the audit and the
receipt are two records answering two questions rather than one record written
twice.

`kotobase.conformance` checks the contract's stated purpose instead of
asserting it: given the receipts and rows from two or more frontends handed
the same request, it refuses unless they agree about the request and about the
answer, while permitting the plan digest, cost, build and signature to differ.
It also reports what it found on the way — a result root is the address of the
rows *as served*, so two conformant frontends serving the same multiset in
different orders produce different roots, and comparing frontends by that
field is asking the wrong question.

`kotobase.execution-keys` answers *which* key, not just whether some key
signed. A signature names the key that made it, and the verifier refuses it
unless a registry says that key id, under that algorithm, was authorised to
sign that kind of record for this tenant at this revocation epoch — with the
tenant and epoch supplied by the execution rather than by whatever the
verifier was built with. A registry that returns nothing refuses; that is the
likeliest way for a key check to pass silently.

`kotobase.metering` counts what an execution spent, at the seam between
`kotobase.core` and the provider: how many times blocks were requested, how
many bytes came back, and how many times the caller had to wait for an answer
before it could ask the next question. That last number is what a pack layout
exists to reduce and what a wall clock on a loaded machine cannot tell you.
`:cache-profile` takes two meters rather than one — a cache in front of a
single decorator is invisible to it and a cache behind it is the provider's
business — so a meter above a cache and one below it derive how much the cache
absorbed: `:hot` when nothing reached the provider, `:cold` when everything
did, `:warm` in between. The same cache is cold on the read that fills it and
hot on the next one, which is why the field is worth measuring per execution
rather than configuring per host. With one meter it stays the caller's word
and `:unmeasured` is the honest value.

`kotobase.causal-commit` is the canonical causal-identity adapter. It commits
identity transitions and LLM/model/agent authority decisions against an exact
immutable basis CID without consulting or publishing a mutable ref. The permanent projection contains attributed
records, decision bases, and content addresses—not raw identity evidence or
credentials. `kotobase.causal-trust` remains the explicitly named numeric-
revision compatibility route; the two basis types are never translated.

**The datom database of the kotoba stack.** kotobase persists, indexes,
queries, and time-versions the datom model that the
[**`kotoba`**](https://github.com/kotoba-lang/kotoba) language defines
(`kotoba.kgraph`'s `[e a v]`), and is built _on_ kotoba — it depends on the
language, never the reverse. The db value is kotoba data; the query is kotoba
data.

**What kotobase is based on is the datom plane, not Datomic**
(ADR-2608039970). Two things sit underneath everything here, and neither is
Datomic:

- **content-addressed blocks + optional compatibility refs + large objects** — facts are
  CIDv1 blocks (`ipld`/`multiformats`/`dag-cbor`), the index structure is a
  content-addressed Prolly Tree instead of a B-tree, history is an immutable
  commit DAG rather than a single log. S3/R2 and IPFS are official immutable
  transports; PostgreSQL, D1, and mutable IPNS/ref adapters are compatibility
  surfaces and cannot select canonical truth — see
  [`docs/storage-architecture.md`](docs/storage-architecture.md). IPLD is the
  canonical encoding in every deployment. **Where those blocks physically sit
  is a separate layer**: blocks are packed into CARv2 archives
  (`io-ipld-car`) and read back by byte range, so a block's identity stays its
  own CID while its location is `(pack CID, offset, length)`. One object per
  CID remains a legal backend profile; it is no longer the default one
  (superproject ADR-2608160100).
- **the datom (triple/EAV) itself**, immutable and content-addressed — the
  logical model every query surface shares. It is the right base for a stack
  serving many protocols because a relational row, an RDF quad, a property
  graph edge and a JSON document all reduce to triples, and the reverse does
  not hold losslessly.

The Datomic-shaped API (`kotobase.core`'s `q`/`query`/`pull`, and
`kotobase.datomic`'s EDN transaction and query grammar) is **one surface over
that plane, alongside SQL, openCypher, SPARQL, GraphQL and Gremlin** — a good
one, and the right choice when the caller's language is already
Datalog-shaped. It is not the layer the others are built on, and Datalog is
not the IR they are translated into: see
[`kotobase-query`](https://github.com/kotoba-lang/kotobase-query)'s
`materialize` + access-path contract.

**Why the distinction is worth stating.** "kotoba : kotobase = Clojure :
Datomic" (ADR-2607032500) is a naming and terminology decision — where the
`spo`/`pso`/`pos`/`ocp` vocabulary comes from, why the repos are named as they
are — and it remains accurate. What it is not is a design premise. Read as one,
it produced a stack in which every query protocol was expected to route
through Datalog, which is not what the surfaces that exist actually do.

"kotobase" is the umbrella over the datom-plane repos (bottom-up): content
addressing (`ipld`/`multiformats`/`dag-cbor`) → block packing
(`io-ipld-car`, CARv2 — a block's CID is its identity, the pack is its
location) → content-addressed storage
(`prolly-tree`) → immutable commit chain / time (`commit-dag`) → 4 covering
indexes (`arrangement`, query layer in `datalog`) → transact/datoms/q/pull
(`kotobase-engine`) → CACAO client (`kotobase-client`) → edge runtime
(`kotobase-cljc-worker` = the kotobase.net PDS). **This repo (`kotobase-clj`) is
kotobase's client seam** — the `IStore` port below.

Query languages sit *beside* each other on top of the indexes rather than in
that column: `kotobase-query`'s `materialize` + access paths is what they
share, and Datalog (`datalog.core`, `kotobase.core/q`), SQL
(`org-postgresql-wire`), openCypher, SPARQL, GraphQL and Gremlin are peers
over it (ADR-2608039970). `quad-store` and `kqe` are the former names of
`arrangement` and of the query layer now in `datalog`.

**Disambiguation (ADR-2607050900):** this repo, [`kotobase-client`](https://github.com/kotoba-lang/kotobase-client),
and [`kotoba-client`](https://github.com/kotoba-lang/kotoba-client) are three
distinct repos with no functional overlap, despite the similar names:
`kotobase-client` is the CACAO-authed ClojureScript client for the
`kotobase.net` tenant Datom plane specifically (linked above in the pipeline
diagram); `kotoba-client` is a separate, *generic, non-CACAO* CID-verified
block ingest/hydrate client over kotoba's content graph, consumed by `p2p`.
Neither previously cross-referenced the other two by name alone.

---

## Stack topology

kotobase's position in the stack (depends on kotoba, **never** the reverse —
verified: runtime deps are `security` only, `kotoba` appears solely in the
`:integration` test alias), and the decision to converge the datom plane's
repo names on the `kotobase-*` prefix (retiring the need for the
Disambiguation section above), are recorded in
[`docs/ADR-stack-topology.md`](docs/ADR-stack-topology.md)
(root authority: `com-junkawasaki/root` ADR-2607241100).

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

### What is vocabulary here, and what is wired

Everything above describes the model. Two parts of it are **not** carried by
the query engine today, and this section exists so a reader does not infer
that they are. Recorded in `com-junkawasaki/root` ADR
`adr-2608201500-incidence-is-the-vocabulary-the-query-engine-stops-at-triples`.

| claim | wired? | where it stops |
| --- | --- | --- |
| every block's boundary is walkable | **yes** | `ipld/links` returns `∂(i)` |
| a link occupies a labelled position | **yes, in the block** | the map key is the `role` |
| the CID is the relation's identity | **yes** | canonical DAG-CBOR over `∂(i)` |
| a query can *read* an endpoint's `role` | **no** | `datalog` clauses are `[s p o]` — arity 3, positional, no labels |
| a query can read `sign` or `mult` | **no** | neither has a position in a triple |

So the incidence framing is exact for **storage** and role-erased for
**query**. `[e a v]` is the base case of the model, and it is also, today, the
*only* case the query layer implements. Generalizing `datalog` from a
positional triple to a labelled boundary is open work, not a description of
what runs — see that library's own "The relation model above this one".

One consequence worth stating plainly, because it points the other way from
the usual disclaimer: **`role`, `sign` and `mult` cannot be added to a
positional clause after the fact.** Erasing labels is not reversible. That
makes the order in which this gets generalized load-bearing rather than a
matter of taste.

Two further separations the vocabulary makes and the engine does not:

- **`Hash` vs `Link`.** `Link` is a first-class value (IPLD Data Model kind
  `:link`, DAG-CBOR tag 42) and drives the `:vaet` reverse index through
  `ref?`. A bare **multihash** — the key IPNI actually indexes, and the thing
  that answers *where is it* rather than *what is it* — has no type; in
  `io-ipni-specs` an EntryChunk's `entries` are documented as
  "multihashes (octet vectors), not CIDs" and validated per field.
- **identity vs naming.** `:kotoba.graph/cid` is identity, `:kotoba.graph/head`
  is naming (`kotoba.protocol.ref`: identity is a hash, a name is a mutable
  pointer to a hash). Both are strings at a clause position.

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

Backends that can provide strong commits additionally implement
`ITransactionalStore`: `-snapshot` returns all requested collections and
streams at one tenant revision, and `-transact` compares that revision before
atomically applying document mutations and ordered appends. Transaction ids are
idempotent. `LocalStore` is the reference implementation. Remote use is an
explicit capability negotiation so older servers remain compatible:

```clojure
(kb/kotobase-store xrpc {:transactional? true})
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

Production callers must select the production profile and supply the sealed
store adapter. The profile also requires ABAC, information-flow, mTLS,
hybrid-crypto, signed-capability, request-bound, approval, hardware-signing,
remote-telemetry and recovery-readiness controls. Construction fails before
any XRPC call if any control is absent:

```clojure
(kb/kotobase-store xrpc
  {:deployment-profile :production
   :sealed-store-options {:seal-fn seal!
                          :ciphertext-digest-fn ciphertext-digest}
   ;; plus the mandatory policy/evidence inputs documented by
   ;; kotobase.kotobase/production-profile-violations
   })
```

The one-argument form is a compatibility/development surface and carries no
production confidentiality claim.

The contract suite asserts `KotobaseStore ≡ LocalStore` over a faithful transport, so a
live kotobase.net backend is correct iff it passes the same checks
(`MemStore ≡ DatomicStore` discipline).

## Content-addressed code graph

`kotobase.code-graph` implements the C2–C5 portable storage/query seam from
`kotoba-lang/kotoba-lang`'s
`ADR-kotoba-content-addressed-codebase.md`. It runs over the same `IStore` on a
local atom or kotobase.net XRPC and provides:

- mandatory host-injected CID verification before definition, type, artifact,
  namespace, migration-attestation, and receipt admission;
- dependency-first definition storage and Datom projection;
- dependency closure, reverse dependency, and transitive effect queries;
- compiler-contract-keyed Wasm artifact reuse and analysis caches;
- immutable causal namespace commits (`name -> definition CID`), explicit
  three-way merge conflicts, and hash-qualified resolution;
- capability-checked execution receipt persistence linking code, artifact,
  input/output roots, package lock, policy, CACAO grants, and host receipts;
- authorization-gated sealed/private views, two-XRPC-node missing-block sync,
  verified artifact transfer/reuse, and a host-neutral CID-root execution
  coordinator;
- authorized cross-contract identity migration attestations; and
- auditable pin/revoke events plus a non-destructive GC plan for namespace, release,
  deployment, audit, research, and legal-hold roots.

Cryptographic codecs remain in the language/block layer: the integration suite
uses `kotoba.semantic-code`'s canonical DAG-CBOR definition, namespace,
closure, and execution blocks with real CIDv1 verification. Run it with:

```bash
clojure -M:integration
```

CID possession is never authority. Package signatures/admission, CACAO,
capability intersection, local policy, and Wasm host confinement remain
separate mandatory gates.

### Promise/async hosts

`kotobase.code-graph-async/run!` makes the complete synchronous code-graph API
usable over a Promise-returning IStore. For `ITransactionalStore`, it reads one
revisioned snapshot and flushes all changed documents and new events with one
revision-checked transaction, preventing fractured code-graph commits. Legacy
IStore backends retain the collection-by-collection compatibility path, with
appends flushed in program order.
Use `promise-runtime` in ClojureScript; other completion models can inject the
same `resolve`/`then`/`all` algebra. CI compiles and executes this path under
Node as real ClojureScript rather than relying only on the synchronous JVM test.

`kotobase.causal-commit`, `kotobase.guarded`, and
`kotobase.authorized-query` also have a real Worker completion path. A remote
LLM/model/agent authorizer, the query evaluator, every immutable block write,
the exact-CID reread, and the receipt sink are awaited in order. Any rejection
withholds the rows. The same public `causal-commit/read!`, `receipt-at`, and
ledger adapter remain synchronous on the JVM and Promise-returning in
ClojureScript.

## Comparing this shape with OrbitDB / Ceramic / ActorDB

[`capability-bench/`](capability-bench/) implements the OrbitDB
(Merkle-CRDT oplog), Ceramic (per-stream event logs + columnar projection) and
ActorDB (actor-per-shard, single writer per shard) architectures behind one
capability contract, next to kotobase's own Prolly/commit-DAG shape, and
replays one deterministic datom workload into all four. The block layer is
real — genuine DAG-CBOR/CIDv1 through `io-ipld` and the real `prolly-tree` and
`kotoba-lang/crdt` libraries — so the block and byte counters are properties
of the architectures rather than of a simulation. The three foreign
architectures are re-implementations of their published *shapes*, not their
code; there is no libp2p, no `ceramic-one` and no network, and
`capability-bench/README.md` states exactly what that does and does not
license you to conclude.

```bash
cd capability-bench && npm install && npm run setup
nbb --classpath "$(nbb setup.cljs --print-classpath)" verify.cljs   # all four must agree
nbb --classpath "$(nbb setup.cljs --print-classpath)" run.cljs --fvm
```

The same module also measures the **semantic code graph** —
[`capability-bench/README-semantic.md`](capability-bench/README-semantic.md) —
by chunking the IR that `kotoba.codebase.semantic-code` really produces at
three block granularities (every node / every definition / semantic chunks),
next to the datom projection, the namespace commit plane, and a CID-keyed
evaluation cache that refuses to memoise effects. A third,
[`capability-bench/README-storage.md`](capability-bench/README-storage.md),
puts the result on an object-store profile — requests vs hops, client-side
encryption, CID identity schemes, and a columnar lake projection.

## Consumers

The cloud API workers [local-murakumo](https://github.com/gftdcojp/local-murakumo)
(formerly `cloud-murakumo`, renamed 2026-07-04) and
[cloud-manimani](https://github.com/gftdcojp/cloud-manimani) (cljs Cloudflare
Workers) inject a `fetch`-based `xrpc` and serve the app API straight off the
`:kotobase` store; the desktop/CLI apps use `:local`.

> **Naming note:** the old `io.github.com-junkawasaki/kotobase-clj` coordinate
> redirects to this renamed repository. Current west-managed consumers use
> `io.github.kotoba-lang/kotobase`; see `docs/coverage.edn`'s resolved M5 note.

```bash
clojure -M:test     # LocalStore + KotobaseStore both satisfy the IStore contract
clojure -M:cljs-test -m cljs.main -co '{:target :nodejs :output-to "target/p2-tests.js" :output-dir "target/p2-out" :optimizations :none :main kotobase.async-test-runner}' -c kotobase.async-test-runner
node target/p2-tests.js              # real Promise causal-commit/guarded path
```
