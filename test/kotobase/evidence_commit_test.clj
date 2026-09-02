(ns kotobase.evidence-commit-test
  "The lift, against records this repository actually wrote.

  `kotobase.evidence-test` exercises the rules on fixtures. That leaves the
  claim the fixtures cannot make: that a causal disclosure receipt as
  `kotobase.causal-commit/read!` really commits it — not as a test author
  imagines it — carries what the adapter says it carries, and no more. If the
  two ever disagreed, the portable suite would keep measuring a shape nothing
  produces."
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.causal-commit :as causal]
            [kotobase.causal-trust-test :as fixture]
            [kotobase.core :as core]
            [kotobase.evidence :as evidence]
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

(defn- guarded-read!
  "One read through the legacy plane, at BASIS, allowed or not."
  [db basis allowed?]
  (let [template (-> fixture/receipt-template
                     (assoc :causal.receipt/basis-cid basis)
                     (assoc-in [:causal.receipt/decision
                                :decision/trust-basis-cid]
                               basis))]
    (causal/read!
     {:database db
      :disclosure {:template template
                   :expected-basis-cid basis
                   :receipt-cid-fn (constantly "bafy-disclosure")
                   :at "2026-09-02T01:00:01Z"}
      :authorize! (fn [_]
                    {:allowed? allowed?
                     :projection #{:invoice/id :invoice/amount}
                     :basis basis
                     :policy-cid "bafy-authority-policy"})
      :schema fixture/classified-schema
      :grant {:granted #{:public :internal} :scopes #{}}
      :query (assoc-in fixture/invoice-query [:scope :basis] basis)
      :evaluate! (fn [_ _] [{:invoice/id "INV-42" :invoice/amount 5000}])})))

(defn- disclosed-receipt
  "Run one guarded read through the legacy plane and read its receipt back."
  [db]
  (let [result (guarded-read! db (seed! db) true)
        proof (causal/receipt-at db (get-in result [:provenance
                                                    :receipt-commit-cid])
                                 "bafy-disclosure")]
    (first (:receipt/records proof))))

(deftest a-committed-disclosure-carries-what-the-adapter-says-it-carries
  (let [record (disclosed-receipt (database (memory/memory-store)))]
    (testing "this is the real record, not a fixture of one"
      (is (= :disclosed (get-in record [:causal.receipt/outcome
                                        :outcome/status])))
      (is (= 1 (get-in record [:causal.receipt/outcome :outcome/row-count]))))
    (testing "and it answers exactly one field of a version 1 receipt"
      (is (= {:authority/decision :allow}
             (evidence/carried :causal-disclosure record)))
      (is (= 7 (count (evidence/missing :causal-disclosure record)))))
    (testing "so a lift of it is seven eighths supplement"
      ;; the measurement, stated as a number a change would break: a plane
      ;; that records a row count instead of a result root is this far from
      ;; the contract, and no adapter closes the distance
      (let [supplement {:request/digest "bafy-request"
                        :execution/manifest "bafy-manifest"
                        :query/plan-digest "bafy-plan"
                        :result/root "bafy-result"
                        :cost {:dependent-hops 1 :requests 2 :bytes 512
                               :cache-profile :cold}
                        :implementation/build "kotobase@lift"
                        :signature "sig"}
            lifted (evidence/lift :causal-disclosure record supplement)]
        (is (= :allow (:authority/decision lifted)))
        (is (= supplement (dissoc lifted :receipt/version
                                  :authority/decision)))))))

(deftest a-refused-read-writes-no-disclosure-to-lift
  ;; the legacy plane's own gap, found by trying to lift from it: a denial is
  ;; refused at the gate, never reaches the receipt sink, and leaves nothing
  ;; to bring onto the evidence plane. `kotobase.governed-execution` commits a
  ;; deny receipt precisely so that being refused is not the cheapest way to
  ;; leave no trace
  (let [backend (memory/memory-store)
        db (database backend)
        blocks #(count (:blocks (memory/snapshot backend)))
        basis (seed! db)
        before (blocks)
        error (try (guarded-read! db basis false) nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= :denied (:kotobase.query/reason (ex-data error))))
    (testing "and the refusal wrote nothing"
      (is (pos? before))
      (is (= before (blocks))))
    (testing "while the same read allowed does write one"
      ;; the control: the counter moves when something is written, so the
      ;; assertion above is about the denial and not about the counter
      (guarded-read! db basis true)
      (is (> (blocks) before)))))
