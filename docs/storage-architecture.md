# Kotobase storage architecture

The primary storage compatibility surface is now `kotobase-storage`:

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
    +-- storage-ipfs   (IPFS and single-writer IPNS)
    `-- storage-git    (bare git repository; update-ref as CAS)
```

A PostgreSQL or S3 deployment does not create or require a peer. “Peer” is an
old implementation detail currently hidden behind `kotobase-engine`.
IPLD remains the canonical block format in every deployment.

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
- `kotobase-storage-git` (bare git repository; no server and no SDK, git itself
  is the storage engine)

IPLD is the canonical encoding shared by every provider, not a provider.

Blocks are globally keyed by CID, enabling safe deduplication. Mutable refs are
scoped by tenant and database. Provider bytes are untrusted: the engine verifies
their CID before decoding or using them.

PostgreSQL and S3/R2 expose linearizable conditional refs. IPNS does not offer a
general multi-writer compare-and-set primitive, so the IPFS adapter explicitly
uses a single-writer profile. Multi-writer IPFS deployments must put a
linearizable ref service in front of publication instead of pretending IPNS is
CAS.

Git needs no such caveat on a single filesystem: `git update-ref <ref> <new>
<old>` is exactly the conditional publish `IRefStore` asks for, implemented by
git with a lock file and fsync. Over NFS the git provider inherits git's own
locking caveats unchanged. Blocks are committed into a tree under
`refs/kotobase/blocks` rather than left as loose objects, because `git gc`
prunes what no ref reaches — writing git objects is not the same thing as using
git as storage. The provider drives git through plumbing and a scratch index
(no worktree, bare repo), so it requires a git binary and does not run inside a
Worker. Its decisions and verified boundary are recorded in
`90-docs/adr/2607262000-kotobase-storage-git-backend.edn`.

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
