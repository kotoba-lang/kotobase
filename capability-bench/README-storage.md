# Object store, encryption, identity, lake — measured

The third benchmark in this module. The first two compared *architectures*;
this one puts the winning one on an object store and asks what actually
changes: requests instead of bytes, hops instead of blocks, and what
client-side encryption, identity scheme choice and analytical scans cost on
top.

```bash
npm install && npm run setup
nbb --classpath "$(nbb setup.cljs --print-classpath)" run-storage.cljs \
    --entities 4000 --txns 50 --defs 300 --cache 1000
```

Results below: 4 000 entities / 20 000 datoms, EAVT+AVET Prolly indexes (both
height 2), 300 definitions for the identity section, 2026-08-06. Raw output in
`results/storage.txt`, EDN in `results/storage.edn`.

## What is real and what is modelled

**Real**: the block layer (DAG-CBOR + CIDv1/sha2-256), the Prolly indexes,
AES-256-GCM through Node's `crypto` (not a stand-in), the semantic lowering
from `kotoba.codebase.semantic-code`, the columnar projection and its zone
maps, and every request/byte/block counter.

**Modelled**: the network. There is no S3 endpoint here. What the profile does
is make every block access a *request* and count it, then apply an RTT you
choose. That is deliberate — request counts and hop chains are properties of
the design and survive being carried to a real bucket, while a latency number
measured against a loopback socket would not.

**Hops are declared, not detected.** A block store cannot see which of its
requests depended on each other, so the hop count for each operation comes
from the algorithm's dependency structure (tree height for a descent, measured
by walking root→leaf). Where a number is a hop count it is labelled as one.

## 1. Object-store profile: requests and hops

| operation | GET | PUT | bytes read | **hops** |
|---|---|---|---|---|
| bulk load (20 000 datoms) | 0 | 151 | — | 1 |
| point read | 2.04 | 0 | 24.9 KB | **2** |
| find by value | 3.6 | 0 | 44.8 KB | **2** |
| range scan | 16 | 0 | 175 KB | **2** |
| transaction | 10.08 | 5.78 | 118 KB | **5** |
| replica sync (50 txns) | 74 | 0 | 1.11 MB | 2 |
| point read, 1 000-block cache | **0.74** | 0 | 10.4 KB | 2 |

The shape of the answer: **request count varies 8× across read operations,
hop count does not vary at all.** Every read is 2 hops because both trees are
height 2 — a range scan reads 16 blocks, but it learns all 16 addresses from
one root, so they are one parallel batch.

That is the number that decides whether this design works on S3:

| RTT | point read | transaction | range scan |
|---|---|---|---|
| | serial / pipelined | serial / pipelined | serial / pipelined |
| 1 ms | 2.0 / 2 ms | 15.9 / 5 ms | 16 / **2** ms |
| 10 ms | 20.4 / 20 ms | 158.6 / 50 ms | 160 / **20** ms |
| 50 ms | 102 / 100 ms | 793 / 250 ms | 800 / **100** ms |
| 100 ms | 204 / 200 ms | 1 586 / 500 ms | 1 600 / **200** ms |

*serial* = one request at a time (`requests × RTT`); *pipelined* = every
independent request issued together (`hops × RTT`). Real clients sit between,
and both bounds are given so nobody has to trust an invented concurrency
factor.

**A client that does not pipeline pays 8× on range scans and 3× on
transactions.** That is the single highest-leverage implementation detail for
an S3-backed deployment, and it is worth more than any index tuning: at 100 ms
RTT it is the difference between a 1.6 s scan and a 200 ms scan.

The block cache is the other lever: 1 000 cached blocks turn 2.04 requests per
point read into 0.74 — a 64% cut, because the upper tree levels are shared by
every descent and are exactly what a small cache holds.

## 2. Client-side encryption (AES-256-GCM)

Size and CPU, on two real block populations:

| block population | mean size | overhead/block | overhead % | encrypt | decrypt |
|---|---|---|---|---|---|
| index blocks | 12 225 B | 28 B | **0.23%** | 0.25 ms | 0.07 ms |
| definition blocks | 1 638 B | 28 B | **1.71%** | 0.04 ms | 0.03 ms |

Throughput 31.6 MB/s in this runtime. The 28 bytes (12-byte nonce + 16-byte
GCM tag) are **per block and fixed**, so the cost of encryption is a direct
function of block granularity — which ties straight back to the granularity
result in `README-semantic.md`. Per-node chunking at 14.9 blocks per definition
would pay 417 B/definition of nonce and tag against 28 B for definition-level
blocks; encryption makes fine chunking more expensive, not less.

### Where you put the encryption decides what you lose

| placement | server can verify | dedup | equality leaks | tree stays canonical |
|---|---|---|---|---|
| none | yes | yes | n/a | yes |
| convergent, ciphertext-addressed | yes | yes | **yes** | yes |
| random nonce, ciphertext-addressed | yes | **no** | no | **no** |
| any mode, plaintext-addressed | **no** | yes | yes | yes |

Measured, on 430 real blocks encrypted twice each:

- convergent: **430 distinct ciphertexts** from two independent encryptions of
  the same 430 blocks — deduplication survives intact.
- random nonce: **860** — a duplicate factor of exactly **2.0**. Encoding the
  same logical node twice yields two addresses, so two replicas building
  identical data disagree about the root. Content addressing stops being
  content addressing; this is not a cost, it is a disqualification.

And what convergent encryption gives away, measured rather than asserted: two
tenants with independent data over the same schema shared **151 byte-identical
block addresses, 35.12% of tenant A's blocks**. A convergent-encryption server
learns that overlap exists and how large it is, without holding a key.

### Encrypted indexes cost more than the cipher

If the server cannot see index values, it cannot serve a value prefix; the
client takes the whole attribute range and filters locally:

| | requests | rows transferred |
|---|---|---|
| plaintext value prefix | 5 | 496 |
| opaque attribute range | 14 | 4 000 |
| **factor** | **2.8×** | **8.06×** |

The cipher costs 0.07 ms per block. The *opacity* costs 8× the bytes. When
encryption looks expensive it is almost never the AES.

## 3. Identity schemes — what leaves a CID unchanged

Percentage of 300 definitions whose CID moves under a change that alters no
behaviour (last row is the control — a real change):

| perturbation | source-text CID | S-expression CID | checked-KIR CID |
|---|---|---|---|
| reformat (whitespace) | **100%** | 0% | 0% |
| rename every local binding | **79.3%** | **79.3%** | **0%** |
| rename every definition + call site | **100%** | **100%** | **0%** |
| change 1 leaf definition | 0.33% | 0.33% | 0.33% |
| change 10% of definitions (hubs) | 10% | 10% | **100%** |

Two things fall out.

**The naive reading of "S-expressions are the database" is not enough.**
Canonicalising the S-expression as data fixes formatting — 100% → 0% — but
still carries every name, so renaming a local invalidates 79% of the corpus
and renaming the definitions invalidates all of it. Only the checked-KIR
identity, which alpha-normalises binders to de Bruijn indices and drops the
definition's own name, is stable: **renaming every definition and every call
site in the corpus changes zero CIDs, and the CID set is preserved exactly.**

**The last row is the price, and it is real.** Because dependencies are CID
links, a change to a hub propagates to every transitive dependent: 10% edited,
100% of identities changed. The text schemes do not propagate — but they also
cannot be used as a build cache key, because they move on reindentation. There
is no scheme here that is stable *and* non-propagating; propagation is what
you buy correctness with.

## 4. Data lake

The same range aggregate over one attribute, two layouts, same block store:

| | requests | rows scanned | chunks skipped | hops | ms |
|---|---|---|---|---|---|
| columnar + zone maps | **2** | **512** | 7 of 8 | 2 | **6.2** |
| AVET Prolly index | 16 | 4 000 | — | 2 | 154 |

Columnar wins on every axis that matters for analytics: **8× fewer requests,
7.8× fewer rows, 25× faster**, because the footer's min/max lets it skip 7 of 8
row groups before reading anything. Building it cost 46 PUTs and 404 KB.

So: **the datom plane does hold up as a lake, but not in its transactional
shape.** Keep the columnar projection as a derived, deletable artifact
rebuilt from the datom plane — the same relationship ADR-2608580000 sets for
the code graph. Note the hop count is 2 for both: on an object store the win
is bandwidth and request count, not latency.

## 5. Security, in numbers

| | measured |
|---|---|
| sha256 of one block | 0.04 ms |
| full CID assembly (hash + multibase) | **0.71 ms** |
| single-byte tamper detected | yes (CID mismatch) |
| cross-tenant equality visible under convergent encryption | 35.12% of blocks |
| block-address equality leak under plaintext addressing | 100% (the address *is* the plaintext hash) |

**The verification cost is 18× the hash, and none of that is cryptography.**
It is base32 multibase encoding implemented in pure ClojureScript. Content
verification is not expensive; this particular CID *string* assembly is, and it
is an optimisation target rather than an argument against verifying.

Integrity itself is not probabilistic — a flipped byte changes the CID, so
detection is a comparison, not a heuristic. What the numbers above bound is
the *cost* of doing it, and the *leakage* that remains after doing it.

## Caveats

- **No S3 was contacted.** Requests and hops are counted; latency is modelled
  from an RTT you supply. Real object stores add per-request variance, TTFB
  distribution and throttling that none of this captures.
- **Range GET is not modelled.** S3 can serve a byte range of one object; a
  packed-block layout could cut the request count further, and this benchmark
  does not measure that.
- **Hops are declared** from the algorithm's structure, not detected.
- **Milliseconds are interpreter-bound** (nbb/SCI). The AES and sha256 figures
  are Node's native implementations and are closer to real, but throughput
  (31.6 MB/s) is still bounded by how this harness feeds them.
- **The key-management plane is not measured** — key derivation, rotation,
  per-tenant hierarchies and revocation are all out of scope. Only cipher work
  and its structural consequences are.
- The tenant-overlap figure depends on how much two tenants genuinely share;
  35% here is a property of this workload, not a constant.
