(ns kotobase.governed-execution-async-test
  "The Worker half of the governed path.

  In a Worker most of what this composition needs from the host is
  asynchronous: a signature and its verification are `crypto.subtle` calls,
  the nonce ledger is a KV or Durable Object read, and the receipt commit is
  storage. A synchronous-only path would force the host to precompute them,
  which is exactly the shape `execute!` refuses — a cost declared before the
  work and a signature over a receipt that does not exist yet.

  The address function is the exception and is required to be synchronous: a
  canonical value codec is a pure function of the value, and one that needs
  I/O is not one. That it is the *same* function here as on the JVM is the
  point — the CIDs asserted below are the CIDs the JVM suite asserts."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [kotobase.causal-commit :as causal]
            [kotobase.core :as core]
            [kotobase.execution-identity :as id]
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

(defn- query-at [basis]
  (assoc-in fixture/query [:scope :basis] basis))

(defn- options
  "The fixture composition with every host answer deferred to a Promise."
  [db steps basis & {:as overrides}]
  (let [query (query-at basis)]
    (merge
     {:request (assoc fixture/request
                      :base/commit basis
                      :query/digest (id/value-cid query))
      :manifest (fixture/resigned (assoc fixture/manifest :data/commit basis))
      :authority {:now "2026-09-02T14:30:00Z"
                  :epoch 7
                  :consume-nonce! (fresh-nonce)}
      :value-cid id/value-cid
      :verify (fn [request] (later (fixture/verify request)))
      :plan-digest (fn [compiled]
                     (later (if compiled "bafy-plan" "bafy-no-plan")))
      :cost (fn [] (later {:dependent-hops 1 :requests 2 :bytes 512
                           :cache-profile :cold}))
      :implementation/build "kotobase@worker-test"
      :sign (fn [unsigned] (later (fixture/proof unsigned)))
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
      :query query
      :evaluate! (fn [_ _]
                   (swap! steps conj :evaluate)
                   (-> (core/q (core/at-cid db basis) ["INV-42" nil nil])
                       (.then
                        (fn [stored]
                          (let [values (into {} (map (juxt :p :o)) stored)]
                            [{:invoice/id (get values "invoice/id")
                              :invoice/amount
                              (js/parseInt (get values "invoice/amount")
                                           10)}])))))}
     overrides)))

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
             (let [opts (options db steps basis)]
               (-> (governed/execute-async! opts)
                   (.then (fn [result] {:db db :opts opts :result result}))))))
          (.then
           (fn [{:keys [db opts result]}]
             (swap! steps conj :returned)
             (is (= [:model :evaluate :commit :returned] @steps))
             (is (= fixture/served (:rows result)))
             (let [receipt (:execution/receipt result)]
               (is (= :allow (:authority/decision receipt)))
               (testing "the identifiers are addresses, not labels"
                 ;; the same codec the JVM suite uses, over the records this
                 ;; execution actually ran with
                 (is (= (id/value-cid fixture/served) (:result/root receipt)))
                 (is (= (id/value-cid (:request opts))
                        (:request/digest receipt)))
                 (is (= (id/value-cid (:manifest opts))
                        (:execution/manifest receipt))))
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
                    (:kotobase.authority-window/reason (ex-data error))))
             (done)))))))

(deftest worker-signature-verification-is-awaited-not-assumed
  (async done
    (let [steps (atom [])]
      (-> (seeded
           (fn [db basis]
             ;; a Promise resolving to `false` is a failed verification. An
             ;; unawaited Promise object is truthy, and every signature would
             ;; have passed
             (governed/execute-async!
              (options db steps basis :verify (fn [_] (later false))))))
          (.then (fn [_]
                   (is false "an unverified manifest was served")
                   (done)))
          (.catch
           (fn [error]
             (is (= :signature-not-verified
                    (:kotobase.governed-execution/reason (ex-data error))))
             (testing "and nothing ran or was written"
               (is (= [] @steps)))
             (done)))))))
