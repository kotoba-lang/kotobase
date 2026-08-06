(ns kotobase.capability.backend
  "The single seam every architecture under test implements.

  Deliberately narrow: transact, read one entity, find by attribute value,
  scan a range, read a past state, and catch a replica up. Those six are
  enough to separate the three architectures.

  Note what is *not* guarded here. `find-by-value` and `range-scan` are always
  attempted, because a backend without a covering index can still answer them
  — by materialising everything. Refusing to measure that would hide the very
  cost the comparison exists to show, so instead every answer carries `:via`
  (`:index`, `:index-fanout`, `:projection`, `:full-materialisation`) and the
  capability matrix says which of those is index-backed. Only operations a
  backend genuinely cannot perform return `::cap/unsupported`."
  (:require [kotobase.capability :as cap]))

(defprotocol IDatomBackend
  (-transact! [this txn]
    "Apply one transaction. Returns backend-specific commit info.")
  (-read-entity [this e]
    "Current attribute map of entity `e`.")
  (-find-by-value [this a v]
    "Entities where attribute `a` = `v`.")
  (-range-scan [this a lo hi]
    "[[e v] ...] for `lo` <= v <= `hi` on attribute `a`.")
  (-snapshot-read [this t e]
    "Attribute map of `e` as of basis `t`.")
  (-checkpoint [this]
    "Opaque marker of the current state, for replica sync.")
  (-sync-from [this marker opts]
    "Cost for a replica sitting at `marker` to reach the current state.")
  (-info [this]
    "Backend-specific structural facts worth reporting."))

(defn transact! [b txn] (-transact! b txn))
(defn read-entity [b e] (-read-entity b e))
(defn find-by-value [b a v] (-find-by-value b a v))
(defn range-scan [b a lo hi] (-range-scan b a lo hi))

(defn snapshot-read [b t e]
  ;; Guarded on :global-snapshot, not :time-travel. Every backend here can
  ;; travel in time within one log/stream; what separates them is whether a
  ;; single basis names a consistent state across the *whole* database.
  (cap/guard (:capabilities b) :global-snapshot #(-snapshot-read b t e)))

(defn checkpoint [b] (-checkpoint b))
(defn sync-from [b marker opts] (-sync-from b marker opts))
(defn info [b] (-info b))
