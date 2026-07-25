(ns kotobase.audit-anchor-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.audit-anchor :as audit]))

(def policy (audit/read-policy))
(defn signed [receipt] (assoc receipt :receipt/signature [:signed (:receipt/id receipt)]))
(defn verify [body signature] (= signature [:signed (:receipt/id body)]))
(def first-receipt (signed {:receipt/id "r1" :receipt/event :pin
                            :receipt/previous-digest audit/genesis}))
(def second-receipt
  (signed {:receipt/id "r2" :receipt/event :revoke
           :receipt/previous-digest (audit/digest first-receipt)}))
(def complete {:receipts [first-receipt second-receipt]
               :anchor {:anchor/external? true :anchor/reconciled? true
                        :anchor/receipt-digest (audit/digest second-receipt)}
               :verify-signature verify})

(deftest signed-receipt-chain-must-match-external-anchor
  (let [result (audit/evaluate policy complete)]
    (is (:audit/anchored? result) (pr-str result))
    (is (= (audit/digest second-receipt) (:audit/head result)))))

(deftest receipt-integrity-fails-closed
  (doseq [[input error]
          [[(assoc-in complete [:receipts 1 :receipt/previous-digest] "forged") :audit/chain]
           [(assoc-in complete [:receipts 1 :receipt/signature] [:forged]) :audit/signature]
           [(assoc-in complete [:anchor :anchor/reconciled?] false) :audit/anchor]]]
    (let [result (audit/evaluate policy input)]
      (is (false? (:audit/anchored? result)))
      (is (contains? (set (:audit/errors result)) error)))))
