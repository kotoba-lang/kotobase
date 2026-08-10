# ADR-2608090000: Rust-free CID canonical route

- Status: Accepted
- Date: 2026-08-09
- Authority: network-awai/net-kotobase ADR-2608082300

## Decision

The formal `kotobase.net` and `kotoba-lang/kotobase` read, write, merge,
recovery, and query-cache route is implemented in Kotoba and must execute on
both `kotoba-native` and `kotoba-wasm`. Rust is not required and is forbidden
from the canonical build, runtime, conformance, and CI path. A Rust SDK may
exist only as an explicitly noncanonical compatibility adapter and cannot
satisfy a promotion gate.

Canonical truth is a signed immutable IPLD commit DAG addressed by CID. Durable
Objects, D1, PostgreSQL, mutable heads, and provider listings do not participate
in correctness. R2 is allowed only through the S3-compatible immutable block
boundary, alongside B2, generic S3, IPFS, and client caches.

## Qualification boundary

`kotoba/cid_graph_replay.kotoba` and
`qualification/kotobase/cid_graph_replay_qualification_test.clj` execute the
same fixed-schema frontier ordering and assert/retract semantics on actual
Kotoba native and Wasm targets without a Rust toolchain.
`kotoba/cid_signed_commit.kotoba` and
`qualification/kotobase/cid_crypto_qualification_test.clj` extend that same
fixed vector through all public novelty transaction blocks, CID-linked queue
nodes, `{state, prev, seq}` commits, the exact state-root and merge marker CIDs,
canonical payload/envelope DAG-CBOR, SHA-256 CIDv1/base32, and Ed25519 signing.
All layers pass on both targets. Separate exports preserve the standard bounded
native tender arena rather than weakening it for a monolithic test call.
Transaction blocks are assembled from bounded `s/p/o/op` atoms rather than
precomputed block hex. A fixed scalarized criss-cross ancestry matrix also
passes shuffled frontiers, shared-ancestor deduplication, and effect-free merge
markers on both targets.
`kotoba/cid_dag_traversal.kotoba` additionally decodes the actual `parents`
arrays from two formal signed fixtures. The eight-commit fixture contains two
concurrent branches, two crossing merge commits, and a final merge; it proves
canonical parent order, shared-ancestor closure, deduplication, and causal
height on both targets.
`kotoba/cid_external_dag_traversal.kotoba` additionally executes with no
envelope fixture bytes or positional CID inventory in the guest. Starting from
one frontier CID, Kotoba fetches immutable parent blocks directly by CID and
builds its visited/height indexes with the private native/Wasm `string-index`
value. Offset hints are non-authoritative and are accepted only after Kotoba
verifies the `parents` marker at the claimed byte position. The same unchanged
guest produces closure 8 / height 4 for the formal criss-cross blocks and
closure 12 / height 11 for a provider-generated chain on both native and Wasm.

One execution page is intentionally bounded to 128 CIDs and 65536 aggregate
UTF-8 key bytes. Global scale is a DAG of such CID-addressed IPLD pages; a
provider listing is neither truth nor a correctness dependency. Qualification
also proves that cycles return an invalid height, unverified offset hints
contribute no closure, and a 129th local CID traps on both targets.
Qualification of the global multi-page scheduler remains an open gate.

`kotoba/cid_external_transaction_replay.kotoba` removes transaction fixtures
from the guest. The provider supplies one CID-addressed replay page containing
parallel arrays of up to 16 transaction, novelty, and state-checkpoint DAG-CBOR
CID links plus an expected state root. In separate bounded stages, Kotoba
verifies the page CID, every linked transaction digest, canonical public
transaction blocks and `s/p/o/op` quad maps, and every novelty/chain transition
before binding the final checkpoint to the expected root. Splitting the stages
keeps each native invocation inside the sealed tender arena; acceptance is the
conjunction of every per-index stage, never `main` or the root check alone. The
public guest API deliberately has no aggregate transaction scan. The unchanged
guest replays the canonical three-transaction/six-atom vector, a
provider-generated five-transaction/eight-atom vector, and the actual sealed
page boundary of 16 transactions/16 atoms identically on native and Wasm.
Wrong-CID bytes, malformed quad maps, and a forged intermediate state
checkpoint all fail closed even when the claimed final root is unchanged.

The fixed graph-replay, bounded dynamic CID traversal, and bounded external
transaction-page replay flags may therefore be true. The global multi-page
scheduler and unbounded replay remain false until page-DAG scheduling passes
the same native/Wasm qualification.
Cryptographic host capabilities remain narrow, provider-neutral, and
Rust-free; storage providers never supply graph semantics.
