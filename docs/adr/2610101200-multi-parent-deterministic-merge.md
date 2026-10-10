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
| `:parents` | 2..16 distinct CID strings, strictly ascending string order |
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
  `kotoba/cid_dag_traversal.kotoba`.
- Fan-in is bounded at 16 because verifying a merge recomputes every parent's
  closure.
- `:merge-policy` is inside the signed value so a future policy change cannot
  reinterpret an old merge.
- Schema-changing merges are out of scope: heads whose schema, model contract
  or admission policy roots differ are rejected (`:incompatible-heads`).

### 2. Merge base via ancestor closure

`closure(c) = {c} ∪ closure(parents(c))`. Every commit's epoch must equal
`1 + max(parent epochs)`, which makes epoch the causal height and guarantees
that `(epoch, CID)` is a linear extension of causality.

- `reduce-heads`: remove duplicates and every head that is in another head's
  closure. If one head remains, the result is `:fast-forward` to it and no
  commit is created. So `merge(A, A) = A` and `merge(M, ancestor) = M`.
- `merge-bases`: the maximal elements of `∩ closure(head)`. A criss-cross has
  more than one. The bases are reported. The Datom rule below is defined over
  events and causality, so it needs no recursive "virtual base".

### 3. Datom merge policy `kotobase.merge/v1`

Every normalized transaction datom in `⋃ closure(head)` is an event stamped
`[epoch commit-cid index]`. Event `x` happens before `y` if `x.commit` is a
proper ancestor of `y.commit`, or if both are in the same commit and
`x.index < y.index`.

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
2. **Retraction vs concurrent assertion.** If a visible Datom's live assertion
   is concurrent with a retraction, and no live assertion has observed that
   retraction since, the assertion wins (add-wins / OR-set). The merge
   transaction re-asserts the Datom (`:op :assert`), so the decision is a
   history event attributed to the merge commit. This rule uses no CID
   tie-break.
3. **Cardinality-one conflict.** If more than one value of `[e attr]` is still
   visible, the value whose newest live assertion has the greatest stamp wins:
   higher causal height first, then higher commit CID, then later tx index.
   Every losing value is retracted in the merge transaction.
4. **`:db/unique` conflict.** This runs after step 3. If one `[attr v]` is held
   by more than one entity, the entity whose earliest live assertion has the
   smallest stamp keeps it: lower causal height first, then lower CID. Each
   other holder's Datom is retracted in the merge transaction.
5. The **resolution transaction** contains only these decisions: retractions
   sorted canonically, then re-assertions sorted canonically. It holds no user
   intent. A writer who wants new facts adds a v1 commit on top of the merge.

Steps 3 and 4 point in opposite directions on purpose. Both reproduce what a
serial executor running commits in `(epoch, CID)` order would have done. For
cardinality-one, the last write replaces earlier ones. For uniqueness, a later
conflicting claim would have failed its uniqueness check, so the first claim
stands. An offline writer therefore cannot take a unique identity value away
from an entity that claimed it earlier.

Conflicts are rejected per Datom, not per transaction. Rejecting a whole
transaction would cascade to every later commit on that branch that depends on
it.

Properties that follow by construction:

- **Commutative:** the result depends only on the reduced head set, and parents
  are canonically sorted, so `merge(A, B)` and `merge(B, A)` give byte-identical
  CIDs.
- **Idempotent:** merging an ancestor is a fast-forward. A merge commit
  happens after every event it resolves, so later merges find those conflicts
  closed and do not record them again.
- **Criss-cross convergent:** two independent merges of the same conflict pick
  the same winner, because the stamp does not depend on which writer merged.
  The final merge resolves only conflicts that are still open.
- **Verifiable:** `verify-merge` recomputes a published v2 commit from its
  parents and rejects it unless the bytes are identical. That includes a merge
  with a dropped loser retraction, a wrong epoch, or a redundant ancestor
  parent.

### 4. Frontier: merge is descent, not equivocation

`accept-head` keeps every v1 rule: rollback, same-epoch `:equivocation`,
contiguous single-parent edges, path length = epoch distance. Merge commits
change only these things:

- A v2 proof edge is valid when the next path root is one of the merge's
  parents and the merge's epoch is strictly greater. A merge edge may skip
  epochs, because a merge sits at `1 + max(parents)` and the path may go
  through a lower parent. The path-length equality check therefore applies
  only to all-v1 paths.
- A different root at the last-seen epoch is still `:equivocation`. A merge
  commit whose parents include the last-seen root is at a higher epoch and
  descends from it, so it is `:advanced`, and the result carries
  `:merge-parents`. A client that saw A and then the equivocating B can accept
  `merge(A, B)` without a coordinator. A client that saw B can do the same.
- Descent proof does not prove that the merge was computed correctly. A client
  that needs that runs `verify-merge` over the fetched closure.

## Consequences

- Two offline writers converge by exchanging blocks. Each computes the same
  merge CID locally, and a mutable ref or CAS is only an optional
  availability hint.
- Every non-causal decision is an auditable Datom in a signed commit.
- The reference `merge` replays the full union closure to genesis and runs in
  O(history). It is the specification and conformance oracle, not a
  production algorithm.

## Not decided here / open gates

- Bounded or incremental merge from a checkpoint state at the merge bases
  (needed at scale; must equal the reference result), and its Kotoba
  native/Wasm qualification alongside the existing DAG fixtures.
- Wiring into `kotobase-engine`. `commit-at!` writes kotobase-peer
  `{state, prev, seq}` chain commits with a single `prev`. A `merge-at!` needs
  multi-parent physical commits in kotobase-peer plus a v2 publication. The
  physical root may be rebuilt from the merged Datoms; Prolly gives CID-identical
  trees for equal Datom sets.
- Transport and discovery of sibling heads (gossip, IPNS, DNSLink): these are
  availability only and never truth.
- Merges across schema changes, transaction functions that read state
  (`:db.fn/cas` intent across branches), and tuple/composite uniqueness.
- Verifying nested merges recursively inside `merge` (currently an earlier v2
  commit's transaction is trusted as signed).
