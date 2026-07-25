(ns kotobase.hostile-fault-model-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.local :as local]
            [kotobase.store :as store])
  (:import [java.util Collections Random]))

(defn request [id revision key value]
  {:tx-id id :expected-revision revision
   :puts [["docs" key value]] :deletes [] :appends []})

(defn apply-logical-operations [order]
  (let [db (local/local-store)]
    (doseq [n order
            :let [revision (:revision
                            (store/-snapshot db
                                             {:collections [] :streams []}))
                  tx (request (str "tx-" n) revision (str "k-" n) n)
                  receipt (store/-transact db tx)]]
      (is (= receipt (store/-transact db tx))
          "network duplicate returns the original receipt"))
    db))

(deftest retry-duplicate-and-reorder-converge
  (let [rng (Random. 0x4b4f544f42415345)
        expected (into {} (map #(vector (str "k-" %) %) (range 20)))]
    (dotimes [_ 100]
      (let [order (java.util.ArrayList. (range 20))]
        (Collections/shuffle order rng)
        (let [db (apply-logical-operations order)
              snapshot (store/-snapshot db
                                        {:collections ["docs"] :streams []})]
          (is (= expected (get-in snapshot [:docs "docs"])))
          (is (= 20 (:revision snapshot))))))))

(deftest partitioned-writers-conflict-then-retry-without-loss
  (let [db (local/local-store)
        left (request "left" 0 "left" 1)
        right (request "right" 0 "right" 2)
        left-receipt (store/-transact db left)]
    (is (= 1 (:revision left-receipt)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"revision conflict"
         (store/-transact db right)))
    (let [retried (assoc right :expected-revision 1)]
      (is (= 2 (:revision (store/-transact db retried))))
      (is (= {"left" 1 "right" 2}
             (get-in (store/-snapshot db
                                      {:collections ["docs"] :streams []})
                     [:docs "docs"]))))))

(deftest crash-recovery-preserves-idempotency-receipts
  (let [before (local/local-store)
        tx (request "durable-tx" 0 "a" 1)
        receipt (store/-transact before tx)
        recovered (local/local-store (local/snapshot before))]
    (is (= receipt (store/-transact recovered tx)))
    (is (= 1 (:revision
              (store/-snapshot recovered
                               {:collections ["docs"] :streams []}))))
    (is (= 1 (get-in (store/-snapshot recovered
                                      {:collections ["docs"] :streams []})
                     [:docs "docs" "a"])))))

(deftest stale-and-conflicting-tx-identities-never-mutate
  (let [db (local/local-store)
        original (request "same-id" 0 "a" 1)]
    (store/-transact db original)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"tx-id reused"
         (store/-transact db (assoc original :puts [["docs" "a" 2]]))))
    (is (= 1 (store/-get db "docs" "a")))
    (is (= 1 (:revision
              (store/-snapshot db {:collections ["docs"] :streams []}))))))

(deftest byzantine-transaction-shapes-fail-before-atomic-state-change
  (let [bad-requests
        [(assoc (request "bad-unknown" 0 "a" 1) :attacker true)
         (assoc (request "bad-revision" 0 "a" 1) :expected-revision -1)
         (assoc (request "bad-put" 0 "a" 1) :puts [["docs"]])
         (assoc (request "bad-delete" 0 "a" 1) :deletes [["docs"]])
         (assoc (request "bad-append" 0 "a" 1)
                :appends [["events" "not-a-map"]])
         (assoc (request "oversized" 0 "a" 1)
                :puts (vec (repeat 1001 ["docs" "a" 1])))]
        db (local/local-store)]
    (doseq [bad bad-requests]
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/-transact db bad))))
    (is (= {:revision 0 :docs {} :streams {}}
           (store/-snapshot db {:collections ["docs"] :streams ["events"]})))))
