(ns kotobase.capability.bench
  "The harness. Replays one workload into every backend and records, per
  phase, the block-store counters and the wall clock.

  Read the counters first. `puts` / `gets` / bytes / messages are properties of
  the architecture and hold on any runtime; wall time here is measured under
  an interpreter (nbb/SCI) and under an in-memory store with no network, so it
  overstates CBOR decode cost relative to a compiled deployment and understates
  every cost that is normally I/O. It is reported because it is what was
  actually observed, not because it predicts production latency."
  (:require [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.backend :as be]))

(defn now-ms []
  #?(:cljs (js/performance.now)
     :clj (/ (System/nanoTime) 1e6)))

(defn- measure*
  "Run `f`, return [result {:ms .. counters..}]."
  [store f]
  (let [before (bs/stats store)
        t0 (now-ms)
        r (f)
        t1 (now-ms)]
    [r (assoc (bs/delta before (bs/stats store))
              :ms (- t1 t0))]))

(defn- sum-metrics [ms]
  (apply merge-with + (map #(dissoc % :n) ms)))

(defn- per-op [m n]
  (into {} (for [[k v] m] [k (if (zero? n) 0 (/ (double v) n))])))

(defn- round [x] (/ (Math/round (* 1000.0 (double x))) 1000.0))

(defn- roundm [m] (into {} (for [[k v] m] [k (if (number? v) (round v) v)])))

;; ── phases ─────────────────────────────────────────────────────────────────

(defn phase-write
  [backend store txns]
  (let [results (mapv (fn [txn] (second (measure* store #(be/transact! backend txn)))) txns)
        total (sum-metrics results)]
    {:transactions (count txns)
     :total (roundm total)
     :per-transaction (roundm (per-op total (count txns)))}))

(defn- collect-query
  "Run `f` over `args`, summing store deltas and any numeric annotations the
  backend attached to its answer (rows scanned, shards queried, projection
  cost). Unsupported answers are recorded as such, never as zero."
  [store f args]
  (let [runs (mapv (fn [a]
                     (let [[r m] (measure* store #(f a))]
                       {:result r :metrics m}))
                   args)
        unsupported (filterv #(cap/unsupported? (:result %)) runs)
        ok (filterv #(not (cap/unsupported? (:result %))) runs)
        total (sum-metrics (map :metrics ok))
        annotations (reduce (fn [acc {:keys [result]}]
                              (reduce-kv (fn [acc k v]
                                           (cond
                                             (number? v) (update acc k (fnil + 0) v)
                                             (and (map? v) (= k :projection))
                                             (update acc :projection
                                                     #(merge-with + (or % {}) v))
                                             :else acc))
                                         acc
                                         (dissoc result :value)))
                            {}
                            ok)]
    (cond-> {:ops (count args)
             :answered (count ok)
             :via (into (sorted-set) (keep #(get-in % [:result :via]) ok))
             :total (roundm total)
             :per-op (roundm (per-op total (count ok)))
             :result-rows (reduce + 0 (map #(count (get-in % [:result :value] [])) ok))}
      (seq annotations) (assoc :annotations
                               (into {} (for [[k v] annotations]
                                          [k (if (map? v) (roundm v) (round v))])))
      (seq unsupported) (assoc :unsupported
                               {:ops (count unsupported)
                                :capability (get-in (first unsupported) [:result :capability])
                                :why (get-in (first unsupported) [:result :why])}))))

(defn run
  "Run one backend against one workload. `store` must be the store the backend
  was built on, and must be fresh."
  [backend store workload]
  (let [{:keys [load-txns update-txns queries]} workload
        _ (bs/reset-stats! store)
        cold (be/checkpoint backend)
        load (phase-write backend store load-txns)
        warm (be/checkpoint backend)
        steady (phase-write backend store update-txns)
        point (collect-query store #(be/read-entity backend %) (:point queries))
        by-value (collect-query store #(be/find-by-value backend (first %) (second %))
                                (:by-value queries))
        ranges (collect-query store #(be/range-scan backend (first %) (second %) (nth % 2))
                              (:range queries))
        snap-basis (max 1 (- (get-in workload [:meta :total-t]) 50))
        snaps (collect-query store #(be/snapshot-read backend snap-basis %)
                             (:snapshot queries))
        [sync-warm sync-warm-m] (measure* store #(be/sync-from backend warm {}))
        [sync-cold sync-cold-m] (measure* store #(be/sync-from backend cold {}))
        interest (into #{} (take 100 (:point queries)))
        [sync-interest sync-interest-m]
        (measure* store #(be/sync-from backend warm {:interest interest}))]
    {:backend {:id (:id backend) :label (:label backend)
               :capabilities (vec (sort (:capabilities backend)))}
     :info (be/info backend)
     :phases
     {:bulk-load load
      :steady-state-writes steady
      :point-read point
      :find-by-value by-value
      :range-scan ranges
      :snapshot-read (assoc snaps :basis snap-basis)
      :replica-sync-since-load (assoc (roundm sync-warm-m) :report sync-warm)
      :replica-sync-from-empty (assoc (roundm sync-cold-m) :report sync-cold)
      :replica-sync-interest-scoped
      (assoc (roundm sync-interest-m)
             :report sync-interest
             :interest-size (count interest)
             :note "meaningful only where :interest-sync is declared")}
     :storage {:blocks (bs/block-count store)
               :bytes (bs/stored-bytes store)}}))
