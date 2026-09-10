# The public side of an execution — measured, 2026-09-10

Harness: `scripts/measure-disclosure-surface.cljs`. Run it from the repo root
with the workspace source dirs on the classpath. It returns a **count** of
failures, and exits 2 — neither 0 nor 1 — when its own control does not hold.

This exists because root ADR-2609108000 makes *Auditability ≠ Publicity* a
hard invariant. That is a claim about a boundary, so the boundary is measured
rather than asserted.

## What holds

**A receipt cannot carry an identity.** `receipt-keys` is exactly nine fields
and `exact-keys!` rejects any extra, so the intersection with
`{:principal :tenant :purpose :query/text :query/ast :credential :token
:authorization}` is empty and cannot be widened by accident. `:principal`
exists — in the **request envelope**, which the receipt cites only by digest.
`forbidden-keys` walks nested values, so a credential hidden inside `:cost`
is refused as `:forbidden-field`.

Measured with the reason literals pinned, not just "it threw":

    receipt + :principal            → :invalid-keys, :unexpected #{:principal}
    receipt + :query/text           → :invalid-keys  (exact-keys! runs first)
    credential nested under :cost   → :forbidden-field

**Two runs of the same query by the same principal do not look alike.** The
envelope nonce reaches `:request/digest`, which reaches the receipt, which
reaches the receipt's own address. Every public identifier differs. The
transparency leaf carries only `:receipt-cid` and `:execution-identity-cid`,
so it does not repeat either. Content addressing is still deterministic for
an identical envelope — that is what makes an auditor able to recompute it.

## The residue, named rather than closed

`:result/root` is a content address of the **answer**. Two principals asking
the same question of the same commit get the same `:result/root`, and a
changed answer changes it. So a party who can read receipts — not the log,
the receipts — can observe *that the answer changed* without reading it.

This is not a defect. It is the property that makes a receipt auditable and
lets two readers agree. A per-reader blinded commitment would remove the
channel and would also remove the agreement. It is recorded here so nobody
has to rediscover it, and so that a decision to blind it is a decision.

## Where the boundary is not

**Six namespaces here are `.clj`, so the runtime this service deploys on has
no source to load:**

    audit_anchor.clj              qualification_host.clj
    operations_qualification.clj  recovery_qualification.clj
    retention_qualification.clj   transparency_log.clj

`kotobase.execution-contract` is `.cljc` — the boundary measured above runs
everywhere. The transparency log, which is the *public* half of the audit
plane, does not. CLAUDE.md ranks JVM last among runtimes, so "the audit plane
exists" and "the audit plane runs where the service runs" are two claims and
only the first is currently supported.

Nothing here proposes moving them. It records that the reach differs from the
design, because a boundary enforced only where the service does not run is
not a boundary the service has.

## Both directions

- **Widen `receipt-keys` with `:principal`** → exit 2. The valid-receipt
  baseline stops validating first, so the harness refuses to report at all.
- **Widen it with `:purpose` and add the field to the fixture** (so the
  control still holds) → exit 1, and the census names it:
  `receipt-keys ∩ identifying = #{:purpose} over 10 receipt fields`.
- **Unmodified** → exit 0, 13 assertions.

The second break matters: without it, section A could be passing because the
control aborts before it, rather than because it measures anything.
