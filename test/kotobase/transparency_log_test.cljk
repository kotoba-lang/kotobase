(ns kotobase.transparency-log-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.transparency-log :as log]))

(def policy {:required-witnesses #{:operations :independent-auditor}
             :classes {:security {:minimum-ms 100}
                       :ephemeral {:minimum-ms 10}}})
(def key-schedule {"key-2" {:epoch 2 :valid-from 1000 :valid-until 2000}})
(defn signatures [body]
  {:operations [:signed :operations (log/digest body)]
   :independent-auditor [:signed :independent-auditor (log/digest body)]})
(defn verify [witness body signature]
  (= signature [:signed witness (log/digest body)]))

(deftest checkpoint-binds-append-only-root-key-epoch-and-witnesses
  (let [state (-> {:leaves [] :root log/genesis}
                  (log/append-leaf {:receipt-cid "r1"
                                    :execution-identity-cid "e1"}))
        base (log/checkpoint state {:key-id "key-2" :key-epoch 2
                                    :issued-at 1500
                                    :previous-checkpoint-cid nil
                                    :signatures {}})
        cp (assoc base :signatures (signatures (dissoc base :signatures)))]
    (is (:transparency/valid?
         (log/verify-checkpoint policy key-schedule nil cp verify)))
    (is (= [:transparency/witness-threshold]
           (:transparency/errors
            (log/verify-checkpoint policy key-schedule nil
                                   (update cp :signatures dissoc :independent-auditor)
                                   verify))))
    (is (= #{:transparency/key-epoch :transparency/signature}
           (set (:transparency/errors
                 (log/verify-checkpoint policy key-schedule nil
                                        (assoc cp :key-epoch 1)
                                        verify)))))))

(deftest retention-honors-class-hold-and-checkpoint-before-key-retirement
  (let [receipts [{:receipt-cid "security" :issued-at 0
                   :retention-class :security :encrypted? true}
                  {:receipt-cid "held" :issued-at 0
                   :retention-class :ephemeral :encrypted? true}
                  {:receipt-cid "plain" :issued-at 0
                   :retention-class :ephemeral :encrypted? false}]
        decision (log/retention-decision
                  policy 200 receipts #{"held"} {:issued-at 150})]
    (is (= #{"held"} (:retain decision)))
    (is (= #{"security"} (:crypto-shred decision)))
    (is (= #{"plain"} (:delete decision)))))

(deftest key-rotation-is-cross-signed-and-monotonic
  (let [old {:id "key-1" :epoch 1 :valid-from 0 :valid-until 1200}
        new {:id "key-2" :epoch 2 :valid-from 1000 :valid-until 2000}
        body {:from-key-id "key-1" :to-key-id "key-2" :effective-at 1100}
        rotation (assoc body :signatures
                        {"key-1" [:signed "key-1" (log/digest body)]
                         "key-2" [:signed "key-2" (log/digest body)]})
        verifier (fn [key-id value signature]
                   (= signature [:signed key-id (log/digest value)]))]
    (is (:rotation/valid? (log/verify-rotation old new rotation verifier)))
    (is (= [:rotation/next-signature]
           (:rotation/errors
            (log/verify-rotation old new
                                 (update rotation :signatures dissoc "key-2")
                                 verifier))))))

(deftest checkpoint-replay-detection
  "Test that submitting a checkpoint with identical tree-size and root as previous is rejected as duplicate"
  (let [state (-> {:leaves [] :root log/genesis}
                  (log/append-leaf {:receipt-cid "r1"
                                    :execution-identity-cid "e1"}))
        cp1 (log/checkpoint state {:key-id "key-2" :key-epoch 2
                                   :issued-at 1500
                                   :previous-checkpoint-cid nil
                                   :signatures {}})
        cp1-signed (assoc cp1 :signatures (signatures (dissoc cp1 :signatures)))
        ;; Create cp2 as exact replay of cp1 (same tree-size, root, issued-at)
        ;; but with correct previous-checkpoint-cid pointing to cp1
        cp2-body (assoc (dissoc cp1-signed :signatures)
                        :previous-checkpoint-cid (log/digest cp1-signed))
        cp2 (assoc cp2-body :signatures (signatures cp2-body))
        result (log/verify-checkpoint policy key-schedule cp1-signed cp2 verify)]
    (is (= #{:transparency/duplicate-checkpoint :transparency/issued-at-non-monotonic}
           (set (:transparency/errors result))))
    (is (not (:transparency/valid? result)))))

(deftest checkpoint-replay-with-different-timestamp
  "Test that replay with same tree-size/root but later timestamp is rejected as duplicate (timestamp check passes)"
  (let [state (-> {:leaves [] :root log/genesis}
                  (log/append-leaf {:receipt-cid "r1"
                                    :execution-identity-cid "e1"}))
        cp1 (log/checkpoint state {:key-id "key-2" :key-epoch 2
                                   :issued-at 1500
                                   :previous-checkpoint-cid nil
                                   :signatures {}})
        cp1-signed (assoc cp1 :signatures (signatures (dissoc cp1 :signatures)))
        ;; Create cp2 as replay with same tree-size/root but LATER timestamp
        cp2-body (assoc (dissoc cp1-signed :signatures)
                        :issued-at 1600
                        :previous-checkpoint-cid (log/digest cp1-signed))
        cp2 (assoc cp2-body :signatures (signatures cp2-body))
        result (log/verify-checkpoint policy key-schedule cp1-signed cp2 verify)]
    ;; Should detect duplicate but NOT non-monotonic (since 1600 > 1500)
    (is (= #{:transparency/duplicate-checkpoint}
           (set (:transparency/errors result))))
    (is (not (:transparency/valid? result)))))