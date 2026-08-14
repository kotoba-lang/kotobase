(ns kotobase.capability.backend.merkle-bplus
  "Merkle B+ backend: the same three covering indexes + commit DAG as
  kotobase-prolly, with occupancy-split B+ trees instead of Prolly trees.

  Node encoding is identical (leaf/internal DAG-CBOR, tag-42 child links), so
  lookup, prefix scan, and `prolly-tree.diff` apply unchanged. What changes
  is which CIDs move on a write: a full leaf splits at the midpoint, and a
  deletion window re-packs with its successor. Unchanged keys can still land
  in a new block. That is the number this shape exists to put next to Prolly's
  14.32 puts/txn and 273-block 200-txn delta."
  (:require [clojure.string :as str]
            [prolly-tree.core :as pt]
            [prolly-tree.diff :as ptd]
            [kotobase.merkle.bplus :as b+]
            [ipld.core :as ipld]
            [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.workload :as w]
            [kotobase.capability.backend :as be]))

(def base-capabilities
  #{:immutable-blocks :cid-verified-read :conditional-ref :linearizable-txn
    :covering-index :verifiable-index :range-scan :global-snapshot
    :time-travel :structural-delta-sync :interest-sync})

(defn- entity-map-from-eavt
  [entries]
  (reduce (fn [acc [k v]]
            (let [[_ a t] (str/split k #"\|")
                  a (keyword a)
                  prev (get acc a)]
              (if (or (nil? prev) (pos? (compare t (:t prev))))
                (assoc acc a {:v (get v "v") :t t})
                acc)))
          {}
          entries))

(defn- current-map [entries]
  (into {} (for [[a {:keys [v]}] (entity-map-from-eavt entries)] [a v])))

(defrecord MerkleBPlusBackend [id label capabilities store put! get-fn st opts]
  be/IDatomBackend
  (-transact! [_ txn]
    (let [{:keys [datoms]} txn
          t (:t txn)
          s @st
          touched (into (sorted-set) (map w/datom-e datoms))
          priors (when (= :update (:kind txn))
                   (into {} (for [e touched]
                              [e (current-map
                                  (pt/scan-prefix get-fn (:eavt s)
                                                  (w/eavt-entity-prefix e)))])))
          eavt-adds (mapv (fn [d] [(w/eavt-key (w/datom-e d) (w/datom-a d) (w/datom-t d))
                                   {"v" (w/datom-v d)}])
                          datoms)
          aevt-adds (mapv (fn [d] [(w/aevt-key (w/datom-a d) (w/datom-e d) (w/datom-t d))
                                   {"v" (w/datom-v d)}])
                          datoms)
          avet-adds (mapv (fn [d] [(w/avet-key (w/datom-a d) (w/datom-v d) (w/datom-e d))
                                   {"t" (w/datom-t d)}])
                          datoms)
          avet-removals (vec (for [d datoms
                                   :let [e (w/datom-e d) a (w/datom-a d)
                                         old (get-in priors [e a])]
                                   :when (and (some? old) (not= old (w/datom-v d)))]
                               (w/avet-key a old e)))
          eavt' (b+/insert-many put! get-fn (:eavt s) eavt-adds)
          aevt' (b+/insert-many put! get-fn (:aevt s) aevt-adds)
          avet' (b+/mutate-many put! get-fn (:avet s) avet-adds avet-removals)
          commit (cond-> {"kind" "commit"
                          "t" t
                          "eavt" (ipld/link eavt')
                          "aevt" (ipld/link aevt')
                          "avet" (ipld/link avet')}
                   (:commit s) (assoc "prev" (ipld/link (:commit s)))
                   (:code-cid opts) (assoc "code" (ipld/link (:code-cid opts))))
          commit-cid (ipld/put-node! put! commit)]
      (bs/cas! store [id :db] (:commit s) commit-cid)
      (swap! st #(-> % (assoc :eavt eavt' :aevt aevt' :avet avet' :commit commit-cid :t t)
                     (update :entity-heads into (map (fn [e] [e t]) touched))
                     (update :history conj {:t t :commit commit-cid
                                            :eavt eavt' :aevt aevt' :avet avet'})))
      {:commit commit-cid :t t}))

  (-read-entity [_ e]
    {:via :index
     :value (current-map (pt/scan-prefix get-fn (:eavt @st) (w/eavt-entity-prefix e)))})

  (-find-by-value [_ a v]
    {:via :index
     :value (mapv (fn [[k _]] (last (str/split k #"\|")))
                  (pt/scan-prefix get-fn (:avet @st) (w/avet-value-prefix a v)))})

  (-range-scan [_ a lo hi]
    (let [lo-k (w/->v-key lo) hi-k (w/->v-key hi)]
      {:via :index
       :value (->> (pt/scan-prefix get-fn (:avet @st) (w/avet-attr-prefix a))
                   (keep (fn [[k _]]
                           (let [[_ vk ek] (str/split k #"\|")]
                             (when (and (>= (compare vk lo-k) 0)
                                        (<= (compare vk hi-k) 0))
                               [ek vk]))))
                   vec)}))

  (-snapshot-read [_ t e]
    (let [h (->> (:history @st) (filter #(<= (:t %) t)) last)]
      (if-not h
        {:status cap/unsupported :capability :time-travel :why "basis before first commit"}
        {:via :commit-dag
         :value (->> (pt/scan-prefix get-fn (:eavt h) (w/eavt-entity-prefix e))
                     (filter (fn [[k _]]
                               (<= (compare (last (str/split k #"\|"))
                                            (w/pad t 7))
                                   0)))
                     current-map)})))

  (-checkpoint [_] (select-keys @st [:eavt :aevt :avet :commit :t :entity-heads]))

  (-sync-from [_ marker {:keys [interest]}]
    (if (seq interest)
      (let [s @st
            old (:entity-heads marker)
            changed (filterv #(not= (get old %) (get (:entity-heads s) %))
                             interest)
            reads (atom 0)
            g (fn [cid] (swap! reads inc) (get-fn cid))
            entries (reduce + 0
                            (map (fn [e]
                                   (count (pt/scan-prefix g (:eavt s)
                                                          (w/eavt-entity-prefix e))))
                                 changed))]
        {:via :interest-scoped-sync
         :entities-considered (count interest)
         :entities-changed (count changed)
         :blocks-read @reads
         :entries-transferred entries})
      (let [s @st
            per-index (for [k [:eavt :aevt :avet]]
                        (let [d (ptd/diff* get-fn (get marker k) (get s k))]
                          [k {:blocks-read (:blocks-read d)
                              :added (count (:added d))
                              :removed (count (:removed d))
                              :changed (count (:changed d))}]))]
        {:via :structural-delta
         :per-index (into {} per-index)
         :blocks-read (reduce + (map (comp :blocks-read second) per-index))
         :entries-transferred (reduce + (map (fn [[_ v]] (+ (:added v) (:changed v)))
                                             per-index))})))

  (-info [_]
    (let [s @st]
      {:roots (select-keys s [:eavt :aevt :avet :commit])
       :basis (:t s)
       :commits (count (:history s))
       :height {:eavt (b+/height get-fn (:eavt s))
                :aevt (b+/height get-fn (:aevt s))
                :avet (b+/height get-fn (:avet s))}
       :max-occupancy b+/max-occupancy})))

(defn make
  [{:keys [store fvm?] :as opts}]
  (let [{:keys [put! get]} (if fvm?
                             (bs/fvm-boundary store)
                             {:put! (bs/put-fn store) :get (bs/get-fn store)})
        code-cid (when fvm?
                   (ipld/put-node! put! {"kind" "actor-code"
                                         "name" "merkle-bplus-transactor"}))]
    (map->MerkleBPlusBackend
     {:id (if fvm? :merkle-bplus+fvm :merkle-bplus)
      :label (if fvm?
               "Merkle B+ (occupancy-split, 3 indexes + commit DAG) inside an FVM boundary"
               "Merkle B+ (occupancy-split, 3 indexes + commit DAG)")
      :capabilities (cap/declare-capabilities
                     :merkle-bplus
                     (cond-> base-capabilities
                       fvm? (conj :deterministic-execution)))
      :store store :put! put! :get-fn get
      :opts (assoc opts :code-cid code-cid)
      :st (atom {:eavt nil :aevt nil :avet nil :commit nil :t 0
                 :history [] :entity-heads {}})})))
