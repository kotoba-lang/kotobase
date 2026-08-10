# Kotobase storage architecture

## Canonical route rule

For the official `kotobase.net` and `kotoba-lang/kotobase` route, canonical
truth is the caller-selected, signed immutable CID commit DAG. Reads require an
explicit commit CID or canonical frontier; writes return a new commit CID and
concurrent writes branch before deterministic merge. No mutable ref is required
for correctness.

Cloudflare Durable Objects, D1, PostgreSQL, and Rust MUST NOT enter that
canonical read, write, merge, recovery, query, build, or CI path. S3-compatible
R2/B2/generic S3 and IPFS may store immutable CID blocks but are untrusted
availability providers and cannot author or choose graph truth. The canonical
implementation language is Kotoba, with required native and Wasm execution
targets. This rule mirrors network-awai/net-kotobase ADR-2608082300 and is
recorded locally by `docs/adr/2608090000-rust-free-cid-canonical-route.md`.
The checked-in fixed C3 vector is qualified layer by layer on both actual
Kotoba targets. A formal eight-commit signed criss-cross fixture also qualifies
DAG-CBOR parent decoding, shared-ancestor closure, deduplication, and causal
height on both targets. The provider-supplied path embeds neither envelopes nor
a positional CID inventory in the guest: Kotoba starts from a frontier CID,
fetches parents directly by CID, verifies provider offset hints against
DAG-CBOR, and owns closure and causal height in a private `string-index`.

The unchanged guest also traverses a provider-generated twelve-node chain on
native and Wasm. A local execution page is bounded to 128 CIDs / 65536 UTF-8
key bytes. Google-scale topology comes from CID-addressed IPLD page DAGs, not
from widening one process-local map; global multi-page scheduling remains an
explicit open gate. Cycle, forged-offset, and 129th-entry adversarial cases
fail closed on both execution targets.

Transaction replay follows the same rule. A provider may return an immutable
`kotobase.transaction-replay-page.v1` block, but Kotoba verifies its CID, the
digest bytes in every DAG-CBOR transaction link, the canonical public
transaction/quad grammar, every novelty/state checkpoint transition, and the
recomputed state root. One page is bounded to 16 transactions. A scheduler
must accept only the conjunction of the page, count, every per-index CID, atom,
and replay-step result, and the final root; neither `main` nor the final-root
stage is a standalone verifier. The native/Wasm qualification covers the
original three-transaction vector, a provider-generated five-transaction
vector, the actual 16-transaction/16-atom sealed-page boundary, wrong-CID
substitution, malformed atoms, and a forged intermediate checkpoint. Global
transaction page-DAG scheduling remains an explicit open gate.

Everything below describing `IRefStore`, conditional refs, PostgreSQL, D1, or
single-writer IPNS is a compatibility/migration surface, not the formal route.

The legacy storage compatibility surface is `kotobase-storage`:

- immutable CID blocks (`IBlockStore`);
- mutable conditional database refs (`IRefStore`);
- explicit backend capabilities.

The split is intentional:

```
kotobase.core
    |
kotobase-engine        IPLD/CID verification, commit, query, retry
    |
kotobase-storage       immutable blocks + conditional mutable refs
    |
    +-- storage-postgres
    +-- storage-sqlite (embedded/local)
    +-- storage-d1     (Cloudflare D1)
    +-- storage-s3     (S3 and R2)
    `-- storage-ipfs   (IPFS and single-writer IPNS)
```

A PostgreSQL or S3 deployment does not create or require a peer. “Peer” is an
old implementation detail currently hidden behind `kotobase-engine`.
IPLD remains the canonical block format. Ref-capable deployments do not make
their ref service authoritative for the official route.

`kotobase.core` is the public database API. Provider repositories construct a
storage value and pass it to `kotobase/open`; the engine remains provider
neutral. JVM calls are synchronous; ClojureScript/Worker calls return Promises.
The async engine caches fetched blocks during synchronous IPLD traversal and
awaits every immutable write before publishing the mutable ref.

The historical `kotobase.store/IStore` document/stream API remains temporarily
as a compatibility surface for existing murakumo/manimani consumers. New
database backends must not implement or depend on it. It will move to a
dedicated compatibility package after downstream consumers migrate.

Provider repositories:

- `kotobase-storage-postgres`
- `kotobase-storage-sqlite` (embedded/local SQLite)
- `kotobase-storage-d1` (Cloudflare D1; SQLite profile, not PostgreSQL)
- `kotobase-storage-s3` (including Cloudflare R2)
- `kotobase-storage-ipfs` (IPFS blocks + single-writer IPNS refs)

IPLD is the canonical encoding shared by every provider, not a provider.

Blocks are globally keyed by CID, enabling safe deduplication. Mutable refs are
scoped by tenant and database. Provider bytes are untrusted: the engine verifies
their CID before decoding or using them.

Compatibility PostgreSQL and S3/R2 adapters expose conditional refs. IPNS does not offer a
general multi-writer compare-and-set primitive, so the IPFS adapter explicitly
uses a single-writer profile. Multi-writer IPFS deployments must put a
linearizable ref service in front of publication instead of pretending IPNS is
CAS.

`kotobase-server` contains a migration bridge from this contract to its older
`BlockStore`/`HeadStore` ports. Provider code targets only `kotobase-storage`.

The D1 verification deployment composes the storage contract with
`kotoba-lang/authentication`-shaped generic CACAO authentication and
`kotoba-lang/authorization`-shaped deny-by-default decisions. Authentication
establishes the signed DID only; capability and tenant-ref scope are evaluated
separately by authorization. D1 also stores nonce claims and sanitized decision
evidence, never raw credentials.

The D1 provider now binds the same Promise-based engine used by the other
providers. Applications use `kotobase.datomic` with Datomic's EDN transaction
and query grammar (`d/transact`, `d/q`, `d/pull`, `d/datoms`); the Worker carries
that EDN as `application/edn` rather than defining a backend-specific JSON query
language. This is grammar compatibility, not a claim that Kotobase implements
Datomic's numeric basis-t, tempid, transactor, listener, or log APIs. The
authoritative decision and verified boundary are recorded in
`90-docs/adr/2607265000-kotobase-d1-datomic-edn-api.{md,edn}`.
