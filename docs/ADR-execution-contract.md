# ADR: Cross-protocol execution contract v1

Status: accepted

## Context

Kotobase exposes peer query frontends over the same content-addressed datom
plane. The protocols retain their own semantics, but execution evidence must
not vary with a frontend, storage provider, mutable head, or wire codec.
Previously the relevant identifiers existed in separate contracts: data and
index commits, policy snapshots, pack locations, request authority, query
plans, resource measurements, and audit receipts.

## Decision

Every governed execution is represented by three exact, versioned records:

1. `ExecutionManifest` binds the immutable data commit, policy snapshot,
   revocation epoch, pack-location manifest, schema root, parent execution,
   issue time, and signature.
2. `RequestEnvelope` binds the principal, tenant, graph, operation, semantic
   query digest, base commit, policy snapshot, revocation epoch, nonce, and
   expiry.
3. `ExecutionReceipt` binds the canonical request digest and execution
   manifest CID to the plan digest, allow/deny decision, result root, measured
   cost, implementation build, and signature.

The host supplies canonical `manifest-cid` and `request-digest` values. This
module validates relationships but deliberately does not choose DAG-CBOR,
JSON, EDN, a hash function, a signature scheme, or a query frontend.

Validation is fail closed:

- record keys are exact for version 1;
- raw queries, credentials, provider choices, and backend choices are outside
  the signed contract;
- denied executions have no result root and allowed executions require one;
- counters are non-negative integers;
- request base, policy, and epoch must match the manifest, and both externally
  calculated identifiers must match the receipt. The semantic query digest and
  physical plan digest remain distinct because peer frontends and builds can
  execute the same request with different valid plans.

## Consequences

- Datalog, SQL, SPARQL, Cypher, GraphQL, and Gremlin can preserve their own
  language semantics while producing comparable evidence.
- A mutable head selects a manifest; it does not become canonical truth.
- Repacking data changes location evidence without changing the data commit.
- Policy or revocation changes cannot be silently applied to an older request.
- Success and refusal share one evidence plane for conformance, security,
  performance regression, audit, and billing.
- Version 1 is intentionally closed. New fields require a new version and an
  explicit compatibility decision.

The caller that produces these records, and the runtime authority checks this
module cannot make, are in
[`ADR-governed-execution.md`](ADR-governed-execution.md).

## Addendum: a result root is not the identity of an answer

Measured by `kotobase.conformance`, which exists to check the first
consequence above rather than assert it.

`:result/root` is the address of the rows **as served**, and an address is
order sensitive. The admitted query grammar
(`kotobase.authorized-query/query-keys`) has no ordering clause, so the answer
to any query this repository can express is a **multiset** — and two
conformant frontends may serve it in different orders and produce different
result roots. Neither is wrong.

So the root answers *what was served to whom*, which is what an audit needs.
It is **not** the identity of the answer across frontends. Comparing frontends
by it is asking the wrong question, and `kotobase.conformance` computes a
separate canonical result address over the sorted multiset for that purpose,
reporting order divergence rather than hiding it.

This canonicalisation is lossless only while no query can ask for an order. If
an ordering clause is ever admitted, the answer to such a query stops being a
multiset; `conformance/ordered-query?` is a predicate rather than a constant
so that the decision has somewhere to be taken.
