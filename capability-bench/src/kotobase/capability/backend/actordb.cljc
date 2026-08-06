(ns kotobase.capability.backend.actordb
  "ActorDB-shaped backend: the database is partitioned into shard actors, each
  the single writer of its own slice, with a database actor holding the map of
  shard roots.

  Structure faithful to the actor-model database pattern (ActorDB's
  actor-per-partition with a KV/SQL engine inside each, restated here on
  content-addressed Prolly Trees so it is comparable with the other two):

  - a shard actor owns EAVT + AVET trees for its entities and is the only
    writer of them, so no lock and no CRDT is needed inside a shard;
  - a transaction touching one shard is a fast path — one actor message and
    one commit;
  - a transaction touching several shards runs two-phase commit, and the
    prepare/decide rounds are counted as messages and as extra blocks;
  - a query by attribute value has no home shard, so it fans out to all of
    them: K messages instead of one descent.

  The trade this measures: sharding cuts per-transaction write amplification
  (each tree is 1/K the size, so a path rewrite is shorter) and buys parallel
  sync, and it pays for that at every cross-shard read and every cross-shard
  transaction."
  (:require [clojure.string :as str]
            [prolly-tree.core :as pt]
            [prolly-tree.diff :as ptd]
            [ipld.core :as ipld]
            [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.workload :as w]
            [kotobase.capability.backend :as be]))

(def base-capabilities
  ;; No :multi-writer-merge — single writer per shard is the whole design.
  ;; No :interest-sync / :analytical-projection.
  #{:immutable-blocks :cid-verified-read :conditional-ref :linearizable-txn
    :cross-shard-txn :covering-index :verifiable-index :range-scan
    :global-snapshot :time-travel :structural-delta-sync})

(defn- shard-of [e k] (mod (hash (w/entity-key e)) k))

(defn- current-map [entries]
  (reduce (fn [acc [key v]]
            (let [[_ a t] (str/split key #"\|")
                  a (keyword a)
                  prev (get acc a)]
              (if (or (nil? prev) (pos? (compare t (:t prev))))
                (assoc acc a {:v (get v "v") :t t})
                acc)))
          {}
          entries))

(defn- plain-map [entries]
  (into {} (for [[a {:keys [v]}] (current-map entries)] [a v])))

(defn- shard-apply
  "What one shard actor does when it is handed its slice of a transaction:
  read the priors it needs to retract, rewrite its two trees, return its new
  root. Pure w.r.t. the store ports, which is what makes it re-executable."
  [put! get-fn shard datoms update?]
  (let [priors (when update?
                 (into {} (for [e (into #{} (map w/datom-e datoms))]
                            [e (plain-map (pt/scan-prefix get-fn (:eavt shard)
                                                          (w/eavt-entity-prefix e)))])))
        eavt-adds (mapv (fn [d] [(w/eavt-key (w/datom-e d) (w/datom-a d) (w/datom-t d))
                                 {"v" (w/datom-v d)}])
                        datoms)
        avet-adds (mapv (fn [d] [(w/avet-key (w/datom-a d) (w/datom-v d) (w/datom-e d))
                                 {"t" (w/datom-t d)}])
                        datoms)
        avet-removals (vec (for [d datoms
                                 :let [old (get-in priors [(w/datom-e d) (w/datom-a d)])]
                                 :when (and (some? old) (not= old (w/datom-v d)))]
                             (w/avet-key (w/datom-a d) old (w/datom-e d))))
        eavt' (pt/insert-many put! get-fn (:eavt shard) eavt-adds)
        avet' (pt/mutate-many put! get-fn (:avet shard) avet-adds avet-removals)]
    {:eavt eavt' :avet avet'}))

(defrecord ActorDbBackend [id label capabilities store put! get-fn st opts]
  be/IDatomBackend
  (-transact! [_ txn]
    (let [k (:shards opts)
          by-shard (group-by #(shard-of (w/datom-e %) k) (:datoms txn))
          participants (sort (keys by-shard))
          two-phase? (> (count participants) 1)
          update? (= :update (:kind txn))]
      ;; client -> coordinator
      (bs/message! store)
      (let [prepared
            (into {}
                  (for [sh participants]
                    (let [_ (bs/message! store)          ; coordinator -> shard: prepare
                          shard (get-in @st [:shards sh] {:eavt nil :avet nil})
                          new-roots (shard-apply put! get-fn shard (get by-shard sh) update?)]
                      (when two-phase?
                        ;; a prepared-but-uncommitted root is durable before
                        ;; the decision — that block is 2PC's real cost
                        (ipld/put-node! put! {"kind" "prepared"
                                              "shard" sh
                                              "t" (:t txn)
                                              "eavt" (ipld/link (:eavt new-roots))
                                              "avet" (ipld/link (:avet new-roots))})
                        (bs/message! store))             ; shard -> coordinator: vote
                      [sh new-roots])))]
        (when two-phase?
          (bs/message! store (count participants)))       ; coordinator -> shards: commit
        (swap! st update :shards merge prepared)
        (let [s @st
              shard-index (vec (for [i (range k)
                                     :let [sh (get (:shards s) i)]]
                                 [(w/pad i 3)
                                  (if sh
                                    {"eavt" (ipld/link (:eavt sh))
                                     "avet" (ipld/link (:avet sh))}
                                    {})]))
              root (cond-> {"kind" "db-root"
                            "t" (:t txn)
                            "shards" (into {} shard-index)}
                     (:root s) (assoc "prev" (ipld/link (:root s)))
                     (:code-cid opts) (assoc "code" (ipld/link (:code-cid opts))))
              root-cid (ipld/put-node! put! root)]
          (bs/message! store)                             ; coordinator -> database actor
          (bs/cas! store [id :db] (:root s) root-cid)
          (swap! st #(-> % (assoc :root root-cid :t (:t txn))
                         (update :history conj {:t (:t txn) :root root-cid
                                                :shards (:shards s)})))
          {:root root-cid :shards-touched (count participants) :two-phase? two-phase?}))))

  (-read-entity [_ e]
    ;; One hop. The shard's tree is 1/K the size, so the descent is shorter
    ;; than the single-root case — this is where sharding pays.
    (let [k (:shards opts)
          sh (get-in @st [:shards (shard-of e k)])]
      (bs/message! store)
      {:via :index
       :shards-queried 1
       :value (plain-map (pt/scan-prefix get-fn (:eavt sh) (w/eavt-entity-prefix e)))}))

  (-find-by-value [_ a v]
    ;; No home shard for a value predicate: every actor has to look.
    (let [k (:shards opts)]
      (bs/message! store k)
      {:via :index-fanout
       :shards-queried k
       :value (vec (mapcat (fn [i]
                             (let [sh (get-in @st [:shards i])]
                               (map (fn [[key _]] (last (str/split key #"\|")))
                                    (pt/scan-prefix get-fn (:avet sh)
                                                    (w/avet-value-prefix a v)))))
                           (range k)))}))

  (-range-scan [_ a lo hi]
    (let [k (:shards opts)
          lo-k (w/->v-key lo) hi-k (w/->v-key hi)]
      (bs/message! store k)
      {:via :index-fanout
       :shards-queried k
       :value (->> (range k)
                   (mapcat (fn [i]
                             (let [sh (get-in @st [:shards i])]
                               (keep (fn [[key _]]
                                       (let [[_ vk ek] (str/split key #"\|")]
                                         (when (and (>= (compare vk lo-k) 0)
                                                    (<= (compare vk hi-k) 0))
                                           [ek vk])))
                                     (pt/scan-prefix get-fn (:avet sh)
                                                     (w/avet-attr-prefix a))))))
                   (sort-by first)
                   vec)}))

  (-snapshot-read [_ t e]
    (let [k (:shards opts)
          h (->> (:history @st) (filter #(<= (:t %) t)) last)]
      (if-not h
        {:status cap/unsupported :capability :time-travel :why "basis before first commit"}
        (let [sh (get-in h [:shards (shard-of e k)])]
          (bs/message! store)
          {:via :db-root-history
           :value (->> (pt/scan-prefix get-fn (:eavt sh) (w/eavt-entity-prefix e))
                       (filter (fn [[key _]]
                                 (<= (compare (last (str/split key #"\|")) (w/pad t 7)) 0)))
                       plain-map)}))))

  (-checkpoint [_] {:shards (:shards @st) :root (:root @st) :t (:t @st)})

  (-sync-from [_ marker _opts]
    ;; Same structural diff as the single-root case, but per shard — so the
    ;; work is partitioned and the critical path is the busiest shard, not the
    ;; sum. Both numbers are reported; only one of them is the latency.
    (let [k (:shards opts)
          s @st
          per-shard (for [i (range k)
                          :let [old (get-in marker [:shards i])
                                new (get-in s [:shards i])]]
                      (let [de (ptd/diff* get-fn (:eavt old) (:eavt new))
                            da (ptd/diff* get-fn (:avet old) (:avet new))]
                        {:shard i
                         :blocks-read (+ (:blocks-read de) (:blocks-read da))
                         :entries (+ (count (:added de)) (count (:changed de))
                                     (count (:added da)) (count (:changed da)))}))]
      {:via :structural-delta-per-shard
       :blocks-read (reduce + (map :blocks-read per-shard))
       :critical-path-blocks (apply max 0 (map :blocks-read per-shard))
       :entries-transferred (reduce + (map :entries per-shard))
       :shards k}))

  (-info [_]
    (let [s @st]
      {:shards (:shards opts)
       :root (:root s)
       :basis (:t s)
       :commits (count (:history s))})))

(defn make
  [{:keys [store fvm? shards] :or {shards 8} :as opts}]
  (let [{:keys [put! get]} (if fvm?
                             (bs/fvm-boundary store)
                             {:put! (bs/put-fn store) :get (bs/get-fn store)})
        code-cid (when fvm?
                   (ipld/put-node! put! {"kind" "actor-code" "name" "actordb-shard-actor"}))]
    (map->ActorDbBackend
     {:id (if fvm? :actordb+fvm :actordb)
      :label (str "ActorDB-shaped " shards "-shard actors over Prolly Trees"
                  (when fvm? " inside an FVM boundary"))
      :capabilities (cap/declare-capabilities
                     :actordb
                     (cond-> base-capabilities fvm? (conj :deterministic-execution)))
      :store store :put! put! :get-fn get
      :opts (assoc opts :code-cid code-cid :shards shards)
      :st (atom {:shards {} :root nil :t 0 :history []})})))
