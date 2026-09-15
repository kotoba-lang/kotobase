(ns kotobase.graph-store
  "Datomic-centred graph database facade over ITransactionalStore.

  One append-only datom stream is the source of truth. Transactions use the
  store revision CAS, so all datoms in one transaction publish atomically and
  retries are idempotent by tx-id. Database values are immutable projections
  identified by `:basis-t`; query language adapters never own separate data."
  (:require [kotobase.graph-query :as query]
            [kotobase.store :as store]))

(def datom-stream "graph.datoms")

(defn- require! [pred value problem data]
  (when-not (pred value)
    (throw (ex-info (name problem) (assoc data :problem problem))))
  value)

(defn- snapshot [s]
  (require! store/transactional-store? s :graph/transactional-store-required {})
  (store/-snapshot s {:collections [] :streams [datom-stream]}))

(defn- snapshot-datoms [snap]
  (mapv :datom (get-in snap [:streams datom-stream] [])))

(defn- prior-transaction [snap tx-id]
  (let [events (filter #(= tx-id (:graph/tx-id %))
                       (get-in snap [:streams datom-stream] []))]
    (when (seq events)
      {:operations (mapv :graph/operation events)
       :datoms (mapv :datom events)})))

(defn basis-t
  "Latest committed graph transaction number."
  [s]
  (reduce max 0 (map #(nth % 3) (snapshot-datoms (snapshot s)))))

(defn- operation->datom [t operation]
  (let [[op e a v :as full] operation]
    (require! #(= 4 (count %)) full :graph/invalid-operation {:operation full})
    (require! #{:db/add :db/retract} op :graph/invalid-operation {:operation full})
    (require! keyword? a :graph/invalid-attribute {:operation full})
    [e a v t (= op :db/add)]))

(defn transact!
  "Atomically publish Datomic-shaped operations.

  REQUEST is `{:tx-id string :tx-data [[:db/add e a v] ...]}`. A stale
  concurrent writer receives the underlying revision conflict and may retry
  with the same logical tx-id and data. Reusing a committed tx-id is
  idempotent."
  [s {:keys [tx-id tx-data]}]
  (require! #(and (string? %) (seq %)) tx-id :graph/tx-id-required {})
  (require! sequential? tx-data :graph/tx-data-required {:tx-id tx-id})
  (let [tx-data (vec tx-data)
        snap (snapshot s)]
    (if-let [{:keys [operations datoms]} (prior-transaction snap tx-id)]
      (do
        (require! #(= tx-data %) operations :graph/tx-id-conflict {:tx-id tx-id})
        {:tx-id tx-id :basis-t (some-> datoms first (nth 3)) :datoms datoms})
      (let [t (inc (reduce max 0 (map #(nth % 3) (snapshot-datoms snap))))
            datoms (mapv (partial operation->datom t) tx-data)
            receipt (store/-transact
                     s {:tx-id tx-id
                        :expected-revision (:revision snap)
                        :puts [] :deletes []
                        :appends (mapv (fn [operation datom]
                                         [datom-stream
                                          {:datom datom :graph/tx-id tx-id
                                           :graph/operation operation}])
                                       tx-data datoms)})
            committed-datoms (mapv (comp :datom second) (:appends receipt))]
        {:tx-id tx-id :basis-t (or (some-> committed-datoms first (nth 3)) t)
         :datoms committed-datoms}))))

(defn history
  "Complete assertion/retraction history."
  [s]
  (query/history (snapshot-datoms (snapshot s))))

(defn db
  "Immutable database value, current or at inclusive `basis-t`."
  ([s] (let [h (history s)] {:basis-t (reduce max 0 (map #(nth % 3) h))
                              :datoms (query/current h)}))
  ([s t] {:basis-t t :datoms (query/as-of (history s) t)}))

(defn since [s t]
  (query/since (history s) t))

(defn q-db
  "Execute against an explicit immutable database value."
  [db-value request]
  (query/execute (:datoms db-value) request))

(defn q
  "Execute Datalog/structured-SPARQL/structured-Cypher against current db."
  [s request]
  (q-db (db s) request))

(defn transact-git!
  "Project a Git object or ref into the same datom history."
  [s tx-id git-record]
  (transact! s {:tx-id tx-id
                :tx-data (mapv (fn [[e a v]] [:db/add e a v])
                               (query/git-object-datoms git-record))}))
