# kotobase capability-bench

Three distributed-database architectures — **OrbitDB** (Merkle-CRDT oplog),
**Ceramic** (per-stream event logs + columnar projection) and **ActorDB**
(actor-per-shard, single writer per shard) — implemented behind one capability
contract and one datom workload, next to **kotobase's own shape** (three
content-addressed Prolly Tree indexes + a commit DAG behind a linearizable
conditional ref).

The question this answers is not "which is fastest". It is: *what does each
architecture refuse to do, and what does the refusal buy?* So every number is
paired with the capability the operation needs, and a backend that lacks the
capability is recorded as `::unsupported` rather than as a fast zero.

## What is real here and what is a model

Real, not simulated:

- **the block layer** — every block is genuine canonical DAG-CBOR with a real
  CIDv1/sha2-256 address, produced by this workspace's own `io-ipld`,
  `dag-cbor` and `io-multiformats` at pinned SHAs;
- **kotobase's index** — the real `prolly-tree` library, including
  `prolly-tree.diff` for structural delta sync;
- **OrbitDB's merge semantics** — the real `kotoba-lang/crdt` LWW-Register and
  Lamport clock, one register per `[entity attribute]`;
- **the workload** — one deterministic datom stream (same seed → same
  entities, values, updates and query keys) replayed byte-identically into
  every backend.

A model, and it must be read as one:

- **the three foreign architectures are re-implementations of their published
  shapes**, not their code. There is no `js-ipfs`, no libp2p, no
  `ceramic-one`, no Erlang. What is reproduced is the structure that
  determines cost — where the writes land, what a query has to materialise,
  what a replica has to fetch — and structure is what the comparison turns
  on;
- **there is no network.** Every store is in-memory, so replication is
  measured in blocks and bytes, not in seconds. That is deliberate: block and
  byte counts are properties of the architecture, latency is a property of
  someone's deployment;
- **wall-clock time is measured under an interpreter** (nbb/SCI). It
  overstates CBOR decode cost relative to a compiled runtime and understates
  everything that is normally I/O. It is reported because it was observed, not
  because it predicts production latency. **Read the counters first.**

If you want the honest one-line summary of the epistemic status: this is a
structural cost comparison with real content addressing, not a deployment
benchmark, and no number here should be quoted as an OrbitDB, Ceramic or
ActorDB throughput figure.

## Running it

```bash
npm install
npm run setup                      # clone the pinned kotoba-lang libraries into .deps/
nbb --classpath "$(nbb setup.cljs --print-classpath)" run.cljs \
    --entities 4000 --updates 200 --shards 8 --fvm
```

Options: `--entities`, `--updates`, `--shards`, `--backends a,b`, `--fvm`,
`--out FILE`. Full results land in `results/latest.edn` (EDN, so it transacts
straight into a datom plane) and the console table in `results/latest.txt`.

## A second benchmark: the semantic code graph

[`README-semantic.md`](README-semantic.md) measures the *other* half of the
"CID graph as a database" question — code rather than rows. It chunks the IR
that `kotoba.codebase.semantic-code` really produces under three block
granularities (every node / every definition / semantic chunks), and measures
the datom projection, the namespace commit plane, and a CID-keyed evaluation
cache. Run it with `run-semantic.cljs`.

## A third benchmark: object store, encryption, identity, lake

[`README-storage.md`](README-storage.md) puts the winning architecture on an
object-store profile and measures what changes there: requests and **hops**
(the dependent round-trip chain that sets the latency floor), client-side
AES-256-GCM, the three CID identity schemes (source text / S-expression /
checked KIR), a columnar lake projection, and the cost of verification.
Run it with `run-storage.cljs`.

## The capability vocabulary

`src/kotobase/capability.cljc` is the contract. A backend declares a set; the
declaration is validated against the vocabulary at construction, and the
harness guards the operations that genuinely cannot be performed:

| capability | what a backend must actually do to claim it |
|---|---|
| `:immutable-blocks` | facts are CIDv1 blocks |
| `:cid-verified-read` | the reader recomputes the CID before decoding |
| `:conditional-ref` | linearizable compare-and-set on the mutable ref |
| `:multi-writer-merge` | disconnected writers converge by merge, not coordination |
| `:linearizable-txn` | a transaction lands atomically across all it touches |
| `:cross-shard-txn` | atomicity survives a shard/actor/stream boundary |
| `:covering-index` | answers "which entities have `a = v`" without materialising the database |
| `:verifiable-index` | index membership is provable against the root CID |
| `:range-scan` | ordered range access without a full materialisation |
| `:global-snapshot` | one consistent read-state across all entities |
| `:time-travel` | an arbitrary past state is a readable value |
| `:structural-delta-sync` | replica convergence costs O(changed subtrees) |
| `:log-replay-sync` | replica convergence by replaying missing log entries |
| `:interest-sync` | a replica may sync only the subset it declared interest in |
| `:analytical-projection` | a columnar projection exists beside the transactional index |
| `:deterministic-execution` | `old-root + message + code-CID → new-root` is re-executable by a second party |

Two deliberate design decisions in the harness:

- **`find-by-value` and `range-scan` are never refused.** A backend without a
  covering index can still answer them — by materialising everything — and
  refusing to measure that would hide the exact cost the comparison exists to
  show. Every answer instead carries `:via` (`:index`, `:index-fanout`,
  `:projection`, `:full-materialisation`, `:materialised-index`), and the
  matrix says which of those is index-backed.
- **`snapshot-read` is guarded on `:global-snapshot`, not `:time-travel`.**
  All four shapes can travel in time inside one log or stream. What separates
  them is whether a single basis names a consistent state across the whole
  database — and Ceramic's honest answer is that it does not.

## `:deterministic-execution` and the FVM boundary

`--fvm` re-runs every shape with the block ports wrapped in
`blockstore/fvm-boundary`: each block is copied on the way in and on the way
out of a host/guest boundary, each crossing is counted, and the commit block
gains a `code` link to the actor code's CID — which is what actually earns the
capability, because it is what lets a second party re-execute the transition
and check the root.

This is the concrete version of the FVM design question. The capability is not
free and it is not catastrophic; it is a per-block-access tax, and the
benchmark reports the crossing count so the tax can be recomputed with any
per-syscall constant rather than the one this container happens to have. The
architectural consequence follows from the counts, not from the milliseconds:
the shape that touches the most blocks per transaction is the shape that pays
the most for determinism.

## Measured results

Run of 2026-08-06, 4 000 entities / 20 000 datoms, bulk-loaded as 16 batched
transactions, then 200 single-entity steady-state transactions; `actordb` at 8
shards. Raw output in `results/latest.txt`, full EDN in `results/latest.edn`.
Counters are exact; milliseconds are interpreter-bound (see the caveats
above).

### Capability matrix

| capability | kotobase-prolly | orbit | ceramic | actordb |
|---|---|---|---|---|
| immutable-blocks / cid-verified-read | yes | yes | yes | yes |
| conditional-ref | yes | – | – | yes |
| linearizable-txn | yes | – | – | yes |
| cross-shard-txn | – | – | – | yes |
| multi-writer-merge | – | yes | yes | – |
| covering-index | yes | – | – | yes |
| verifiable-index | yes | – | – | yes |
| range-scan | yes | – | – | yes |
| global-snapshot | yes | yes | – | yes |
| time-travel | yes | yes | yes | yes |
| structural-delta-sync | yes | – | – | yes |
| log-replay-sync | – | yes | yes | – |
| interest-sync | – | – | yes | – |
| analytical-projection | – | – | yes | – |

`:deterministic-execution` is earned only by the `--fvm` variants, which put a
`code` CID in the commit so a second party can re-execute the transition.

### Write cost, per steady-state transaction

| backend | block puts | put bytes | block gets | get bytes | actor msgs |
|---|---|---|---|---|---|
| kotobase-prolly | 14.32 | 176 947 | 22.56 | 274 376 | 0 |
| orbit | 1 | 158 | 0 | 0 | 0 |
| ceramic | 1 | 125 | 0 | 0 | 0 |
| actordb (8 shards) | 8.15 | 74 276 | 13.49 | 122 461 | 3 |

Three covering indexes plus a commit block cost **14× the block writes and
~1 400× the bytes** of one append-only entry. That is the price of the
capability column above it, not an inefficiency: the gets are the index paths
being read to be rewritten, and the retraction read that keeps AVET a
current-value index.

### Query cost, per operation

| operation | kotobase-prolly | orbit | ceramic | actordb (8) |
|---|---|---|---|---|
| point read | 3.02 gets / 25.8 KB | **0 gets** (local index) | 1.05 gets / 142 B | 2.15 gets / 17.3 KB |
| find by value | 3.46 gets / 42.6 KB, `:index` | 0 gets, **4 000 entities scanned** | 81.9 gets amortised, `:projection` | 17.46 gets / 286 KB, **8-way fan-out** |
| range scan | 16 gets / 176 KB | 0 gets, 4 000 scanned | 0 gets (projection warm) | 30 gets / 335 KB |
| snapshot at basis | 3 gets / 24.7 KB | **216 gets / 499 KB per op** | **unsupported** | 2.05 gets / 15.1 KB |

Four things worth reading twice:

- **OrbitDB's free reads are real and so is what pays for them.** Zero block
  reads, because the answer comes from a local index outside the hash graph —
  and therefore an unprovable one, rebuilt by replay, and unordered, so
  `find-by-value` and `range-scan` scan all 4 000 materialised entities every
  time.
- **Ceramic's projection is the whole story.** Building it once cost 4 095
  block reads over 3 902 streams; amortised over 50 queries that is 81.9
  reads/op, and by the time the range scans ran it was warm and they cost
  **zero** block reads. Cross-stream queries are not slow here — they are
  *derived*, and the derivation is what you pay for and what goes stale.
- **Time travel by log replay does not amortise.** OrbitDB walks the entire
  216-entry log for *every* snapshot read — 216 blocks and 499 KB per
  operation against kotobase's 3 blocks and 24.7 KB, because a commit DAG
  makes a past state a root you address rather than a computation you redo.
- **Ceramic refuses.** There is no cross-stream order to anchor a basis to, so
  `snapshot-read` returns `::unsupported`, not a number.

### Replica sync

| scenario | kotobase-prolly | orbit | ceramic | actordb (8) |
|---|---|---|---|---|
| from empty | 222 blocks → 60 800 entries | 216 blocks → 216 entries | 4 200 blocks → 4 200 events | 183 blocks (critical path **26**) → 40 400 entries |
| 200 transactions behind | 273 blocks → 1 192 entries | 200 blocks → 200 entries | 200 blocks → 200 events | 244 blocks (critical path 36) → 792 entries |
| interest-scoped (100 of 4 000 entities) | 273 blocks (ignores interest) | 200 blocks (ignores interest) | **7 blocks** | 244 blocks (ignores interest) |

The structural-delta claim survives, but only in the regime it is actually
about. Catching up a **large** divergence, kotobase moves 60 800 entries for
222 block reads, because unchanged subtrees cost one CID comparison each.
Catching up a **small, scattered** one, it reads *more* blocks than OrbitDB
reads log entries (273 vs 200) — 200 single-entity updates touch most of the
78 leaves, so almost nothing is shared. A Prolly diff is cheap in proportion
to what two roots have in common, and that is not the same statement as "delta
sync always beats log replay".

Two honest artefacts of this workload: OrbitDB's log is 216 entries only
because the bulk import was batched into 16 transactions — per-entity writes
would make it 4 200, like Ceramic's, and every snapshot read would scale with
it. And Ceramic's interest-scoped win is genuine but narrow: it is the only
shape here that can decline to sync data it does not want.

### Stored state after the whole run

| backend | blocks | bytes |
|---|---|---|
| kotobase-prolly | 2 983 | 44.3 MB |
| orbit | 216 | 499 KB |
| ceramic | 4 200 | 569 KB |
| actordb (8 shards) | 2 478 | 23.2 MB |

The two orders of magnitude are copy-on-write history, not waste. A Prolly leaf
holds ~256 entries, every touched leaf becomes a *new* immutable block, and the
old one stays reachable — which is what makes any past basis a root you can
address. Ceramic stores the most *blocks* (one event per write per stream) and
the fewest bytes; kotobase stores the fewest blocks per write and the most
bytes. Deduplication is by CID, so identical subtrees are stored once.

### Sharding trade curve (actordb, 2 000 entities / 100 transactions)

| shards | puts/txn | point-read gets | find-by-value gets/op | cross-shard txn msgs | sync blocks | sync critical path |
|---|---|---|---|---|---|---|
| 1 | 11.12 | 2.02 | 2.7 | 3 | 71 | 71 |
| 4 | 8.01 | 2.02 | 8.7 | 14 | 83 | 23 |
| 16 | 6.21 | 1.92 | 31.7 | 50 | 130 | 12 |
| 64 | 4.19 | 1.42 | 86.7 | 191.75 | 241 | 9 |

Monotone in both directions, which is what makes it a real trade rather than a
tuning knob: 64 shards write **2.7× fewer blocks per transaction** and cut the
sync critical path from 71 blocks to 9, while paying **32× more block reads on
every value query** and **64× more messages on every transaction that spans
shards**. Point reads barely move — a shard's tree is smaller, but the descent
was already logarithmic.

Note that the steady-state transactions here each touch one entity, so they
take the single-shard fast path (3 messages). The two-phase commit cost is
visible in the bulk-load column, where every transaction spans every shard.

### The FVM boundary

| backend | boundary crossings/txn | bytes copied/txn | crossings per point read | crossings for a full sync |
|---|---|---|---|---|
| kotobase-prolly+fvm | 36.88 | 472 605 | 3.02 | 222 |
| actordb+fvm | 21.64 | 205 622 | 2.15 | 183 |
| orbit+fvm | 1 | 204 | **0** | 216 |
| ceramic+fvm | 1 | 171 | 1.05 | 4 200 |

This is the FVM question in one table. Determinism costs a per-block-access
tax, so the shape that touches the most blocks per transaction pays the most
for it: putting a three-index Prolly transaction inside a Wasm boundary means
**~37 host crossings and half a megabyte copied per transaction**, against one
crossing and 204 bytes for an append-only entry. The wall clock barely moved
here because an in-memory copy is nearly free — which is exactly why the
crossing count, not the millisecond figure, is the number to carry: multiply
it by whatever a syscall costs in your executor.

The architectural reading is the one the FVM specification itself gives for
fine-grained IPLD access: it is not that FVM is too slow to hold a database,
it is that **the index shape decides the tax**. An architecture that already
touches one block per write barely notices the boundary; one that rewrites
three index paths per write notices it 37 times.

## Layout

```
src/kotobase/capability.cljc                     capability vocabulary + guards
src/kotobase/capability/blockstore.cljc          instrumented blocks, refs, messages, FVM boundary
src/kotobase/capability/workload.cljc            deterministic datom workload + index key encoding
src/kotobase/capability/backend.cljc             the six-operation seam
src/kotobase/capability/backend/kotobase_prolly.cljc
src/kotobase/capability/backend/orbit.cljc
src/kotobase/capability/backend/ceramic.cljc
src/kotobase/capability/backend/actordb.cljc
src/kotobase/capability/bench.cljc               phases + accounting
run.cljs / setup.cljs                            nbb entry points
```

Everything except the two entry points is `.cljc`: the backends are portable
and carry no host effects, exactly like the libraries they are built on.
