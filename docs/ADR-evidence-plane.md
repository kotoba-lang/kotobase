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
| `:causal-disclosure`, served | decision | 7 |
| `:causal-disclosure`, denied | decision, and that there is no result | 6 |
| `:code-graph-execution` | — | refused |
| `:admission` | — | refused |

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
- The disclosure plane's distance is mostly one field's worth of design: it
  binds an evaluated **row count**, which is a fact about how many rows there
  were and not about which ones, so it cannot answer `:result/root` for a
  served read. That is the same gap `ADR-governed-execution` closed for the
  governed path by handing the receipt sink the rows.
- The adapter is exercised against a record `kotobase.causal-commit/read!`
  actually commits, not only against a fixture of one, so the measured
  distance is to the shape production writes.

## What this does not do

- **No plane was retired.** Every existing receipt is still written by the
  code that wrote it before. What changed is that the distance to the contract
  is measured and refusable, not that anything stopped.
- **Lifting the legacy read path is not a fix for it.** Seven of eight fields
  supplied means the disclosure receipt is not evidence of a version 1
  execution; the fix is to route those reads through
  `kotobase.governed-execution`, which produces the receipt directly. Adapting
  harder would only move the invention from the adapter to its caller.
- **A denial on the legacy path writes nothing at all.** Measured, not
  assumed: the refusal happens at the gate, before the receipt sink, and the
  block store does not grow. `governed-execution` commits a deny receipt
  precisely so that being refused is not the cheapest way to leave no trace.
- **Still no cross-protocol conformance suite.** Nothing yet runs one semantic
  request through two frontends and compares result roots.
