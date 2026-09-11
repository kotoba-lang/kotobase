(ns kotobase.capability.backend.orbit
  "OrbitDB-shaped backend: an append-only Merkle-CRDT oplog plus a local
  materialised index.

  Structure faithful to OrbitDB's core: every write is one hash-linked entry
  whose `next` points at the current heads; convergence is by CRDT merge, not
  by coordination, so there is no compare-and-set anywhere on the write path.
  Merge semantics are the real `kotoba.crdt` LWW-Register + Lamport clock from
  this workspace, one register per `[entity attribute]`.

  The consequential part is what is *not* in the block store: the index.
  OrbitDB answers reads from a local index (LevelDB in production, an
  in-memory map here) that is rebuilt by replaying the log. So reads look
  free — and they are, once you already replayed — while every query that is
  not a point read is a full scan of materialised state, and a fresh replica
  pays for the whole log before it can answer anything at all. Both of those
  are measured here rather than assumed."
  (:require [ipld.core :as ipld]
            [kotoba.crdt.clock :as clock]
            [kotoba.crdt.register :as reg]
            [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.workload :as w]
            [kotobase.capability.backend :as be]))

(def base-capabilities
  ;; No :conditional-ref — heads are gossiped, not compare-and-set.
  ;; No :linearizable-txn — an entry is atomic, but two writers may both
  ;; win and be merged afterwards.
  ;; No :covering-index / :verifiable-index / :range-scan — the index is a
  ;; local side effect of replay, outside the hash graph, so nothing about it
  ;; is provable and nothing about it is ordered.
  ;; :global-snapshot IS here, with a caveat worth stating: a consistent
  ;; database-wide state exists at any *head set*, because every entry is
  ;; reachable from the heads. What does not exist is a numeric basis that
  ;; orders two concurrent writers — replaying to a head set is the honest
  ;; form of the operation, and that is what -snapshot-read does.
  #{:immutable-blocks :cid-verified-read :multi-writer-merge
    :log-replay-sync :time-travel :global-snapshot})

(defn- apply-entry!
  "Fold one entry's payload into the local materialised index — the LevelDB
  side of OrbitDB, modelled with the real LWW-Register."
  [idx payload]
  (reduce (fn [m [e a v t]]
            (update-in m [e a] reg/merge-register
                       (reg/write v {:crdt/counter t :crdt/actor "writer-0"})))
          idx
          payload))

(defn- entry-node [heads clock payload code-cid]
  (cond-> {"kind" "entry"
           "id" (str (:crdt/actor clock))
           "clock" {"counter" (:crdt/counter clock) "actor" (str (:crdt/actor clock))}
           "next" (mapv ipld/link heads)
           "payload" (mapv (fn [[e a v t]] [e (w/attr-str a) v t]) payload)}
    code-cid (assoc "code" (ipld/link code-cid))))

(defn- read-entry [get-fn cid]
  (ipld/decode (get-fn cid)))

(defn- walk-log
  "Follow `next` links back from `heads` until `stop?` says so.
  Returns [entries-newest-first blocks-read]."
  [get-fn heads stop?]
  (loop [frontier (vec heads) seen #{} out [] reads 0]
    (if (empty? frontier)
      [out reads]
      (let [cid (first frontier)]
        (cond
          (seen cid) (recur (subvec frontier 1) seen out reads)
          (stop? cid) (recur (subvec frontier 1) (conj seen cid) out reads)
          :else
          (let [node (read-entry get-fn cid)
                nexts (mapv ipld/link-cid (get node "next"))]
            (recur (into (subvec frontier 1) nexts)
                   (conj seen cid)
                   (conj out [cid node])
                   (inc reads))))))))

(defrecord OrbitBackend [id label capabilities store put! get-fn st opts]
  be/IDatomBackend
  (-transact! [_ txn]
    (let [s @st
          clk (clock/tick (:clock s))
          payload (mapv (fn [d] [(w/datom-e d) (w/datom-a d) (w/datom-v d) (w/datom-t d)])
                        (:datoms txn))
          cid (ipld/put-node! put! (entry-node (:heads s) clk payload (:code-cid opts)))]
      ;; Heads are published, not compare-and-set. That is the primitive
      ;; OrbitDB actually has, and the reason :conditional-ref is absent.
      (bs/set-ref! store [id :heads] [cid])
      (swap! st #(-> % (assoc :heads [cid] :clock clk)
                     (update :entries inc)
                     (update :index apply-entry! payload)))
      {:entry cid :entries (:entries @st)}))

  (-read-entity [_ e]
    ;; Zero block reads on purpose: this is the local index answering, exactly
    ;; as OrbitDB does. The cost was paid at replay time and is charged there.
    {:via :materialised-index
     :block-free? true
     :value (into {} (for [[a r] (get-in @st [:index e])] [a (reg/value r)]))})

  (-find-by-value [_ a v]
    ;; No secondary index exists. OrbitDB's answer is a scan of materialised
    ;; state, and the scan width is the honest cost.
    (let [idx (:index @st)]
      {:via :full-materialisation
       :entries-scanned (count idx)
       :value (vec (for [[e attrs] idx
                         :let [r (get attrs a)]
                         :when (and r (= (reg/value r) v))]
                     (w/entity-key e)))}))

  (-range-scan [_ a lo hi]
    (let [idx (:index @st)]
      {:via :full-materialisation
       :entries-scanned (count idx)
       :value (->> (for [[e attrs] idx
                         :let [r (get attrs a) v (some-> r reg/value)]
                         :when (and (number? v) (>= v lo) (<= v hi))]
                     [(w/entity-key e) (w/->v-key v)])
                   (sort-by first)
                   vec)}))

  (-snapshot-read [_ t e]
    ;; Time travel exists, but it is a replay: walk the log back past every
    ;; entry newer than the basis, then fold forward.
    (let [s @st
          [entries reads] (walk-log get-fn (:heads s) (constantly false))
          keep-entries (filter (fn [[_ node]]
                                 (every? (fn [[_ _ _ et]] (<= et t))
                                         (get node "payload")))
                               entries)
          idx (reduce (fn [m [_ node]]
                        (apply-entry! m (mapv (fn [[pe pa pv pt]]
                                                [pe (w/->attr pa) pv pt])
                                              (get node "payload"))))
                      {}
                      (reverse keep-entries))]
      {:via :log-replay
       :blocks-read reads
       :entries-replayed (count keep-entries)
       :value (into {} (for [[a r] (get idx e)] [a (reg/value r)]))}))

  (-checkpoint [_] {:heads (:heads @st) :entries (:entries @st)})

  (-sync-from [_ marker _opts]
    ;; A replica that already has everything up to `marker` walks back from
    ;; the current heads and stops there — O(entries since marker) blocks,
    ;; then re-folds them into its index.
    (let [stop (set (:heads marker))
          [entries reads] (walk-log get-fn (:heads @st) #(contains? stop %))]
      {:via :log-replay
       :blocks-read reads
       :entries-transferred (count entries)
       :index-refold-entries (reduce + (map (fn [[_ n]] (count (get n "payload")))
                                            entries))}))

  (-info [_]
    (let [s @st]
      {:heads (:heads s)
       :log-entries (:entries s)
       :materialised-entities (count (:index s))
       :note "index lives outside the hash graph; it is not provable and not ordered"})))

(defn make
  [{:keys [store fvm?] :as opts}]
  (let [{:keys [put! get]} (if fvm?
                             (bs/fvm-boundary store)
                             {:put! (bs/put-fn store) :get (bs/get-fn store)})
        code-cid (when fvm?
                   (ipld/put-node! put! {"kind" "actor-code" "name" "orbit-merkle-crdt"}))]
    (map->OrbitBackend
     {:id (if fvm? :orbit+fvm :orbit)
      :label (if fvm?
               "OrbitDB-shaped Merkle-CRDT oplog inside an FVM boundary"
               "OrbitDB-shaped Merkle-CRDT oplog + local index")
      :capabilities (cap/declare-capabilities
                     :orbit
                     (cond-> base-capabilities fvm? (conj :deterministic-execution)))
      :store store :put! put! :get-fn get
      :opts (assoc opts :code-cid code-cid)
      :st (atom {:heads [] :entries 0 :index {} :clock (clock/init "writer-0")})})))
