(ns kotobase.governed-read-test
  "The successor to the retired disclosure read path.

  Two things are being asserted here at once: that a guarded read through this
  namespace leaves an ExecutionReceipt that reads back out of the commit it
  produced, and that the checks the retired path made are still made. The
  second is the reason this namespace exists at all — deleting a path and its
  checks together would have been a regression wearing a consolidation's
  clothes."
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.causal-commit :as causal]
            [kotobase.causal-trust-test :as trust-fixture]
            [kotobase.core :as core]
            [kotobase.evidence :as evidence]
            [kotobase.execution-identity :as id]
            [kotobase.governed-execution-test :as contract-fixture]
            [kotobase.governed-read :as governed-read]
            [kotobase.storage.memory :as memory]))

(defn- database [backend]
  (core/open {:storage backend
              :encrypt-fn identity
              :decrypt-fn identity
              :blind-fn pr-str
              :visible? (constantly true)}))

(defn- seed! [db]
  (core/commit-at! db nil [["INV-42" "invoice/id" "INV-42"]
                           ["INV-42" "invoice/amount" "5000"]]))

(def served [{:invoice/id "INV-42" :invoice/amount 5000}])

(defn- request-at
  "The composition, with every record bound to the same immutable basis."
  [db basis evaluated & {:as overrides}]
  (let [query (assoc-in trust-fixture/invoice-query [:scope :basis] basis)
        envelope (assoc contract-fixture/request
                        ;; the capability in the trust decision names alice,
                        ;; and it is the envelope that has to be about her
                        :principal "did:key:alice"
                        :base/commit basis
                        :query/digest (id/value-cid query))
        manifest (contract-fixture/resigned
                  (assoc contract-fixture/manifest :data/commit basis))]
    (merge
     {:commit {:database db
               :expected-basis-cid nil
               :receipt-cid-fn (constantly "bafy-execution-receipt")}
      :authority-decision trust-fixture/reader-decision
      :request envelope
      :manifest manifest
      :authority {:now "2026-09-02T14:30:00Z"
                  :epoch 7
                  :consume-nonce! (contract-fixture/nonce-ledger)}
      :value-cid id/value-cid
      :verify contract-fixture/verify
      :plan-digest (fn [compiled] (if compiled "bafy-plan" "bafy-no-plan"))
      :cost (fn [] {:dependent-hops 1 :requests 2 :bytes 512
                    :cache-profile :cold})
      :implementation/build "kotobase@governed-read-test"
      :sign contract-fixture/proof
      :authorize! (fn [q] {:allowed? true
                           :projection (set (:find q))
                           :basis basis
                           :policy-cid "bafy-policy"})
      :schema trust-fixture/classified-schema
      :grant {:granted #{:public :internal} :scopes #{}}
      :query query
      :evaluate! (fn [_ _] (reset! evaluated true) served)}
     overrides)))

(defn- reason [f]
  (let [data (ex-data (try (f) nil (catch clojure.lang.ExceptionInfo e e)))]
    (or (:kotobase.governed-read/reason data)
        (:kotobase.governed-execution/reason data))))

(deftest a-read-returns-rows-only-behind-an-execution-receipt
  (let [backend (memory/memory-store)
        db (database backend)
        evaluated (atom false)
        basis (seed! db)
        result (governed-read/read! (request-at db basis evaluated))
        receipt (:execution/receipt result)
        commit-cid (get-in result [:provenance :receipt-commit-cid])
        proof (causal/receipt-at db commit-cid "bafy-execution-receipt")]
    (is (= served (:rows result)))
    (is (true? @evaluated))
    (testing "and the receipt is the one the rows waited for"
      (is (= [receipt] (:receipt/records proof)))
      (is (= "bafy-execution-receipt" (get-in result [:provenance :receipt-cid])))
      (is (not= basis commit-cid)))
    (testing "which answers every field of the contract"
      ;; the measurement from ADR-evidence-plane, on a record this path wrote:
      ;; the retired disclosure receipt answered one of eight
      (is (= #{} (evidence/missing :governed-execution receipt)))
      (is (= (id/value-cid served) (:result/root receipt))))
    (testing "and no mutable head was published to find it by"
      (is (nil? (core/head db))))))

(deftest the-retired-paths-checks-are-still-made
  (let [db (database (memory/memory-store))
        basis (seed! db)]
    (testing "a challenge is evidence to gather, never permission to disclose"
      (let [evaluated (atom false)]
        (is (= :decision-not-allowed
               (reason #(governed-read/read!
                         (request-at db basis evaluated
                                     :authority-decision
                                     (assoc trust-fixture/reader-decision
                                            :decision/status :challenge))))))
        (is (false? @evaluated))))
    (testing "a capability for another resource is refused before evaluation"
      (let [evaluated (atom false)]
        (is (= :query-resource-mismatch
               (reason #(governed-read/read!
                         (request-at db basis evaluated
                                     :authority-decision
                                     (assoc-in trust-fixture/reader-decision
                                               [:decision/runtime-capability-spec
                                                :capability/resource]
                                               #{"INV-OTHER"}))))))
        (is (false? @evaluated))))
    (testing "so is one for another tenant, or for something other than a read"
      (let [evaluated (atom false)]
        (is (= :query-tenant-mismatch
               (reason #(governed-read/read!
                         (request-at db basis evaluated
                                     :authority-decision
                                     (assoc-in trust-fixture/reader-decision
                                               [:decision/runtime-capability-spec
                                                :capability/tenant]
                                               "other-tenant"))))))
        (is (= :read-capability-required
               (reason #(governed-read/read!
                         (request-at db basis evaluated
                                     :authority-decision
                                     (assoc-in trust-fixture/reader-decision
                                               [:decision/runtime-capability-spec
                                                :capability/action]
                                               :object/write))))))
        (is (false? @evaluated))))
    (testing "and the capability must be about the principal the envelope names"
      ;; stronger than the retired path, which could only compare the
      ;; capability to a receipt template it was handed alongside it
      (let [evaluated (atom false)]
        (is (= :principal-mismatch
               (reason #(governed-read/read!
                         (request-at db basis evaluated
                                     :request
                                     (assoc contract-fixture/request
                                            :principal "did:key:someone-else"
                                            :base/commit basis
                                            :query/digest
                                            (id/value-cid
                                             (assoc-in
                                              trust-fixture/invoice-query
                                              [:scope :basis] basis)))))))) 
        (is (false? @evaluated))))))

(deftest there-is-no-second-sink
  (let [db (database (memory/memory-store))
        basis (seed! db)
        evaluated (atom false)
        request (request-at db basis evaluated)]
    (testing "a caller cannot substitute its own receipt plane"
      ;; choosing a different sink is choosing a different evidence plane, and
      ;; that choice should be a call to `governed-execution`, not an option
      (is (= :invalid-read-options
             (reason #(governed-read/read!
                       (assoc request :commit! (fn [_] {:receipt/durable? true
                                                        :receipt/cid "x"})))))))
    (testing "and nothing is optional"
      (doseq [k (keys request)]
        (is (= :invalid-read-options
               (reason #(governed-read/read! (dissoc request k))))
            (str "removing " k " should refuse the read"))))
    (is (false? @evaluated))))
