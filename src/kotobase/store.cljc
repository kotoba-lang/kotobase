(ns kotobase.store
  "IStore — the external-storage seam that lets murakumo / manimani (and any other
  com-junkawasaki app) run STANDALONE on a local backend or, when connected to the
  cloud, persist to **kotobase.net** (the kotoba PDS) — without the app code
  changing. Same injection pattern as the actors' Store (`MemStore ‖ DatomicStore`)
  and num-clj's IBackend.

  Two shapes of state cover both apps:
  - **docs** — a keyed, last-writer-wins document space (`put`/`get`/`list`):
    e.g. a fleet node's latest Heartbeat, a triage rule, a config fact.
  - **streams** — append-only event logs with a monotonic `:seq` cursor
    (`append`/`read`): e.g. manimani's Decision Ledger, murakumo's per-node event
    feed, the kotoba Datom log. `read` returns events whose `:seq` exceeds a cursor,
    in order — the Kafka-offset model, which is robust across devices and merges."
  )

(defprotocol IStore
  ;; document space (last-writer-wins) ---------------------------------------
  (-put  [s coll k v] "Idempotent put of value `v` at `[coll k]`; returns v.")
  (-get  [s coll k]   "Read `[coll k]` or nil.")
  (-list [s coll]     "Keys present in `coll` (vector).")
  ;; append-only streams (monotonic :seq cursor) -----------------------------
  (-append [s stream event] "Append `event` to `stream`; returns it stamped with :seq.")
  (-read   [s stream since] "Events in `stream` with :seq > `since` (0/nil = from start), :seq-ordered."))

(defn store? [x] (satisfies? IStore x))
