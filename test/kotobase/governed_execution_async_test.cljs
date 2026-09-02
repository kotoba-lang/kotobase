(ns kotobase.governed-execution-async-test
  "The Worker half of the governed path.

  In a Worker every host answer this composition needs is asynchronous: a
  query digest, a plan digest, a result root and a signature are all
  `crypto.subtle` calls, the nonce ledger is a KV or Durable Object read, and
  the receipt commit is storage. A synchronous-only path would force the host
  to precompute all of them, which is exactly the shape `execute!` refuses —
  a cost declared before the work and a root declared before the rows.

  So this suite makes every one of them a Promise and asserts the ordering
  that matters: nothing is served until the receipt is committed."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [kotobase.causal-commit :as causal]
            [kotobase.core :as core]
            [kotobase.governed-execution :as governed]
            [kotobase.governed-execution-test :as fixture]
            [kotobase.storage.memory :as memory]))

(defn- database []
  (core/open {:storage (memory/memory-store)
              :encrypt-fn #(js/Promise.resolve %)
              :decrypt-fn #(js/Promise.resolve %)
              :blind-fn #(js/Promise.resolve (pr-str %))
              :visible? (constantly true)}))

(defn- later [value] (js/Promise.resolve value))

(defn- fresh-nonce []
  (let [seen (atom #{})]
    (fn [nonce]
      (later (if (contains? @seen nonce)
               false
               (do (swap! seen conj nonce) true))))))

(defn- options
  "The fixture composition with every host answer deferred to a Promise."
  [db steps basis & {:as overrides}]
  (merge
   {:request (assoc fixture/request :base/commit basis)
    :request-digest "bafy-request"
    :manifest (assoc fixture/manifest :data/commit basis)
    :manifest-cid "bafy-manifest"
    :authority {:now "2026-09-02T14:30:00Z"
                :epoch 7
                :consume-nonce! (fresh-nonce)}
    :query-digest (fn [_] (later "bafy-query"))
    :plan-digest (fn [compiled] (later (if compiled "bafy-plan" "bafy-no-plan")))
    :cost (fn [] (later {:dependent-hops 1 :requests 2 :bytes 512
                         :cache-profile :cold}))
    :implementation/build "kotobase@worker-test"
    :result-root (fn [rows] (later (str "bafy-rows-" (count rows))))
    :sign (fn [unsigned]
            (later (str "sig-" (name (:authority/decision unsigned)))))
    :commit! (fn [receipt]
               (swap! steps conj :commit)
               ((causal/execution-receipt-sink
                 db {:expected-basis-cid nil
                     :receipt-cid-fn (constantly "bafy-worker-execution")})
                receipt))
    :authorize! (fn [q]
                  (swap! steps conj :model)
                  (later {:allowed? true
                          :projection (set (:find q))
                          :basis basis
                          :policy-cid "bafy-policy"}))
    :schema fixture/schema
    :grant fixture/grant
    :query (assoc-in fixture/query [:scope :basis] basis)
    :evaluate! (fn [_ _]
                 (swap! steps conj :evaluate)
                 (-> (core/q (core/at-cid db basis) ["INV-42" nil nil])
                     (.then
                      (fn [stored]
                        (let [values (into {} (map (juxt :p :o)) stored)]
                          [{:invoice/id (get values "invoice/id")
                            :invoice/amount
                            (js/parseInt (get values "invoice/amount") 10)}])))))}
   overrides))

(defn- seeded [f]
  (let [db (database)]
    (-> (core/commit-at! db nil [["INV-42" "invoice/id" "INV-42"]
                                 ["INV-42" "invoice/amount" "5000"]])
        (.then #(f db %)))))

(deftest worker-execution-awaits-every-host-answer-before-serving-rows
  (async done
    (let [steps (atom [])]
      (-> (seeded
           (fn [db basis]
             (-> (governed/execute-async! (options db steps basis))
                 (.then (fn [result] {:db db :result result})))))
          (.then
           (fn [{:keys [db result]}]
             (swap! steps conj :returned)
             (is (= [:model :evaluate :commit :returned] @steps))
             (is (= [{:invoice/id "INV-42" :invoice/amount 5000}]
                    (:rows result)))
             (let [receipt (:execution/receipt result)]
               (is (= :allow (:authority/decision receipt)))
               (is (= "bafy-rows-1" (:result/root receipt)))
               (is (= "sig-allow" (:signature receipt)))
               (is (= "bafy-worker-execution"
                      (get-in result [:provenance :receipt-cid])))
               (-> (causal/receipt-at
                    db (get-in result [:provenance :receipt-commit-cid])
                    "bafy-worker-execution")
                   (.then (fn [proof]
                            (is (= [receipt] (:receipt/records proof)))))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "unexpected rejection: " error))
                    (done)))))))

(deftest worker-refusal-is-committed-before-it-is-raised
  (async done
    (let [steps (atom [])]
      (-> (seeded
           (fn [db basis]
             (governed/execute-async!
              (options db steps basis
                       :authorize! (fn [q]
                                     (swap! steps conj :model)
                                     (later {:allowed? false
                                             :projection (set (:find q))
                                             :basis basis
                                             :policy-cid "bafy-policy"}))))))
          (.then (fn [_]
                   (is false "a denied execution returned rows")
                   (done)))
          (.catch
           (fn [error]
             (is (= :authority-denied
                    (:kotobase.governed-execution/reason (ex-data error))))
             (is (= "bafy-worker-execution"
                    (:kotobase.governed-execution/deny-receipt-cid
                     (ex-data error))))
             (testing "the evaluator never ran and the denial is durable"
               (is (= [:model :commit] @steps)))
             (done)))))))

(deftest worker-nonce-ledger-is-awaited-not-assumed
  (async done
    (let [steps (atom [])
          ledger (fresh-nonce)]
      (-> (seeded
           (fn [db basis]
             (let [opts (options db steps basis
                                 :authority {:now "2026-09-02T14:30:00Z"
                                             :epoch 7
                                             :consume-nonce! ledger})]
               ;; a Promise that resolves to `false` is a replay. Before the
               ;; ledger was awaited this resolved to a truthy Promise object
               ;; and every replay was fresh
               (-> (governed/execute-async! opts)
                   (.then (fn [_] (governed/execute-async! opts)))))))
          (.then (fn [_]
                   (is false "a replayed nonce was served")
                   (done)))
          (.catch
           (fn [error]
             (is (= :nonce-replayed
                    (:kotobase.governed-execution/reason (ex-data error))))
             (done)))))))
