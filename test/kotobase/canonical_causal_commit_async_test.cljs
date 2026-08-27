(ns kotobase.canonical-causal-commit-async-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [identity.adapters.ledger :as identity-ledger]
            [kotobase.causal-commit :as causal]
            [kotobase.causal-trust-test :as fixture]
            [kotobase.core :as core]
            [kotobase.guarded :as guarded]
            [kotobase.storage.memory :as memory]))

(defn- database []
  (core/open {:storage (memory/memory-store)
              :encrypt-fn #(js/Promise.resolve %)
              :decrypt-fn #(js/Promise.resolve %)
              :blind-fn #(js/Promise.resolve (pr-str %))
              :visible? (constantly true)}))

(defn- finish! [done promise]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [error]
                (is false (str "unexpected Promise rejection: " error))
                (done)))))

(deftest worker-adapter-commits-and-rereads-an-exact-branch
  (async done
    (let [db (database)]
      (finish!
       done
       (-> (identity-ledger/transact!
            (causal/identity-ledger db)
            [{:db/id "epoch:new" :identity.epoch/initial-trust 0}]
            {:tx/receipt-cid "bafy-worker-identity"
             :tx/expected-basis-cid nil})
           (.then
            (fn [ack]
              (is (true? (:receipt/durable? ack)))
              (is (= :canonical-cid-dag (:receipt/route ack)))
              (is (string? (:receipt/commit-cid ack)))
              (-> (causal/receipt-at db (:receipt/commit-cid ack)
                                     (:receipt/cid ack))
                  (.then
                   (fn [proof]
                     (is (nil? (:receipt/basis-cid proof)))
                     (is (= 0 (get-in proof [:receipt/records 0
                                             :causal.record/datom
                                             :identity.epoch/initial-trust])))
                     (-> (core/head db)
                         (.then #(is (nil? %))))))))))))))

(defn- protected-read [db steps basis]
  (let [template (-> fixture/receipt-template
                     (assoc :causal.receipt/basis-cid basis)
                     (assoc-in [:causal.receipt/decision
                                :decision/trust-basis-cid]
                               basis))
        query (assoc-in fixture/invoice-query [:scope :basis] basis)]
    (-> (causal/read!
         {:database db
          :disclosure {:template template
                       :expected-basis-cid basis
                       :receipt-cid-fn (constantly "bafy-worker-disclosure")
                       :at "2026-08-27T02:00:00Z"}
          :authorize!
          (fn [_]
            (swap! steps conj :model)
            (js/Promise.resolve
             {:allowed? true
              :projection #{:invoice/id :invoice/amount}
              :basis basis
              :policy-cid "bafy-authority-policy"}))
          :schema fixture/classified-schema
          :grant {:granted #{:public :internal} :scopes #{}}
          :query query
          :evaluate!
          (fn [_ _]
            (swap! steps conj :evaluate)
            (-> (core/q (core/at-cid db basis) ["INV-42" nil nil])
                (.then
                 (fn [stored]
                   (let [values (into {} (map (juxt :p :o)) stored)]
                     [{:invoice/id (get values "invoice/id")
                       :invoice/amount
                       (js/parseInt (get values "invoice/amount") 10)}])))))})
        (.then #(assoc % ::basis basis)))))

(deftest worker-read-awaits-model-evaluator-and-canonical-receipt
  (async done
    (let [db (database)
          steps (atom [])]
      (finish!
       done
       (-> (core/commit-at!
            db nil [["INV-42" "invoice/id" "INV-42"]
                    ["INV-42" "invoice/amount" "5000"]])
           (.then #(protected-read db steps %))
           (.then
            (fn [result]
              (swap! steps conj :returned)
              (is (= [:model :evaluate :returned] @steps))
              (is (= [{:invoice/id "INV-42" :invoice/amount 5000}]
                     (:rows result)))
              (let [basis (::basis result)
                    commit-cid (get-in result
                                       [:provenance :receipt-commit-cid])]
                (is (string? commit-cid))
                (-> (causal/receipt-at db commit-cid "bafy-worker-disclosure")
                    (.then (fn [proof] {:basis basis :proof proof}))))))
           (.then
            (fn [{:keys [basis proof]}]
              (is (= basis (:receipt/basis-cid proof)))
              (is (= :disclosed
                     (get-in proof [:receipt/records 0
                                    :causal.receipt/outcome
                                    :outcome/status]))))))))))

(deftest worker-read-withholds-rows-when-receipt-persistence-rejects
  (async done
    (let [steps (atom [])
          query (assoc-in fixture/invoice-query [:scope :basis] "bafy-basis")]
      (-> (guarded/read-async!
           {:authorize!
            (fn [_]
              (swap! steps conj :model)
              (js/Promise.resolve
               {:allowed? true
                :projection #{:invoice/id :invoice/amount}
                :basis "bafy-basis"
                :policy-cid "bafy-policy"}))
            :schema fixture/classified-schema
            :grant {:granted #{:public :internal} :scopes #{}}
            :query query
            :evaluate! (fn [_ _]
                         (swap! steps conj :evaluate)
                         (js/Promise.resolve [{:invoice/id "INV-42"}]))
            :receipt! (fn [_]
                        (swap! steps conj :receipt)
                        (js/Promise.reject
                         (js/Error. "injected receipt failure")))})
          (.then
           (fn [_]
             (is false "rows returned before a durable receipt")
             (done)))
          (.catch
           (fn [error]
             (is (= "injected receipt failure" (.-message error)))
             (is (= [:model :evaluate :receipt] @steps))
             (done)))))))
