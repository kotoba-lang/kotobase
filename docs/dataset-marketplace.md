# Dataset marketplace architecture

## Objective

Make kotobase a Datomic-queryable catalog and delivery plane for datasets used
by analytics and LLM training. The commercial model resembles BigQuery public
datasets and marketplace listings, while immutable CID snapshots and explicit
training gates make every published version reproducible.

## Data flow

```text
Common Crawl / producer / enterprise export
  → source receipt + license inventory
  → extraction and normalization
  → deduplication / language / quality / toxicity / PII reports
  → train-validation-test split
  → immutable Parquet/JSONL/WebDataset snapshot (CID)
  → signed dataset manifest
  → kotobase.dataset/publish!
  → Datomic catalog query + entitlement offer
```

The catalog and data plane are separate:

- datoms contain manifest, provenance, score, gate, split and offer facts;
- bulk shards live in content-addressed object storage;
- an entitlement grants access to a particular CID snapshot;
- a new release creates a new CID and new facts; it never mutates old bytes.

## Publisher privacy and access

Each dataset is owned by a publisher DID and has one visibility:

- `private`: invisible to discovery; owner or active entitlement only;
- `unlisted`: not discoverable, but eligible for a direct negotiated offer;
- `public`: catalog metadata and active offers appear in discovery.

Only the owner can change visibility or create an offer, and the offer seller
must equal that owner. A verified external payment receipt creates an active
buyer entitlement. Revocation retracts `:active` and asserts `:revoked` while
preserving the receipt and transaction history. Expiry timestamps are catalog
facts; the hosting layer must schedule the same explicit revocation at expiry.

Private bytes must never be served directly from catalog query output. The
download gateway calls `marketplace/authorized?` and issues a short-lived URL
only after owner/public/active-entitlement authorization.

## Common Crawl policy

Common Crawl is a source corpus, not a blanket training license. Raw WARC data
is catalog-discoverable with `:mixed-web`, but cannot pass the training gate.
A derivative becomes eligible only after producing reviewable evidence for
rights policy, robots/opt-out handling, PII filtering, safety, deduplication,
quality and provenance. Takedown operates on future entitlement and release
construction while immutable historical CIDs remain access-controlled audit
records where legally required.

## Hugging Face interoperability

Export/import adapters should map:

| Hugging Face field | kotobase fact |
|---|---|
| dataset card / description | `:dataset/description` |
| license | `:dataset/license` + license gate |
| features/config | manifest schema CID |
| splits / num_rows | `:dataset/split` / `:dataset/rows` |
| download size | `:dataset/bytes` |
| languages | `:dataset/language` |
| Parquet files | content-addressed shard CIDs |

Import never trusts a card's license string alone. The publisher must assert
rights and attach evidence before `training-eligible?` can become true.

## Query examples

Eligible Japanese datasets scoring at least 80 can be found by composing the
ordinary datom clauses (numeric predicates are delegated to the production
Datalog engine):

```clojure
[:find ?dataset ?title ?score
 :where
 [?dataset :dataset/training-eligible? true]
 [?dataset :dataset/language "ja"]
 [?dataset :dataset/title ?title]
 [?dataset :dataset/llm-suitability ?score]
 [(>= ?score 80)]]
```

The portable reference executor currently owns equality joins only; production
kotobase-engine owns scalar predicates, indexes, pagination and aggregate cost
control.

## Revenue model

- free/open datasets: no access fee; paid hosted query/egress and provenance;
- one-time snapshot: entitlement to an immutable dataset CID;
- subscription: entitlement to future curated releases;
- private exchange: buyer/seller workspace, negotiated license and audit;
- curation service: filtering, scoring, deduplication and format conversion.

Marketplace fees pay for curation, query, distribution and audit—not ownership
of third-party material. Offers cannot override failed legal or privacy gates.

## Score improvement loop

For every release, retain machine-readable reports and update the eight score
dimensions. Optimize the weakest dimension first. A dataset with 100/100 but a
failed hard gate remains ineligible. Commercial metrics are separate: qualified
views, sample downloads, activated workspaces, paid entitlements, repeat buyers
and publisher retention.
