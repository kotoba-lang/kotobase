(ns kotobase.capability.backend.kotobase-prolly
  "Baseline: kotobase as it is actually built.

  One database root over three content-addressed Prolly Tree indexes (EAVT,
  AEVT, AVET) plus an immutable commit DAG, published through a linearizable
  conditional ref. Uses the real `prolly-tree` and `ipld` libraries from this
  workspace, not a model of them.

  What this shape buys: verifiable covering indexes, a global snapshot at any
  basis, and O(changed-subtree) replica sync via `prolly-tree.diff`.
  What it costs: every transaction rewrites a path in *three* trees and then a
  commit block — the write amplification the other two architectures avoid by
  giving something up."
  (:require [clojure.string :as str]
            [prolly-tree.core :as pt]
            [prolly-tree.diff :as ptd]
            [ipld.core :as ipld]
            [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.workload :as w]
            [kotobase.capability.backend :as be]))

(def base-capabilities
  #{:immutable-blocks :cid-verified-read :conditional-ref :linearizable-txn
    :covering-index :verifiable-index :range-scan :global-snapshot
    :time-travel :structural-delta-sync})

(defn- entity-map-from-eavt
  "EAVT entries for one entity -> current attribute map (max basis wins)."
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

(defrecord ProllyBackend [id label capabilities store put! get-fn st opts]
  be/IDatomBackend
  (-transact! [_ txn]
    (let [{:keys [datoms]} txn
          t (:t txn)
          s @st
          ;; A covering value index must be retracted, not just appended to.
          ;; Finding what to retract is a read of the entity's current state —
          ;; the cost of *having* an AVET index, paid on write.
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
          eavt' (pt/insert-many put! get-fn (:eavt s) eavt-adds)
          aevt' (pt/insert-many put! get-fn (:aevt s) aevt-adds)
          avet' (pt/mutate-many put! get-fn (:avet s) avet-adds avet-removals)
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

  (-checkpoint [_] (select-keys @st [:eavt :aevt :avet :commit :t]))

  (-sync-from [_ marker _opts]
    ;; The property this shape exists for: two roots that share subtrees are
    ;; compared by CID, so an unchanged subtree costs one comparison.
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
                                           per-index))}))

  (-info [_]
    (let [s @st]
      {:roots (select-keys s [:eavt :aevt :avet :commit])
       :basis (:t s)
       :commits (count (:history s))})))

(defn make
  [{:keys [store fvm?] :as opts}]
  (let [{:keys [put! get]} (if fvm?
                             (bs/fvm-boundary store)
                             {:put! (bs/put-fn store) :get (bs/get-fn store)})
        code-cid (when fvm?
                   (ipld/put-node! put! {"kind" "actor-code"
                                         "name" "kotobase-prolly-transactor"}))]
    (map->ProllyBackend
     {:id (if fvm? :kotobase-prolly+fvm :kotobase-prolly)
      :label (if fvm?
               "kotobase (3 Prolly indexes + commit DAG) inside an FVM boundary"
               "kotobase (3 Prolly indexes + commit DAG)")
      :capabilities (cap/declare-capabilities
                     :kotobase-prolly
                     (cond-> base-capabilities
                       fvm? (conj :deterministic-execution)))
      :store store :put! put! :get-fn get
      :opts (assoc opts :code-cid code-cid)
      :st (atom {:eavt nil :aevt nil :avet nil :commit nil :t 0 :history []})})))
