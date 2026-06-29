# kotobase-clj

[![CI](https://github.com/kotoba-lang/kotobase/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kotobase/actions/workflows/ci.yml)

The **external-storage port** for com-junkawasaki apps — one `IStore` seam that lets an
app run **standalone (OSS)** on a local backend or, when connected to the cloud, persist
to **kotobase.net** (the kotoba PDS) *without the app code changing*. Zero-dependency,
all `.cljc` (JVM / cljs / Cloudflare Worker / kotoba-WASM), with the network
**host-injected** — the store carries no HTTP client.

Same injection pattern as the actors' `MemStore ‖ DatomicStore` and num-clj's `IBackend`:

```
murakumo / manimani (and any app)
        │ reads/writes through
        ▼
kotobase.store/IStore           put · get · list · append · read(since)
        ├── kotobase.local/LocalStore     pure atom (OSS standalone + the ORACLE)
        └── kotobase.kotobase/KotobaseStore   forwards every op to an injected
                 `(xrpc method params)` → kotobase.net XRPC → the kotoba PDS,
                 which itself backs onto external object storage (git-annex/B2, S3)
```

Two shapes of state cover both apps:
- **docs** — keyed last-writer-wins (`put`/`get`/`list`): a node's latest Heartbeat, a
  triage rule, a config fact.
- **streams** — append-only logs with a monotonic `:seq` cursor (`append`/`read since`):
  manimani's Decision Ledger, murakumo's per-node event feed, the kotoba Datom log. The
  Kafka-offset model — robust across devices and merges.

## Usage

```clojure
(require '[kotobase.local :as local] '[kotobase.kotobase :as kb] '[kotobase.store :as st])

;; OSS standalone — pure, in-process
(def s (local/local-store))
(st/-put s "nodes" "asher" {:role :worker})
(st/-append s "ledger" {:decision :reply :id 1})
(st/-read s "ledger" 0)               ;=> [{:decision :reply :id 1 :seq 1}]

;; Cloud — same calls, persisted to kotobase.net (host injects `xrpc`, e.g. fetch)
(def s (kb/kotobase-store (fn [method params] (call-kotobase! method params))))
```

The contract suite asserts `KotobaseStore ≡ LocalStore` over a faithful transport, so a
live kotobase.net backend is correct iff it passes the same checks
(`MemStore ≡ DatomicStore` discipline).

## Consumers

The cloud API workers [cloud-murakumo](https://github.com/com-junkawasaki/cloud-murakumo)
and [cloud-manimani](https://github.com/com-junkawasaki/cloud-manimani) (cljs Cloudflare
Workers) inject a `fetch`-based `xrpc` and serve the app API straight off the
`:kotobase` store; the desktop/CLI apps use `:local`.

```bash
clojure -X:test     # LocalStore + KotobaseStore both satisfy the IStore contract
```
