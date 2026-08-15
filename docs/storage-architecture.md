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
`kotobase.transaction-replay-page.v2` block with `next`, `previous_state`, and
`start_sequence` boundaries, but Kotoba verifies its CID, the digest bytes in
every DAG-CBOR transaction and state link, the canonical public
transaction/quad grammar, every novelty/state checkpoint transition, and the
recomputed state root. Novelty is derived from the CID-verified previous state;
the provider does not supply a parallel novelty array. One page is bounded to
16 transactions. A client scheduler MUST follow only the verified `next` CID,
MUST reject a discontinuous sequence or previous-state boundary, and MUST keep
a visited set and an explicit page budget. It must accept only the conjunction
of the page, boundary, count, every per-index CID, atom, replay-step result, and
the final root; neither `main` nor the final-root stage is a standalone
verifier.

Native/Wasm qualification covers the original three-transaction vector, a
provider-generated five-transaction vector, the actual 16-transaction/16-atom
sealed-page boundary, and a 40-transaction chain split into 16/16/8 pages with
start sequences 0/16/32. It also rejects wrong-CID substitution, malformed
atoms, a forged intermediate checkpoint, successor bytes under a claimed CID,
sequence discontinuity, forged `previous_state`, and self-reference/revisit.
This qualifies the bounded linear CID-linked transaction page-chain scheduler.
Arbitrary branching page-DAG scheduling, unbounded replay, Google-scale
operation, and Neo4j performance remain explicit open evidence gates.

## The physical plane: block → pack → object

A block's **identity** is its CID. Where those bytes actually are is a separate
question, and until 2026-08-16 this design answered it only by default: one
object per CID. Superproject **ADR-2608160100** makes the answer explicit and
gives it a middle layer.

```text
L0a  block    IPLD dag-cbor / raw   identity   = the block's own CID
L0b  pack     CARv2                 location   = (pack CID, file-offset, frame-length)
L0c  object   S3 / R2 / B2 / IPFS   transport  = object key + HTTP Range
```

A pack is itself an immutable object with a raw CIDv1 over its own bytes. It
changes nothing about identity, dedup or verification: a block fetched out of a
pack is still rehashed before it is decoded.

**Why the middle layer exists.** The measured cost of a read here is round
trips, not bytes. Production answers a query in ~2.5 s of which 92 % is
hydration (root ADR-2607310900 訂正3), and 97 % of hydration's *sequential*
term is the novelty cons chain (root ADR-2608021000) — width 1, depth = the
number of unfolded transactions, and structurally un-prefetchable because the
next CID does not exist until the previous block is decoded. Parallelism cannot
touch that shape. Co-location can: if those blocks are in one pack, one range
read returns all of them and the chain stays sequential only in logic.

**Backends declare which they are.** Exactly one of `:block-per-object` or
`:packed-blocks`, with no default — the same discipline as `ref-profiles`,
for the same reason: a guess here fails silently. A backend declaring
`:packed-blocks` must also declare the object plane's `:range-read`. Without
it the only possible implementation is to GET the whole pack for one block,
which reduces round trips and multiplies transfer — a failure that reports
success.

**Packing policy is write-locality**: the blocks one commit produces go in one
pack. A sealed pack is never appended to in place; moving a frame invalidates
both the catalog and the embedded index while every CID still verifies.
Compaction writes a new pack and repoints the catalog.

**The catalog lives on the datom plane.** Two questions have two owners: *which
pack holds this CID* is answered by `:block/pack` / `:block/file-offset` /
`:block/frame-length` datoms, and *where inside that pack* by the CARv2
`MultihashIndexSorted` the pack carries. The first is on the datom plane
because it has to join with commits, tenants and lake objects (ADR-260726:
join reach is exactly one ref). It is a **projection** — deleting it may only
cost speed, because scanning the packs rebuilds it.

Large columnar objects do **not** go in packs. A Parquet or Arrow file stays a
large object read through `:presigned-transfer` and a footer range; packing is
for the small-block regime, and the index costs 40 bytes per block regardless
of how big the block is.

The codec is `kotoba-lang/io-ipld-car` (`ipld.car`, `ipld.car.v2`,
`ipld.car.index`), verified against `@ipld/car` and `go-car` rather than
against itself. Nothing in this repository writes CAR bytes directly.

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
