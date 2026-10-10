# ADR-2610101200: Multi-parent logical commits and deterministic Datom merge

- Status: Proposed
- Date: 2026-10-10
- Authority: kotoba-lang/kotobase ADR-2608090000 (canonical CID commit DAG);
  implementation in kotoba-lang/kotobase-engine-contract
  (`kotobase.engine.identity`, `kotobase.engine.merge`,
  `kotobase.engine.frontier`)

## Context

`docs/storage-architecture.md` states that concurrent writes "branch before
deterministic merge", and `kotobase-engine` `commit-at!` already produces
explicit branches. ADR-2608090000 qualifies DAG mechanics on both Kotoba
targets: canonical CID-byte parent order, shared-ancestor closure,
deduplication, causal height and effect-free merge markers. What was missing is
the database meaning of a merge:

- `kotobase.logical-commit/v1` rejects more than one parent;
- `frontier/accept-head` rejects a same-epoch sibling as `:equivocation` and
  has no way to accept the commit that reconciles it;
- no rule says which Datoms a merged database contains.

Without that rule, two writers that diverge offline can only converge by
letting a central compare-and-swap pick a winner, which contradicts the
canonical route's "providers never author graph truth".

## Decision

### 1. Identity: `kotobase.logical-commit/v2` is the merge commit

V1 is unchanged. Its keys, validation and canonical bytes are identical to the
pre-ADR form, and a golden test pins both the canonical string and its
SHA-256 for a fixed v1 commit. Every existing v1 CID is therefore stable.

V2 has the v1 keys plus `:merge-policy` and differs only in parents:

| field | v2 rule |
|---|---|
| `:format` | `"kotobase.logical-commit/v2"` |
| `:merge-policy` | `"kotobase.merge/v1"` (closed; a new policy is a new value) |
| `:parents` | 2..16 distinct printable-ASCII CID strings, strictly ascending string order |
| `:epoch` | `1 + max(parent epochs)` (= causal height, so > 0) |
| `:tx-root` | root of the deterministic resolution transaction (section 3) |
| `:logical-checkpoint-root` | root of the merged visible Datoms |
| `:schema-root`, `:model-contract-root`, `:admission-policy-root`, `:database-id` | must be equal across all parents |

Canonical encoding is the existing `kotobase.logical/v1` envelope in the
`:logical-commit` domain; `:format` inside the value separates v1 from v2.

Decisions within this:

- A zero- or one-parent commit MUST be v1. If v2 could also spell a linear
  commit, the same history would have two CIDs.
- Parents are validated, never normalized. `canonical-parents` (sort +
  distinct) is a constructor helper; a validator that silently re-sorted would
  accept bytes a signer did not sign.
- ASCII multibase CIDs make string order equal CID byte order, matching the
  "canonical CID-byte parent order" already qualified by
  `kotoba/cid_dag_traversal.kotoba`. `canonical-parents` rejects any parent
  that is not printable ASCII, so no host can sort it differently.
- Fan-in is bounded at 16. `merge` checks the distinct head count before it
  walks any history.
- `:merge-policy` is inside the signed value so a future policy change cannot
  reinterpret an old merge.
- Schema-changing merges are out of scope: heads whose schema, model contract
  or admission policy roots differ are rejected (`:incompatible-heads`). The
  schema is applied to all of history, so every ancestor in the closure must
  also carry the heads' `:schema-root` (`:incompatible-history`). This is a v1
  limitation. Per-commit schemas need a schema per root and are an open gate.

### 2. Merge base via ancestor closure

`closure(c) = {c} ∪ closure(parents(c))`. The union closure of the heads is
admissible only if all of these hold. Otherwise the merge fails closed:

- every commit's root and `:tx-root` bind its bytes (`:root-mismatch`,
  `:tx-root-mismatch`);
- every commit has the heads' `:database-id` (`:database-mismatch`) and
  `:schema-root` (`:incompatible-history`);
- a parentless commit is at epoch 0, and every other commit is at exactly
  `1 + max(parent epochs)` (`:invalid-epoch`). This makes epoch the causal
  height, makes the graph acyclic, and makes `(epoch, CID)` a linear extension
  of causality. A forged high-epoch genesis can no longer win every
  last-writer-wins decision;
- the closure has exactly one genesis (`:unrelated-histories`). So any two
  heads share a common ancestor, and two unrelated databases that happen to
  share a `:database-id` cannot be merged into one;
- the walk visits at most `:max-closure` commits (default 100,000;
  `:closure-too-large`), so an attacker-supplied graph cannot make the walk
  unbounded.

- `reduce-heads`: remove duplicates and every head that is in another head's
  closure. If one head remains, the result is `:fast-forward` to it and no
  commit is created. So `merge(A, A) = A` and `merge(M, ancestor) = M`.
- `merge-bases`: the maximal elements of `∩ closure(head)`. This set is never
  empty because of the single genesis. A criss-cross has more than one base.
  The bases are reported. The Datom rule below is defined over events and
  causality, so it needs no recursive "virtual base".

### 3. Datom merge policy `kotobase.merge/v1`

Every normalized transaction datom in `⋃ closure(head)` is an event stamped
`[epoch commit-cid index]`. Event `x` happens before `y` if `x.commit` is a
proper ancestor of `y.commit`, or if both are in the same commit and
`x.index < y.index`. Datoms are identified by their canonical
`kotobase.logical/v1` encoding, never by host equality. So `[1 2]` and
`(1 2)` are different values in the merge exactly as they are in the
checkpoint bytes, and a retraction of one never removes the other. The same
holds for deduplicating loser retractions and for comparing a stored merge
transaction in `verify-merge`.

**Principle:** causality decides whenever it can. The stamp order decides only
between causally concurrent events that the schema says cannot coexist. Every
stamp decision is written as an explicit Datom into the merge commit.

1. **Set union with observed-remove.** An assertion event `a` of `[e attr v]`
   is *live* unless a later event on the same `[e attr]` removes it. Later
   means `a` happens before it. That event is a retraction of `[e attr v]`, or,
   for a cardinality-one `attr`, an assertion of a different value (implicit
   replacement). A Datom is visible if it has at least one live assertion. This
   is the set union of both sides' assertions and retractions since the merge
   base, with each retraction applying only to what its writer observed.
2. **Retraction vs concurrent assertion.** If a visible Datom has a
   retraction that no later assertion of that Datom has observed, the live
   assertion wins (add-wins / OR-set). The merge transaction re-asserts the
   Datom (`:op :assert`), so the decision is a history event attributed to the
   merge commit. This rule uses no CID tie-break. A re-assertion is *not* a
   new write for ordering: it keeps the stamp of the newest live assertion it
   preserves. Its causal position, which is the merge commit, only records
   that the retraction was observed. Otherwise a merge would push an old
   value ahead of writes made concurrently with the merge, and
   `(A ∪ B) ∪ C` would differ from `A ∪ B ∪ C`.
3. **Cardinality-one conflict.** If more than one value of `[e attr]` is still
   visible, the value whose newest live assertion has the greatest stamp wins:
   higher causal height first, then higher commit CID, then later tx index.
   Every losing value is retracted in the merge transaction.
4. **`:db/unique` conflict.** If one `[attr v]` is held by more than one
   entity, the entity whose earliest live assertion has the smallest stamp
   keeps it: lower causal height first, then lower CID. Each other holder's
   Datom is retracted in the merge transaction. Steps 3 and 4 iterate to a
   fixpoint. If an entity's cardinality-one winner loses its unique value, the
   entity falls back to its next-ranked live value, and that value is checked
   for uniqueness again. So an entity never loses every value because two
   rules each took one. This is deferred acceptance (Gale-Shapley): entities
   propose values in rank order, and each unique value keeps its earliest
   claim. So the result does not depend on processing order. Each live datom
   proposes at most once, so the fixpoint costs O(values) per merge.
5. The **resolution transaction** contains only these decisions: retractions
   sorted canonically, then re-assertions sorted canonically. It holds no user
   intent. A writer who wants new facts adds a v1 commit on top of the merge.

Steps 3 and 4 point in opposite directions on purpose. For cardinality-one,
the last write replaces earlier ones. For uniqueness, the first claim stands,
so an offline writer cannot take a unique value away from an entity that
claimed it earlier. This is the rule for `:db.unique/value`, where a serial
executor would have rejected the later claim. It is *not* what a serial
executor does for `:db.unique/identity`: there, a later
`[:db/add tempid attr v]` would upsert into the existing entity instead of
failing. That would merge two entities, and an entity merge cannot be undone
by retracting Datoms. `kotobase.merge/v1` therefore treats both kinds of
uniqueness as first-claim-wins and never merges entities. Identity upsert
across branches is an open gate.

Conflicts are rejected per Datom, not per transaction. Rejecting a whole
transaction would cascade to every later commit on that branch that depends on
it.

Properties that follow by construction:

- **Commutative:** the result depends only on the reduced head set, and parents
  are canonically sorted, so `merge(A, B)` and `merge(B, A)` give byte-identical
  CIDs.
- **Convergent for the same merge DAG, not associative in general:** the
  result is a function of the commit DAG. Replicas that hold the same merge
  commits compute the same state. A *different* merge order can give a
  different state, because a merge's loser retraction is a permanent event.
  `(A ∪ B) ∪ C` equals `A ∪ B ∪ C` only if no intermediate merge decided a
  conflict whose winner another head concurrently removes, and no step 3/4
  fallback is involved. "Removes" means a retraction, or a cardinality-one
  replacement of the winning datom. Randomized testing of three-head merges
  (about 1-3% of random histories) found no other kind of divergence.
  Counterexamples, pinned as tests
  (`merge-order-is-deterministic-but-not-associative`):
  - cardinality-one: `x` writes `w` (epoch 2), `y` writes `l` (epoch 1), and
    `c` (after `x`) retracts `w`. `{x, y, c}` keeps `l`. `(x ∪ y) ∪ c` has
    already retracted `l` for `w`, so `e` has no value.
  - unique: genesis gives `h` to `e3`, `a` retracts it, and `b` gives `h` to
    `e2`. `{a, b, c}` lets `e2` keep `h`. `a ∪ (b ∪ c)` has already retracted
    `e2`'s claim in favour of `e3`, so nobody holds `h`.

  Merge order is part of history. It is not an implementation detail that
  replicas may choose freely. Writers that want the n-way result merge all
  their heads in one merge commit (up to 16).
- **Idempotent:** merging an ancestor is a fast-forward. A merge commit
  happens after every event it resolves, so later merges find those conflicts
  closed and do not record them again.
- **Criss-cross convergent:** two independent merges of the same conflict pick
  the same winner, because the stamp does not depend on which writer merged.
  The final merge resolves only conflicts that are still open.
- **Verifiable, recursively:** `merge`, `state` and `verify-merge` replay
  the closure once in `(epoch, CID)` order. They recompute *every* v2 commit
  they meet from its parents' states and reject it unless the bytes are
  identical. That includes a merge with a dropped loser retraction, a wrong
  epoch, or a redundant ancestor parent. A forged inner merge therefore
  cannot be laundered by an honest outer merge. Each commit is verified once
  per call.

### 4. Frontier: merge is descent, not equivocation

`accept-head` keeps every v1 rule: rollback, same-epoch `:equivocation`,
and contiguous single-parent edges, so an all-v1 path is exactly as long as
the epoch distance. Merge commits change only these things:

- A v2 proof entry must carry `:parent-entries`: one entry for *every*
  parent, in canonical order. Each entry passes the injected
  CID/signature `verify-entry`, and each must have the merge's
  `:database-id`. The merge's epoch must be exactly `1 + max(parent epochs)`
  (`:invalid-epoch`). An entry without `:parent-entries` is
  `:invalid-proof`. So a merge over a nonexistent or unsigned parent, a merge
  with an inflated epoch, and a merge with an understated epoch beside a
  higher parent are all rejected.
- The path may go through any one parent. That parent's declared entry must
  agree with the next path entry (or with last-seen). Each edge is therefore
  exact: +1 for v1, or the verified `1 + max(parents)` for v2. The epoch
  distance is fully proven even though the path is shorter than the
  distance.
- The other parents' own ancestry is not part of a descent proof. The
  *whole path* may therefore skip at most `:max-epoch-skip` epochs in total:
  `distance ≤ path length + max-epoch-skip` (default 2^20,
  `:epoch-skip-too-large`). This holds per edge and summed over all merges
  on the path, so neither one signed merge nor a chain of them can push the
  frontier to the safe-integer limit in one acceptance, which would leave
  honest successors permanently at `:rollback`. A nil or negative bound is
  rejected (`:invalid-option`).
- A different root at the last-seen epoch is still `:equivocation`. A merge
  commit whose parents include the last-seen root is at a higher epoch and
  descends from it, so it is `:advanced`, and the result carries
  `:merge-parents`. A client that saw A and then the equivocating B can accept
  `merge(A, B)` without a coordinator. A client that saw B can do the same.
  Every merge on the path is reported as `:merges [{:root :parents}]`
  (candidate first).
- The frontier is a single-writer view. An honest concurrent sibling (two
  writers committing offline) shows up as `:equivocation` at the same epoch
  or `:not-descendant` at a higher epoch. In a single-writer database that is
  evidence of misbehaviour. A multi-writer client treats it as "a merge is
  needed": it keeps its frontier and computes or waits for `merge(A, B)`,
  which then advances it. It never adopts the sibling directly.
- Descent proof does not prove that the merge was computed correctly. Nor
  does it prove that its other parents belong to the same history. A merge
  whose other parent comes from an unrelated history that reuses the
  `:database-id`, signed by an accepted key, is accepted by the frontier. A
  client that reads state through a merge MUST run `verify-merge` over the
  fetched closure, which rejects it (`:unrelated-histories`) and recomputes
  every merge.

## Consequences

- Two offline writers converge by exchanging blocks. Each computes the same
  merge CID locally, and a mutable ref or CAS is only an optional
  availability hint.
- Every non-causal decision is an auditable Datom in a signed commit.
- The reference `merge` replays the full union closure to genesis once. A v1
  commit costs O(|tx| · log n), because each commit's state is the parent's
  persistent state plus that commit's events. A merge commit, whether met in
  history or computed, costs:
  - O(D log n + T) to join its parents and extend the closure set. D is the
    commits that one side has and the accumulated side lacks, plus their
    events. Only datoms those commits touch are re-joined
    (cardinality-one live sets per entity/attribute, because an assertion
    replaces the other values). T is the number of dots compared for those
    datoms. A datom touched only on the accumulated side already holds the
    joined value, so that side is not walked. Everything else, including
    unobserved retraction tombstones, is shared structurally;
  - O(S + K) to resolve conflicts. S is the live datom count and K the live
    assertion-dot count. A datom keeps one dot per surviving assertion,
    because concurrent retractions remove dots individually. Resolution takes
    the max/min rank over all of them. Repeated assertions inside one
    transaction collapse to one dot. The unique fixpoint adds O(S);
  - O(S log S) to recompute the checkpoint root. This term is inherent to the
    commit format,
    because every v2 commit names a flat `:logical-checkpoint-root` over its
    full state. Removing it needs an incremental (Merkle/Prolly) checkpoint
    root, which is an open gate. Each distinct datom's canonical sort key and
    checkpoint fragment are encoded once per replay. The checkpoint bytes are
    assembled from those fragments and are byte-identical to
    `checkpoint-string`, so a merge step does not re-encode its whole state.

  Total: O(E log n + Σ (D + T) + M · (S log S + K)) for E events and M
  merges. The `M · S` term is not linear: a long history of merges over a
  growing state is quadratic. Two fail-closed bounds therefore apply.
  `:max-closure` (default 100,000 commits) bounds the walk. `:max-work`
  (default 5,000,000 units; `:work-budget-exceeded`) bounds the replay work.
  It charges one unit per event and, per merge step (verified or computed),
  D + T + S + K plus the fixpoint's proposals. Every loop that scales with
  the input is charged *before* or *as* it runs, so an attacker cannot buy
  unbounded CPU with few units. Duplicate dots, cascading unique fallbacks
  and retraction tombstones all count, or are not walked at all.

  Sizing: S + K per merge dominates. 5,000,000 units is about 300 merges at
  16,000 live datoms, or about 5,000 merges at 1,000. A larger database
  raises `:max-work` explicitly or, better, starts the replay from a trusted
  checkpoint. Both the checkpoint/snapshot start and the incremental
  checkpoint root are open gates below.

  Measured: a 4000-commit chain costs about 4x a 1000-commit chain. With
  constant visible state, 1000 merges cost about 4x 250 merges in work units
  (round-1 code took about 10x the time; 4000 merges took 3.7 s instead of
  52 s). 100,000 duplicate assertions in one transaction now add their 10^5
  events once instead of once per merge. A unique cascade of 2,000 fallbacks
  costs O(k) (3.3 s, where round-based re-choosing took 13.4 s). With
  growing state, the M · S term remains (150/300/600 merges: 0.55/1.7/7.2 s).
  This is still the specification and conformance oracle, not a production
  algorithm.

## Not decided here / open gates

- Bounded or incremental merge from a trusted checkpoint/snapshot state at
  the merge bases, and an incremental (Merkle/Prolly) checkpoint root. These
  remove the `M · S` term and the default `:max-work` sizing limit. They are
  needed at scale and must equal the reference result. They also need Kotoba
  native/Wasm qualification alongside the existing DAG fixtures.
- Wiring into `kotobase-engine`. `commit-at!` writes kotobase-peer
  `{state, prev, seq}` chain commits with a single `prev`. A `merge-at!` needs
  multi-parent physical commits in kotobase-peer plus a v2 publication. The
  physical root may be rebuilt from the merged Datoms; Prolly gives
  CID-identical trees for equal Datom sets.
- Transport and discovery of sibling heads (gossip, IPNS, DNSLink): these are
  availability only and never truth.
- Merges across schema changes, including histories whose ancestors carry a
  different `:schema-root` (currently rejected). Also transaction functions
  that read state (`:db.fn/cas` intent across branches),
  tuple/composite uniqueness, and `:db.unique/identity` upsert (entity merge)
  across branches.
- Proving the ancestry of a merge's non-path parents to a frontier client
  (currently bounded by `:max-epoch-skip` and left to `verify-merge` over the
  fetched closure).
