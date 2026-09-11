(ns kotobase.capability.backend.ceramic
  "Ceramic-shaped backend: one hash-linked event log per entity (a stream),
  plus a columnar projection that cross-stream queries run against.

  Structure faithful to Ceramic's split. A write appends one event to one
  stream and nothing else — no global index is touched, which is why writes
  scale with the number of independent streams rather than with database size.
  A single entity's current state is the fold of its own stream, so reading it
  is bounded by that stream's length, not by the log. And anything that spans
  streams — 'which people live in Kyoto', an aggregate, a range — cannot be
  answered from the DAG at all; it is answered from a materialised projection
  (Ceramic One's `event_states` / `stream_states` pipeline, Parquet + Flight
  SQL in production, column vectors here).

  Two things this makes visible and the other backends do not have:
  the projection is *stale* between a write and a flush, and syncing can be
  scoped to declared interest instead of the whole database."
  (:require [ipld.core :as ipld]
            [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.workload :as w]
            [kotobase.capability.backend :as be]))

(def base-capabilities
  ;; :multi-writer-merge is stream-scoped (one controller per stream, other
  ;; streams proceed independently) — it is not a merge of concurrent writes
  ;; to the same field.
  ;; No :global-snapshot: streams have no common order, which is exactly why
  ;; -snapshot-read below refuses instead of returning a plausible number.
  ;; No :covering-index / :verifiable-index: the projection is derived state
  ;; outside the hash graph and has to be built by materialising every stream.
  ;; No :linearizable-txn: an event is atomic within one stream only.
  #{:immutable-blocks :cid-verified-read :multi-writer-merge :log-replay-sync
    :interest-sync :analytical-projection :time-travel})

(defn- event-node [stream prev data t height code-cid]
  (cond-> {"kind" (if prev "data" "init")
           "stream" (w/entity-key stream)
           "data" (into {} (for [[a v] data] [(w/attr-str a) v]))
           "t" t
           "height" height}
    prev (assoc "prev" (ipld/link prev))
    code-cid (assoc "code" (ipld/link code-cid))))

(defn- materialise-stream
  "Fold one stream from its tip. Returns [state events-read]."
  [get-fn tip]
  (loop [cid tip acc [] reads 0]
    (if-not cid
      [(reduce (fn [m node]
                 (reduce (fn [m [a v]] (assoc m (w/->attr a) v)) m (get node "data")))
               {}
               acc)
       reads]
      (let [node (ipld/decode (get-fn cid))]
        (recur (some-> (get node "prev") ipld/link-cid)
               (cons node acc)
               (inc reads))))))

(defn- flush-projection!
  "Bring the columnar projection up to date: materialise every stale stream,
  then rebuild the column vectors. Returns the cost of doing so — this is the
  pipeline latency that sits between a write and a queryable projection, and
  it is charged to the *first* query after a write, not to the write."
  [st get-fn]
  (let [s @st
        stale (:stale s)
        [cache reads]
        (reduce (fn [[cache r] e]
                  (let [[state n] (materialise-stream get-fn (get-in s [:tips e]))]
                    [(assoc cache e state) (+ r n)]))
                [(:state-cache s) 0]
                stale)
        columns (reduce (fn [cols [e state]]
                          (reduce (fn [cols [a v]]
                                    (update cols a (fnil conj []) [(w/->v-key v) (w/entity-key e)]))
                                  cols
                                  state))
                        {}
                        cache)
        columns (into {} (for [[a rows] columns] [a (vec (sort rows))]))]
    (swap! st #(-> % (assoc :state-cache cache :stale #{} :projection columns)
                   (update :projection-gen inc)))
    {:streams-materialised (count stale)
     :blocks-read reads
     :rows (reduce + (map count (vals columns)))}))

(defrecord CeramicBackend [id label capabilities store put! get-fn st opts]
  be/IDatomBackend
  (-transact! [_ txn]
    (let [by-entity (group-by w/datom-e (:datoms txn))
          s @st]
      (doseq [[e ds] by-entity]
        (let [tip (get-in @st [:tips e])
              h (inc (get-in @st [:heights e] 0))
              cid (ipld/put-node!
                   put!
                   (event-node e tip
                               (into {} (for [d ds] [(w/datom-a d) (w/datom-v d)]))
                               (:t txn) h (:code-cid opts)))]
          ;; Per-stream tip publication. Not a database-wide CAS: two streams
          ;; commit independently and no order between them is established.
          (bs/set-ref! store [id :tip e] cid)
          (swap! st #(-> % (assoc-in [:tips e] cid)
                         (assoc-in [:heights e] h)
                         (update :events inc)
                         (update :stale conj e)))))
      {:streams-touched (count by-entity)
       :stale-streams (count (:stale @st))
       :events (:events @st)
       :prior-events (:events s)}))

  (-read-entity [_ e]
    ;; A stream's own state is cheap and does not depend on database size —
    ;; the strongest thing about this shape.
    (let [s @st]
      (if (and (contains? (:state-cache s) e) (not (contains? (:stale s) e)))
        {:via :cached-stream-state :block-free? true :value (get (:state-cache s) e)}
        (let [[state reads] (materialise-stream get-fn (get-in s [:tips e]))]
          (swap! st #(-> % (assoc-in [:state-cache e] state)
                         (update :stale disj e)))
          {:via :stream-materialisation :blocks-read reads :events-folded reads
           :value state}))))

  (-find-by-value [_ a v]
    ;; Cross-stream, so the DAG cannot answer it. The projection can, and the
    ;; flush cost is reported next to the answer rather than hidden behind it.
    (let [cost (flush-projection! st get-fn)
          vk (w/->v-key v)
          rows (get (:projection @st) a [])]
      {:via :projection
       :projection cost
       :rows-scanned (count rows)
       :value (->> rows (filter (fn [[rv _]] (= rv vk))) (mapv second))}))

  (-range-scan [_ a lo hi]
    (let [cost (flush-projection! st get-fn)
          lo-k (w/->v-key lo) hi-k (w/->v-key hi)
          rows (get (:projection @st) a [])]
      {:via :projection
       :projection cost
       :rows-scanned (count rows)
       :value (->> rows
                   (filter (fn [[rv _]] (and (>= (compare rv lo-k) 0)
                                             (<= (compare rv hi-k) 0))))
                   (mapv (fn [[rv re]] [re rv])))}))

  (-snapshot-read [_ t _e]
    ;; Per-stream time travel exists (fold that stream to height n). A basis
    ;; `t` that spans streams does not: there is no order between them to
    ;; anchor it to. Returning a number here would be the dishonest answer.
    {:status cap/unsupported
     :capability :global-snapshot
     :basis t
     :why "streams carry no common order; only per-stream height/anchor time exists"})

  (-checkpoint [_] {:tips (:tips @st) :events (:events @st)})

  (-sync-from [_ marker {:keys [interest]}]
    ;; Interest-scoped: a replica declares the streams it cares about and pays
    ;; only for those. `interest` nil means everything.
    (let [s @st
          old-tips (:tips marker)
          candidates (if interest
                       (filter #(contains? interest %) (keys (:tips s)))
                       (keys (:tips s)))
          changed (filter #(not= (get old-tips %) (get-in s [:tips %])) candidates)
          [reads events]
          (reduce (fn [[r ev] e]
                    (let [stop (get old-tips e)]
                      (loop [cid (get-in s [:tips e]) r r ev ev]
                        (if (or (nil? cid) (= cid stop))
                          [r ev]
                          (let [node (ipld/decode (get-fn cid))]
                            (recur (some-> (get node "prev") ipld/link-cid)
                                   (inc r) (inc ev)))))))
                  [0 0]
                  changed)]
      {:via (if interest :interest-scoped-sync :log-replay)
       :streams-considered (count candidates)
       :streams-changed (count changed)
       :blocks-read reads
       :entries-transferred events}))

  (-info [_]
    (let [s @st]
      {:streams (count (:tips s))
       :events (:events s)
       :stale-streams (count (:stale s))
       :projection-generation (:projection-gen s)
       :note "cross-stream queries are answered by a derived projection, not by the DAG"})))

(defn make
  [{:keys [store fvm?] :as opts}]
  (let [{:keys [put! get]} (if fvm?
                             (bs/fvm-boundary store)
                             {:put! (bs/put-fn store) :get (bs/get-fn store)})
        code-cid (when fvm?
                   (ipld/put-node! put! {"kind" "actor-code" "name" "ceramic-stream-writer"}))]
    (map->CeramicBackend
     {:id (if fvm? :ceramic+fvm :ceramic)
      :label (if fvm?
               "Ceramic-shaped per-stream event logs inside an FVM boundary"
               "Ceramic-shaped per-stream event logs + columnar projection")
      :capabilities (cap/declare-capabilities
                     :ceramic
                     (cond-> base-capabilities fvm? (conj :deterministic-execution)))
      :store store :put! put! :get-fn get
      :opts (assoc opts :code-cid code-cid)
      :st (atom {:tips {} :heights {} :events 0 :stale #{}
                 :state-cache {} :projection nil :projection-gen 0})})))
