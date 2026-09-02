# ADR: The execution contract has one caller

Status: accepted

Extends [`ADR-execution-contract.md`](ADR-execution-contract.md).

## Context

`kotobase.execution-contract` defined three exact records and validated their
shape and cross-record invariants. Nothing in the repository called it. A
record type nobody constructs is a schema, and a schema is not a security
boundary: the boundary is the place a read cannot get past without producing
the evidence, and that place did not exist.

Two further things were true and are worth naming, because both look like
"already handled" from the outside:

- `validate-request!` proves an expiry *field is present*. It cannot prove the
  request has not expired, that the revocation epoch it names is still
  current, or that its nonce has not been used. Those need state the host
  owns, so nothing in the contract layer could ever answer them.
- `kotobase.guarded/read!` already withheld rows until a receipt sink said it
  had persisted — but the sink was whatever the caller passed, and what it
  wrote had no relationship to the contract records.

## Decision

`kotobase.governed-execution/execute!` is the single entry, with no arity that
skips a step, in this order:

1. **The envelope is bound to the query that will actually run.** A signed
   `RequestEnvelope` naming a different query than the one evaluated is the
   whole attack. `:query/digest`, `:tenant` and `:base/commit` are checked
   against the guest AST before admission, and `:authority/policy` against the
   policy CID the compiler returned — between admission and evaluation, so a
   mismatch is refused before rows exist rather than after.
2. **Runtime authority is decided against host state.** Wall clock versus
   `:expires-at`, the current revocation epoch versus `:authority/epoch`, and
   the nonce against a ledger. A missing ledger is a refusal, not an
   allowance; a ledger answer that is not literally `true` is a replay. An
   expired request does not spend its nonce, so a clock-skewed retry is not
   refused as a replay of itself.
   Instants are RFC-3339 UTC only and are compared through a padded key: the
   naive string compare is wrong exactly where it matters, since `…:00Z` sorts
   after `…:00.5Z`. An instant this module cannot order is refused rather than
   ordered wrongly.
3. **The guarded read runs unchanged** — policy, classified schema, grant.
4. **The receipt is built from what happened, and its identifiers are
   computed rather than accepted.** `:execution/manifest` is the address of
   the manifest, `:request/digest` the address of the envelope, `:result/root`
   the address of the rows that were served, and the envelope's own
   `:query/digest` must be the address of the query. An auditor holding those
   records can re-derive every one, and a record edited in any field stops
   matching the receipt that cites it. Only the physical plan digest and the
   cost remain host-answered, because this layer can see neither a plan nor a
   provider read. `validate-execution!` then cross-checks the receipt against
   the manifest and the request.
   The address function is an argument, not a require — `kotobase.execution-
   identity` names the canonical one, `kotoba.value.codec/value-cid`. What the
   composition checks is that the argument *behaves* like an address:
   deterministic, indifferent to map entry order, and different for different
   values. That refuses a constant, a counter, an order-sensitive hash and a
   codec that is simply absent. It does not refuse one that lies, and says so.
   The receipt is signed, and **the signature is verified before the record is
   written** — a signer whose output does not verify is caught at write time
   rather than by an auditor months later. The manifest's signature is
   verified before the nonce is spent.
5. **The receipt is committed and read back, and only then do rows return.**
   This reuses `kotobase.guarded`'s own rule rather than adding one: the
   contract receipt *is* the sink the guarded path already demanded, so there
   is no ordering left to get wrong and no second receipt plane to keep in
   step. The caller's own `:receipt!` never reaches the guarded path.

A refusal is committed too. "Success and refusal share one evidence plane" is
only true if a denial is as durable as a disclosure — otherwise the cheapest
way to leave no trace is to be refused. Only the policy layers' own refusals,
and only before admission, become deny receipts. Both halves are load bearing:
an evaluator that crashed is not an authority decision, and recording it as
`:deny` would put a policy decision in the evidence plane for something policy
never decided — but the reason key alone does not separate them, because
`kotobase.authorized-query` raises the same key *after* the rows exist for a
result that is not a vector or an acknowledgement it will not accept. Those
are plumbing failures. Admission is entirely before evaluation, so reaching
the evaluator is what tells the two apart.

`kotobase.authorized-query/execute!` now hands its receipt sink the rows as
well as their count. A receipt that binds only a row count is not evidence
about the result; `:result/root` is a fact about the rows, and the sink is the
only place that runs before they are released. Sinks persist the root, not the
rows.

`kotobase.causal-commit/execution-receipt-sink` is the canonical-CID
implementation of the commit: validate the receipt again at the boundary where
it becomes durable, write it at an exact immutable basis, and reread it from
the commit it produced before acknowledging. `durable` there means *read
back*, not *the write call returned*.

## Consequences

- The contract is reachable only by producing evidence, the evidence is about
  this execution rather than about a shape, and every identifier in it can be
  re-derived from the records by someone who was not there.
- The canonical codec is named once, in `kotobase.execution-identity`, and
  `io-ipld` becomes a direct dependency for it — pinned to the sha the
  resolver already selected, so no new diamond appears.
- Expiry, revocation and replay are enforced at runtime by state the host
  owns, and their absence is a refusal.
- `kotobase.core`'s `q`/`query`/`pull`/`datoms`/`at-cid`/`head` remain
  unguarded by design and named as such in `kotobase.guarded`. This ADR does
  not close that door; it builds the door that is worth walking through.

## What this does not do

Named here so the next reader does not mistake the wiring for the finished
thing:

- **The signature scheme is still the host's.** `:verify` is asked a yes/no
  question and must answer literally `true`; which key, which algorithm, and
  whether that key was authorised for this tenant at this epoch are decided
  outside. Binding a key to a principal and an epoch is separate work.
- **A codec that lies is not caught.** The address function is checked for
  behaving like an address, not for being the canonical one; a function that
  special-cases the probe and answers freely elsewhere passes.
  `kotobase.execution-identity/conformant?` is the stronger check and is not
  applied to the caller's argument, because the composition deliberately does
  not require a codec.
- **`:cost` is not measured.** It comes from a meter asked for *after*
  evaluation rather than a value declared before it, so it cannot be attested
  ahead of the work — but it remains the host's number. Deriving dependent
  hops, provider requests and bytes from the pack reads themselves is
  unfinished.
- **The receipt planes are not yet consolidated.** `authorized-query`'s
  disclosure receipt, `causal-commit`'s decision commits, `code-graph`'s
  execution receipts and `admission`'s audit receipts still exist beside this
  one. `kotobase.evidence` now lifts what can be lifted and refuses what
  cannot, so the distance from each plane to this one is measured rather than
  asserted — see [`ADR-evidence-plane.md`](ADR-evidence-plane.md) — but no
  plane has been retired.
- **There is no cross-protocol conformance suite.** The contract's stated
  purpose is that Datalog, SQL, SPARQL, Cypher, GraphQL and Gremlin produce
  comparable evidence for the same semantic request. Nothing yet runs one
  request through more than one frontend and compares the result roots.
