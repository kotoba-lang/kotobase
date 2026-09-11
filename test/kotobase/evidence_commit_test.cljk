(ns kotobase.evidence-commit-test
  "The lift, against records this repository actually writes.

  `kotobase.evidence-test` exercises the rules on fixtures. That leaves the
  claim the fixtures cannot make: that a causal authority decision receipt as
  `kotobase.causal-commit` really commits it — not as a test author imagines
  it — carries what the adapter says it carries, and no more. If the two ever
  disagreed, the portable suite would keep measuring a shape nothing produces."
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

(defn- decision-receipt
  "Commit one authority decision through the canonical route and read it back."
  [db status]
  (let [basis (core/commit-at! db nil [["INV-42" "invoice/id" "INV-42"]])
        receipt (-> fixture/receipt-template
                    (assoc :causal.receipt/id "bafy-decision"
                           :causal.receipt/basis-cid basis
                           :causal.receipt/outcome {:outcome/status :pending}
                           :causal.receipt/at "2026-09-02T01:00:01Z")
                    (assoc-in [:causal.receipt/decision :decision/trust-basis-cid]
                              basis)
                    (assoc-in [:causal.receipt/decision :decision/status]
                              status))
        ack (causal/persist-decision! db receipt basis)
        proof (causal/receipt-at db (:receipt/commit-cid ack) (:receipt/cid ack))]
    (first (:receipt/records proof))))

(deftest a-committed-decision-carries-what-the-adapter-says-it-carries
  (let [record (decision-receipt (database (memory/memory-store)) :allow)]
    (testing "this is the real record, not a fixture of one"
      (is (= "bafy-decision" (:causal.receipt/id record)))
      (is (= ["claim:reader"] (:causal.receipt/claim-cids record))))
    (testing "and it answers exactly one field of a version 1 receipt"
      (is (= {:authority/decision :allow}
             (evidence/carried :causal-decision record)))
      (is (= 7 (count (evidence/missing :causal-decision record)))))
    (testing "so a lift of it is seven eighths supplement"
      ;; the measurement, stated as a number a change would break: an
      ;; authority decision names no result, and no adapter closes that
      (let [supplement {:request/digest "bafy-request"
                        :execution/manifest "bafy-manifest"
                        :query/plan-digest "bafy-plan"
                        :result/root "bafy-result"
                        :cost {:dependent-hops 1 :requests 2 :bytes 512
                               :cache-profile :cold}
                        :implementation/build "kotobase@lift"
                        :signature "sig"}
            lifted (evidence/lift :causal-decision record supplement)]
        (is (= :allow (:authority/decision lifted)))
        (is (= supplement (dissoc lifted :receipt/version
                                  :authority/decision)))))))

(deftest a-challenge-is-not-a-decision-version-one-can-hold
  ;; a challenge is evidence still to be gathered. Version 1 has an allow and
  ;; a deny and no third, so it is refused rather than flattened into one
  (let [record (decision-receipt (database (memory/memory-store)) :challenge)]
    (is (= :challenge (get-in record [:causal.receipt/decision
                                      :decision/status])))
    (is (= :unreadable-source
           (:kotobase.evidence/reason
            (ex-data (try (evidence/carried :causal-decision record) nil
                          (catch clojure.lang.ExceptionInfo e e))))))))
