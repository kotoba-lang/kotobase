# ADR-2609060000: IPLD layouts, traversal, and physical packing

- Status: Accepted (architecture contract; implementation remains partial)
- Date: 2026-09-06
- Scope: Cross-repository implementation contract; capability gates remain open.
- Authority: [superproject ADR-2609060000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2609060000-ipld-adl-selector-car-boundaries.edn).

## Context

The existing storage architecture already separates block identity, CARv2 pack
location, and object transport. This proposal extends that separation to ADLs
and selective retrieval. It does not replace ADR-2608090000: signed immutable
commit CIDs/frontiers remain authoritative, and canonical implementation still
requires Kotoba native/Wasm qualification. Clojure compatibility-library tests
do not qualify the canonical route.

## Responsibilities

| Layer | Contract |
| --- | --- |
| DAG-CBOR | Structured nodes with explicit, traversable CID links |
| raw | Opaque bytes: packs, ciphertext, Arrow/Parquet bytes or chunks |
| OrderedMap adapter | Logical lookup plus explicitly specified ordered cursors |
| Prolly / Merkle-LSM | Alternative persistent substrates; possible settled/hot tiers |
| Selector | Structural traversal, partial replication, hydration |
| Datalog planner | Query semantics, index choice, key bounds, joins |
| CARv1 / CARv2 | Block framing / optional indexed physical archive |
| FBL | Optional chunked logical Bytes view for large objects |
| UnixFS | Optional file interoperability/export |
| IPNI | Provider discovery hints by multihash |
| IPNS | Optional mutable publication hint, never canonical truth |

A CID codec describes its actual bytes. Metadata containing a byte string is
still DAG-CBOR when the entire stored block is DAG-CBOR. A concatenation of
encoded nodes or ciphertext is raw, even if its inputs were DAG-CBOR. Changing
the codec changes the CID; never rename existing immutable objects in place.

Large Arrow/Parquet objects keep the existing direct object/range path. An FBL
adapter is optional, versioned work, not a prerequisite or a mandatory rewrite.
Arrow execution should preserve column buffers; generic IPLD row materialization
is not required. A HAMT may accelerate the rebuildable location catalog, but
must not replace its existing datom-plane meaning or become authoritative.

## OrderedMap is an application contract

Prolly and Merkle-LSM may expose the same logical view only after specifying
key encoding/comparator, snapshot visibility, duplicate precedence, tombstones,
and cursor bounds. Standard IPLD Maps have string keys and no ordered-range
API. Composite/binary database keys need a reversible string representation;
lexical ordering of that representation must not silently replace the database
comparator. `seek`, `prefix`, and half-open `range [lower, upper)` are proposed
application extensions, not standard Selector operations. Existing APIs with
inclusive upper bounds require explicit adapters, not a silent behavior change.

An ADL view does not make physical roots representation-independent. Repacking
unchanged blocks preserves their CIDs. LSM compaction or rebuilding a Prolly
tree may change index roots despite equivalent visible rows. A logical identity
independent of those roots needs a separate, specified commitment.

## Traversal and verification

The planner chooses an index and bounds. A substrate cursor visits the needed
blocks; a Selector describes structural traversal where applicable. Standard
`ExploreRange` addresses list positions, not ordered map key ranges. An initial
range artifact may record visited CIDs without claiming to be a standard Selector.

Selected values and loaded blocks differ. An export must include the blocks
needed to replay the traversal (including ADL substrate dependencies), not only
Matcher results. Root CID, Selector bytes/version, supported ADL interpretation,
and explicit resource limits must be available to the verifier. Unknown ADLs,
unsupported Selector forms, missing required blocks, or budget exhaustion must
not report successful completion. Block deduplication must not suppress visiting
the same CID under a different path/Selector state.

CID verification proves bytes; replay proves the requested structural traversal.
Neither alone proves completeness of a database range or Datalog answer. That
requires authenticated index boundaries and trusted index invariants or a
separate proof scheme. Tenant/attribute selection is not access control; shared
physical blocks can contain data outside the logical selection.

## CAR transport profile

Keep `io-ipld-car` as codec owner. Indexed local/object packs should use
MultihashIndexSorted (`0x0401`). Query/replica roots identify their starting
graph; a roots header alone is not evidence that the graph is complete.

Index offsets are relative to the CARv1 payload. Object reads add `data-offset`.
Index entries do not supply frame lengths. Differences between distinct sorted
offsets bound reads; they are exact only with validated complete contiguous
frame coverage. Sparse indexes, omitted identity-CID sections, duplicate offsets,
and payload padding invalidate an unconditional exact-length claim. Parse the
frame varint and CID, check payload bounds, and verify the requested full CID.

Keep write-local packing as the default already specified by storage architecture.
Traversal-order or semantic-page-order packs are optional export/cache policies
whose locality must be measured. CARv2 does not standardize those order flags.
Do not infer database ordering merely from adjacent frames.

HTTP streaming may use CARv1; CARv2 remains useful for persisted random access.
An arbitrary `?selector=` endpoint returning CARv2 is a custom protocol, not
automatically a standard trustless gateway. Negotiate supported forms and
versions explicitly. Wrapping CAR bytes in FBL/UnixFS creates an outer byte DAG,
separate from the graph inside the CAR.

Cache identity must bind snapshot, query semantics/parameters, relevant schema
and evaluator version, and authorization scope where applicable. Physical plans,
Selectors, and packs have separate identities; a Plan CID alone is not a stable
definition of query semantics.

## Current implementation and evidence

| Change | Merged evidence | Validation |
| --- | --- | --- |
| Architecture contract | [kotobase #78](https://github.com/kotoba-lang/kotobase/pull/78) | Documentation review |
| Raw pack/ciphertext CIDs | [projection #2](https://github.com/kotoba-lang/kotobase-projection/pull/2), `73311ba82702626d4474ac341343aeabbbd4f0a5` | CLJS and JVM: each 36 tests / 171 assertions; consumer CLJS: 17 / 77 |
| Retrieval contract | [Ayatori #20](https://github.com/kotoba-lang/ayatori/pull/20) | Documentation review |
| CAR bounds/candidate handling | [Ayatori #21](https://github.com/kotoba-lang/ayatori/pull/21), `2ce321e7fd09eecefcc56a30706d6f206add8a32` | CLJS and JVM pack subset: each 24 tests / 158 assertions |

All reported local assertions passed. These are compatibility-library results,
not canonical Kotoba native/Wasm qualification or a hosted CI receipt.
Merging a library does not deploy a Worker or advance every consumer dependency.
Production rollout remains unverified until the deployment owner records the
resolved dependency closure, built artifact, Worker version, and smoke results.

## Remaining rollout

1. Advance and verify actual deployable consumer dependencies for the merged raw-CID fix; old reads remain supported.
2. Add CAR index parsing resource limits and full `open-pack` header qualification.
3. Implement bounded Selector replay using an explicit supported subset and
   fixtures for missing blocks, shared links, unsupported forms, and limits.
4. Introduce OrderedMap adapters with cross-substrate snapshot/range oracles.
5. Add optional FBL and Arrow-buffer integration with byte-range/lifetime tests.
6. Add versioned ADL signalling after implementations and negotiation exist.

No new ADL, Selector engine, Arrow execution path, or native/Wasm capability is
claimed by this ADR. Measure bytes fetched, request count, peak memory, and
cold/warm latency before promoting a packing or tiering policy.

## References

- [CARv2 specification](https://ipld.io/specs/transport/car/carv2/)
- [Selectors specification](https://ipld.io/specs/selectors/)
- [ADL signalling](https://ipld.io/docs/advanced-data-layouts/signalling/)
- [Existing storage architecture](../storage-architecture.md)
