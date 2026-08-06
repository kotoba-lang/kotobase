# Semantic Merkle Lisp Database — measured

The "S-expressions are the database" model, implemented and measured: canonical
name-free IR as CID blocks, a datom projection for the questions CIDs cannot
answer, a namespace commit plane, and a CID-keyed evaluation cache that refuses
to memoise effects.

Run it:

```bash
npm install && npm run setup
nbb --classpath "$(nbb setup.cljs --print-classpath)" run-semantic.cljs \
    --definitions 2000 --eval-slice 200 --shape-definitions 150
```

Results below: 2 000 definitions, 111 729 IR nodes, `library` dependency shape,
2026-08-06. Raw output in `results/semantic.txt`, EDN in `results/semantic.edn`.

## What this is built on — most of it already existed

The design being tested is, in this workspace, **already decided and largely
shipped**. Before writing anything I found:

- **`kotoba-lang/codebase`** — `semantic-code` lowers a checked definition to
  canonical DAG-CBOR whose CIDv1 is the definition identity: local binders
  become de Bruijn indices, resolved globals become tag-42 IPLD links, and the
  definition's own name is absent from the hashed block. `typed-code` moves
  that identity onto the compiler's **checked KIR**, which is exactly the
  "definition identity is the checked-KIR CID" priority. Namespace commits,
  closure blocks, execution receipts and pure build-cache keys are all there.
- **`kotobase.code-graph`** — the datom projection of that graph
  (`definitions`, `types`, `artifacts`, `namespace-commits`,
  `execution-receipts`, `query-receipts`), which is the query plane.
- **ADR-2608550000** (identity = checked KIR, alpha-normalisation verified
  rather than assumed, execution through `kotoba.kir/execute`) and
  **ADR-2608580000** (the kotobase code graph is a *projection* of codebase,
  never a premise — with a deletion test).

So this benchmark does not re-propose the architecture. **Every strategy here
chunks the IR that the real `kotoba.codebase.semantic-code` actually produces**,
under nbb, at a pinned SHA. Identity is held constant; only block boundaries
move. What was genuinely open was the granularity question — proposals A
(every node), B (every definition), C (semantic chunks) — and the cost model
attached to it, which was explicitly labelled as estimates rather than
measurements.

The corpus is generated, but not invented: parsing every `.kotoba` file in the
workspace (72 definitions in `kotoba-lang/crdt` and `kotoba-lang/hash`) gives
**49.5 nodes/definition mean, 40 median, depth 7.2 mean / 15 max**. The
generator targets that and reports what it produced (55.9 mean, 57 median,
depth 8.1 mean / 11 max). Dependency fan-out (8) is the design note's
assumption, carried through as an assumption.

## 1. Block granularity

| strategy | blocks | blocks/def | stored bytes | bytes/def | blocks to hydrate one def | 10% edit: new blocks | 10% edit: new bytes |
|---|---|---|---|---|---|---|---|
| per-node (A) | 29 795 | 14.9 | 4.27 MB | 2 133 | 47.1 | 11 659 | 1.85 MB |
| per-definition (B) | 2 002 | 1.0 | 3.26 MB | 1 629 | **1** | 1 717 | 2.84 MB |
| semantic-chunk (C) | 16 235 | 8.1 | 3.83 MB | 1 913 | 8.3 | 8 691 | 2.11 MB |

Three things the estimates did not have:

**Deduplication is much stronger than the per-node estimate assumed.** The
corpus has 55.9 IR nodes per definition but per-node chunking stores only
**14.9 blocks per definition** — 73% of nodes collapse, because
`{op: local, index: 0}` and `{op: intrinsic, id: …/+}` are the same block
everywhere they appear. The design note's projection of "40 M blocks for 1 M
definitions" over-counts by ~3.7×, and its 5–9 GiB storage figure for proposal
A over-counts correspondingly. Measured, linearly extrapolated to 1 M
definitions (**extrapolation, not measurement**): B ≈ 1.63 GB / 1.0 M blocks,
C ≈ 1.91 GB / 8.1 M blocks, A ≈ 2.13 GB / 14.9 M blocks. The note's own
estimate for B was 2.5–3.5 GiB; the measured figure is below that.

**But the read cost is as bad as predicted.** Hydrating one definition for
execution costs 1 block fetch at definition granularity and **47 at node
granularity** — more than the 14.9 unique blocks, because a reader without a
node cache re-fetches every shared leaf. That is the "括弧がストレージ代を請求
し始める" effect, and it is the reason execution must go through a compiled
artifact rather than by interpreting the DAG.

**The write-amplification trade runs the opposite way to the block count, and
that is the actual argument for chunking.** On a 10% edit, definition
granularity writes the fewest blocks (1 717) but the most *bytes* (2.84 MB),
because a one-literal change rewrites a whole ~1.6 KB definition. Semantic
chunks write 5× more blocks but **26% fewer bytes**; per-node writes 7× more
blocks and **35% fewer bytes**. If your bottleneck is bytes on the wire, chunk;
if it is round trips, do not.

## 2. Query plane — why the object plane alone is not a database

Same question, same 155 answers: *which definitions declare an effect?*

| | blocks read |
|---|---|
| datom index (Prolly Tree over the projection) | **2** |
| object plane only (open every definition + its type block) | 4 000 |

A CID fetches something you already know the address of. It cannot find
anything. The projection is not an optimisation here — it is the difference
between a content-addressed repository and a database, and the factor is 2 000×
at 2 000 definitions and grows linearly.

Reverse dependency, out of the same index:

| seed | affected definitions | index blocks read |
|---|---|---|
| 20 hub definitions | **1 999 of 2 000** | 4 032 |
| 20 leaf definitions | 20 | 40 |

## 3. Namespace commits — a measured defect in the shipped design

`kotoba.codebase.semantic-code/namespace-block` inlines every binding into one
sorted map. It is correct and content-addressed, and it costs the same whether
you changed everything or one thing:

| second commit after… | flat namespace block (shipped) | name→CID Prolly Tree |
|---|---|---|
| 1 717 definitions changed | 98 058 B | 150 957 B |
| **1 leaf definition changed** | **98 058 B** | **19 776 B** |

A one-definition commit rewrites the entire namespace — 98 KB at 2 000 names,
and linear in the namespace from there. Putting the same map in a Prolly Tree
makes the unchanged regions shared by CID and drops that to 19.8 KB, at the
cost of being larger when everything changes anyway. This is worth fixing
upstream; it is the one place where the measurement contradicts what is
currently shipped rather than confirming it.

## 4. Evaluation plane — and the number the estimate got wrong

Pure definitions are memoised on (definition CID, argument values). Definitions
whose type block declares effects are **never** memoised; each application
produces a receipt instead.

| | evaluations | cache hits | receipts | ms |
|---|---|---|---|---|
| cold | 1 263 | 1 117 | — | 5 980 |
| rebuild after a **leaf** edit | 14 | 222 | 14 | **365** |
| rebuild after a **hub** edit | 1 712 | 1 087 | 1 169 | **7 938** |

A leaf edit gives a **16× faster** rebuild. A hub edit is **slower than the
cold run** — the cache is dead weight, every dependent has a new CID, and the
effectful definitions re-execute and re-issue receipts regardless.

That asymmetry is the finding, and it invalidates the estimate's premise. The
design note assumed "80% of the build is unchanged" and derived 2.27×, then
3.8×. But in a Merkle code graph the cache hit rate is **not** one minus the
edit fraction — an edit changes the CID of every *transitive dependent*, so
reuse is a property of the call graph and of where the edit lands:

| dependency shape | definitions edited | edit lands on | CIDs changed (of 150) | invalidation per edit | cache reuse |
|---|---|---|---|---|---|
| library | 1 | leaf | 1 | 1× | **0.99** |
| library | 1 | hub | 150 | **150×** | **0** |
| library | 3 | random | 150 | 50× | 0 |
| library | 15 | leaf | 15 | 1× | 0.90 |
| library | 15 | random | 150 | 10× | 0 |
| uniform | 1 | leaf | 1 | 1× | 0.99 |
| uniform | 1 | hub | 150 | 150× | 0 |
| uniform | 3 | random | 150 | 50× | 0 |
| chain | 1 | random | 54 | 54× | 0.64 |
| chain | 1 | hub | 150 | 150× | 0 |
| chain | 15 | leaf | 15 | 1× | 0.90 |

Read the `random` rows: they are **bimodal, not average**. A random edit either
misses every hub and invalidates almost nothing, or touches one and invalidates
the whole downstream graph. At 2 000 definitions a random 10% edit changed
1 717 identities — reuse 0.14. There is no useful mean here to plan with; the
honest planning number is *"what does this definition's dependent set look
like"*, which is exactly what the reverse-dependency index in §2 answers.

Practical consequence: a CID build cache is worth a great deal for leaf work
and nothing for library work, and a system that reports one blended hit-rate
figure is hiding the only variable that matters.

## Caveats

- **The corpus is generated**, in the subset the C1 lowering accepts (`defn`
  over intrinsics, `if`/`let`/calls). Shape is calibrated against real
  `.kotoba` files; content is not real code.
- **`semantic-chunk`'s hydrate figure excludes its dependency-vector block**
  (+1 block when you need the closure); the other two carry that vector inline
  in the root block, so they pay for it in bytes instead.
- **Per-node hydration assumes no node cache.** With one, the fetch count falls
  toward the 14.9 unique blocks; the 47 figure is the cold, honest number.
- **Milliseconds are interpreter-bound** (nbb/SCI) and in-memory. Block, byte
  and invalidation counts are the transferable results. In particular the real
  lowering takes ~29 s for 2 000 definitions here, which says nothing about
  the compiler and everything about running it under SCI.
- **No Wasm.** The execution plane is modelled as an artifact-CID cache and a
  small KIR interpreter; it does not compile or run WebAssembly, so nothing
  here measures `kotoba.kir/execute`.
- **Extrapolations to 1 M definitions are linear** and labelled as such. Block
  count and bytes per definition are stable across the sizes measured, but
  index depth and dedup rate are not guaranteed to stay linear.
