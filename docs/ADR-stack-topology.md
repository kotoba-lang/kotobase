# ADR — Stack topology position and the kotobase-* naming convergence

Status: accepted
Date: 2026-07-24
Root authority: `com-junkawasaki/root` ADR-2607241100 (kotoba stack topology
and design cleanup). This ADR is the kotobase-repo mirror; the canonical
topology and the full cross-repo cleanup list live there.

## Position in the stack topology

```
kotoba    = language + datom model     ("Clojure")
compiler  = AOT compiler               (foundation; depends on nothing in the stack)
kototama  = Wasm tender runtime        (depends on: aiueos)
aiueos    = capability OS / broker     (dependency-minimal)
kotobase  = datom database (THIS REPO) ("Datomic")
```

**Invariant (already honored, now stated):** kotobase depends on kotoba,
never the reverse — kotoba : kotobase = Clojure : Datomic (ADR-2607032500).
Verified against deps.edn 2026-07-24: this repo's runtime deps are
`security` only; the `kotoba` dependency appears solely in the
`:integration` test alias. Keep it that way — the `IStore` seam and
`LocalStore` stay zero-dep portable `.cljc`, and no kotobase repo may ever
appear in `kotoba-lang/kotoba`'s (or the compiler's) dependency closure.

## Decision — converge the datom plane on the `kotobase-*` prefix

This README carries a dedicated **Disambiguation** section
(ADR-2607050900) because `kotobase`, `kotobase-client`, and `kotoba-client`
are close enough in name to be confused despite having zero functional
overlap. A required disclaimer paragraph is the measurable symptom that the
names are not doing their job.

**Decision:** the umbrella's repos converge on the `kotobase-*` prefix with
role-bearing suffixes; the odd one out (`kotoba-client`, the generic
non-CACAO CID block client consumed by `p2p`) gets a name that does not
collide with the tenant-plane client (e.g. a content-graph-role name under
its actual plane). Renames follow the org's GitHub-redirect practice and the
no-`-clj`-suffix rule (root ADR-2607102200 addendum 14). Until each rename
lands, the Disambiguation section stays — the warning is removed only after
the hazard is.

Bottom-up, the umbrella pipeline this prefix covers:
content addressing (`ipld`/`multiformats`/`dag-cbor`) → `prolly-tree` →
`commit-dag` → `quad-store` → `kqe` → `kotobase-engine` → `kotobase-client`
→ `kotobase-cljc-worker` (kotobase.net) — with this repo as the `IStore`
client seam.
