# ADR — Stack topology position and the kotobase-* naming convergence

Current architecture direction (2026-10-10): Wasm is one AMU target, target ABI
profiles are separate from neutral contracts, and distribution/consensus
profiles are independent of execution targets. Tier numbers are ownership
labels, not dependency ranks. The coordinated owner guide is
[stack architecture](https://github.com/kotoba-lang/kotoba-lang/blob/main/docs/stack-architecture-target-neutral.ja.md);
[current dependency measurements](https://github.com/kotoba-lang/kotoba-lang/blob/main/lang/stack-dependency-observation.edn)
remain distinct from intended migration. Existing runtime schemas and historical
qualification evidence are not changed by this documentation.

Status: accepted
Date: 2026-07-24
Root authority: `com-junkawasaki/root` ADR-2607241100 (kotoba stack topology
and design cleanup). This ADR is the kotobase-repo mirror; the canonical
topology and the full cross-repo cleanup list live there.

## Position in the stack topology

Updated 2026-10-10 against fetched main manifests. The
[stack architecture](https://github.com/kotoba-lang/kotoba-lang/blob/main/docs/stack-architecture.md) and
[composition contract](https://github.com/kotoba-lang/kotoba-lang/blob/main/lang/stack-architecture.edn) distinguish responsibility, library,
artifact and runtime/service graphs.

```text
kotoba-lang = language contracts (T1)
kotoba      = CLI, libraries and Codebase
amu         = compiler and project linker (T2)
abi         = shared execution contract (T0)
kototama    = Lisp VM contract; engines implement it (T3)
grant       = pure permission decisions (T4); authority owns scope/delegation
aiueos      = operating system (T5); enforces grant's answer
sahai       = reusable placement (T6); murakumo operates its own inference fleet
kotobase    = database and persistent data plane
```

AiueOS is the OS for a modern Kotoba Lisp machine in development; Kototama is
its Lisp VM contract, also implemented by hosted engines. These are
architectural roles, not completion/qualification claims.

Library arrows mean consumer → dependency: Kotoba imports Amu and Kototama;
Kototama imports grant and abi; AiueOS imports grant; grant imports authority
and abi and does not import the OS. Amu imports contracts and multiple
backends. Alias-only dependencies must be labelled separately.
The booted kernel consumes verified compiler artifacts rather than linking
the compiler. Host build/conformance aliases may import compiler libraries.
The database/language boundary describes ownership, not a claim that every
database runtime directly imports the Kotoba CLI.

The July 2026 topology snapshot was corrected on 2026-10-10 after the grant
split and VM-contract separation; its old dependency counts and “AiueOS
decides” wording are not current invariants.

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
