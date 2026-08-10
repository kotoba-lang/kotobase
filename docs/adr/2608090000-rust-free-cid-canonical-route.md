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

The fixed native/Wasm graph-replay and bounded dynamic CID-page traversal flags
may therefore be true. Generalized transaction replay and the global multi-page
scheduler remain false until arbitrary externally supplied transaction atoms
and page DAGs pass the same native/Wasm qualification.
Cryptographic host capabilities remain narrow, provider-neutral, and
Rust-free; storage providers never supply graph semantics.
