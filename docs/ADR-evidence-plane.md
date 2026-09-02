# ADR: One evidence plane, and what does not reach it

Status: accepted

Extends [`ADR-governed-execution.md`](ADR-governed-execution.md), which listed
this as unfinished:

> **The receipt planes are not yet consolidated.** `authorized-query`'s
> disclosure receipt, `causal-commit`'s decision commits, `code-graph`'s
> execution receipts and `admission`'s audit receipts still exist beside this
> one. Until they are adapters onto the v1 contract, this is one more kind of
> receipt, not one instead of several.

## Context

Four kinds of receipt were written before the execution contract existed, and
the contract made five. Saying "the contract is the evidence plane" while four
other planes keep being written is not a claim anyone can check — and the
tempting way to make it true is a mapping function that fills in whatever the
source does not carry, which produces five planes and a lie instead of five
planes.

## Decision

`kotobase.evidence` lifts a source plane's record onto a version 1
`ExecutionReceipt`, under one rule that it cannot break:

**the supplement a caller passes must be exactly the set of fields the source
record does not carry.** Supplying a field the source answers is refused by
name as laundering; omitting one it does not is refused as an incomplete lift.
There is no arity that fills a gap silently, so a lifted receipt is either
genuinely derived or it does not exist — and *the size of the supplement is
the measurement of how far a plane is from the contract*.

The three key sets in `kotobase.execution-contract` become public, and the
adapter derives from them. A key set restated in a second namespace drifts the
moment a version 2 adds a field, and drifts silently, because both halves
still agree with themselves.

### The planes sort into two subjects

| plane | carries | supplement |
|---|---|---|
| `:governed-execution` | all eight | 0 |
| `:code-graph-query` (receipt **with** its execution identity) | decision, result root, plan digest | 5 |
| `:causal-decision` | decision, and — when denied — that there is no result | 7, or 6 |
| `:code-graph-execution` | — | refused |
| `:admission` | — | refused |

A `:challenge` is refused too. It means evidence still to be gathered, and
version 1 has an allow and a deny and no third; flattening it into either
would be the same invention this rule exists to prevent.

The last two are refused rather than mapped. A version 1 `ExecutionReceipt`
binds one *query* execution at one immutable basis, and requires both a plan
digest and a result root:

- the **code-graph execution receipt** records that an artifact was built from
  an admitted code graph under granted effects — a code root, a compiler
  contract, output roots. No query plan, no served result.
- the **admission audit receipt** records that an effect thunk (hydrate,
  execute, pin) was admitted against requested, delegated and local effect
  sets. No query at all.

So the answer to "how many evidence planes are there" is **two subjects, not
one**: query executions, which lift here, and authorised effects, which need
their own versioned record or an explicit version 2 — and version 1 is
deliberately closed, so that is a decision with a cost, not a field addition.

A code-graph query receipt is read **with** the execution identity that binds
it, and the binding is rechecked here. The receipt has the result CID; the
plan CID is on the identity; and it is the `:host-receipt-cids` link that
makes the pair one execution rather than two records mentioning the same
basis. The write path checks this too — a record only its author ever checked
is checked once.

## Consequences

- "One evidence plane" is now a claim with a function behind it and a number
  attached to each plane, held by tests rather than by prose.
- The measurement decided what to do next. A served disclosure answered one
  field of eight; seven supplied is not evidence of an execution, and the fix
  was never a better adapter. **The disclosure read path is now deleted** —
  see the addendum below.
- The adapter is exercised against a record `kotobase.causal-commit/read!`
  actually commits, not only against a fixture of one, so the measured
  distance is to the shape production writes.

## Addendum: the disclosure read path is deleted

`kotobase.causal-commit/read!`, `kotobase.causal-trust/read!`, both
`disclosure-receipt-sink`s, `disclosure-plan`, `disclosure-receipt`,
`disclosure-template-keys` and `require-query-capability!` are gone.
`kotobase.governed-read` replaces them: it commits an ExecutionReceipt, which
answers all eight fields, in place of a disclosure receipt, which answered
one.

Measured before deleting: no caller outside this repository's own tests, among
the repositories checked out in this workspace. That is a bounded measurement,
not a proof — an unchecked-out repository would not appear in it.

**The retired path made two checks the contract does not describe**, and
deleting a path together with its checks would have been a regression wearing
a consolidation's clothes. Both moved into `governed-read`:

- a trust decision that is not an allow is not permission to disclose. *A
  challenge is evidence to gather.*
- the runtime capability being exercised must be a read, for this tenant, over
  exactly these resources.

One got stronger on the way. The capability is now bound to the **signed
envelope's** principal — the record whose digest the receipt names — where the
retired path could only compare it to a receipt template handed in beside it.

`governed-read` also has no `:commit!` option. Choosing a different sink is
choosing a different evidence plane, and that should read as a call to
`kotobase.governed-execution`, not as a parameter.

What remains in `causal-commit` / `causal-trust` is authority decision and
identity persistence — a different subject, with no result to name — which is
why the plane is now called `:causal-decision`.

## What this does not do

- **Two planes remain unretired**, and neither is a query execution:
  `code-graph`'s execution receipt and `admission`'s audit receipt. Covering
  them means a versioned record for authorised effects, which is a decision
  with a cost.
- **The code-graph query receipt plane is not retired either.** It is
  liftable, five fields short, and has its own CID and identity invariants;
  moving it is separate work.
- **Still no cross-protocol conformance suite.** Nothing yet runs one semantic
  request through two frontends and compares result roots.
