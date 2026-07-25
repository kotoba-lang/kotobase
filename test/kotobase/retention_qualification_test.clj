(ns kotobase.retention-qualification-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.retention-qualification :as retention]))

(def policy (retention/read-policy))
(def complete
  {:action :gc/apply
   :gc-plan {:candidates #{"cid-expired"}}
   :dry-run {:dry-run/complete? true :dry-run/candidates #{"cid-expired"}}
   :legal-hold-check {:legal-hold-check/complete? true}
   :approvals [{:approval/principal "did:key:operator-a"}
               {:approval/principal "did:key:operator-b"}]
   :recovery-receipt {:recovery-receipt/restorable? true}
   :audit-receipt {:audit-receipt/durable? true}})

(deftest destructive-retention-change-requires-all-evidence
  (let [result (retention/qualify-change policy complete)]
    (is (:retention/allowed? result) (pr-str result))
    (is (= #{"did:key:operator-a" "did:key:operator-b"}
           (:retention/approvers result)))))

(deftest each-retention-control-fails-closed
  (doseq [[input expected]
          [[(assoc-in complete [:dry-run :dry-run/complete?] false)
            :retention/dry-run]
           [(assoc-in complete [:legal-hold-check :legal-hold-check/complete?] false)
            :retention/legal-hold-check]
           [(assoc complete :approvals [(first (:approvals complete))])
            :retention/two-person-approval]
           [(assoc-in complete [:recovery-receipt :recovery-receipt/restorable?] false)
            :retention/recovery]
           [(assoc-in complete [:audit-receipt :audit-receipt/durable?] false)
            :retention/audit]
           [(assoc-in complete [:dry-run :dry-run/candidates] #{"other"})
            :retention/dry-run-mismatch]]]
    (let [result (retention/qualify-change policy input)]
      (is (false? (:retention/allowed? result)))
      (is (contains? (set (:retention/errors result)) expected)))))
