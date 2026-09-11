(ns kotobase.canonical-causal-commit-test
  (:require [clojure.test :refer [deftest is testing]]
            [identity.adapters.ledger :as identity-ledger]
            [kotobase.causal-commit :as causal]
            [kotobase.causal-trust-test :as fixture]
            [kotobase.core :as core]
            [kotobase.storage.core :as storage]
            [kotobase.storage.memory :as memory]))

(defn- database [backend]
  (core/open {:storage backend
              :encrypt-fn identity
              :decrypt-fn identity
              :blind-fn pr-str
              :visible? (constantly true)}))

(defn- failing-block-backend [delegate]
  (let [writes (atom 0)]
    {:writes writes
     :backend
     (reify
       storage/IBlockStore
       (-put-blocks! [_ blocks]
         (storage/-put-blocks! delegate blocks)
         (swap! writes + (count blocks))
         (throw (ex-info "injected block failure" {:writes @writes})))
       (-get-blocks [_ cids]
         (storage/-get-blocks delegate cids))

       storage/IRefStore
       (-read-ref [_ name]
         (storage/-read-ref delegate name))
       (-compare-and-set-ref! [_ name expected next]
         (storage/-compare-and-set-ref! delegate name expected next))

       storage/IBackendCapabilities
       (-capabilities [_]
         (storage/-capabilities delegate)))}))

(deftest identity-transition-is-one-verifiable-canonical-commit
  (let [backend (memory/memory-store)
        db (database backend)
        ack (identity-ledger/persist-transition!
             (causal/identity-ledger db)
             fixture/epoch-transition fixture/new-epoch
             {:basis-cid "bafy-transition-basis"
              :open-obligation-ids ["obligation:repair"]
              :active-grant-ids ["grant:old"]}
             {:tx/receipt-cid "bafy-transition-receipt"
              :tx/expected-basis-cid nil})
        proof (causal/receipt-at db (:receipt/commit-cid ack)
                                 (:receipt/cid ack))
        datoms (mapv :causal.record/datom (:receipt/records proof))]
    (is (true? (:receipt/durable? ack)))
    (is (= :canonical-cid-dag (:receipt/route ack)))
    (is (string? (:receipt/commit-cid ack)))
    (is (nil? (core/head db)))
    (is (= nil (:receipt/basis-cid proof)))
    (is (= ["transition:repentance" "epoch:new"]
           (mapv :db/id datoms)))
    (is (zero? (:identity.epoch/initial-trust (second datoms))))))

(deftest canonical-route-fails-closed-on-partial-or-forged-storage
  (testing "a failed block sequence returns no commit acknowledgement"
    (let [delegate (memory/memory-store)
          {:keys [backend writes]} (failing-block-backend delegate)
          db (database backend)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"injected block failure"
           (identity-ledger/persist-transition!
            (causal/identity-ledger db)
            fixture/epoch-transition fixture/new-epoch
            {:basis-cid "bafy-transition-basis"
             :open-obligation-ids ["obligation:repair"]
             :active-grant-ids ["grant:old"]}
            {:tx/receipt-cid "bafy-partial"
             :tx/expected-basis-cid nil})))
      (is (pos? @writes))
      (is (nil? (core/head db)))
      (is (empty? (:refs (memory/snapshot delegate))))))

  (testing "bytes forged under a returned CID are rejected on exact reread"
    (let [backend (memory/memory-store)
          db (database backend)
          ack (identity-ledger/persist-transition!
               (causal/identity-ledger db)
               fixture/epoch-transition fixture/new-epoch
               {:basis-cid "bafy-transition-basis"
                :open-obligation-ids ["obligation:repair"]
                :active-grant-ids ["grant:old"]}
               {:tx/receipt-cid "bafy-forgery"
                :tx/expected-basis-cid nil})
          commit-cid (:receipt/commit-cid ack)]
      (swap! (:state backend) assoc-in
             [:blocks commit-cid]
             (.getBytes "forged" "UTF-8"))
      (is (thrown? clojure.lang.ExceptionInfo
                   (causal/receipt-at db commit-cid "bafy-forgery")))))

  (testing "legacy numeric revisions cannot cross the migration boundary"
    (let [db (database (memory/memory-store))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (identity-ledger/transact!
                    (causal/identity-ledger db)
                    [{:db/id "epoch:new"}]
                    {:tx/receipt-cid "bafy-wrong-options"
                     :tx/expected-revision 0})))))

  (testing "a decision cannot be committed against a different basis"
    (let [db (database (memory/memory-store))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (causal/persist-decision!
                    db
                    (assoc fixture/receipt-template
                           :causal.receipt/id "bafy-wrong-basis"
                           :causal.receipt/outcome
                           {:outcome/status :disclosed
                            :outcome/row-count 0}
                           :causal.receipt/at
                           "2026-08-27T01:00:01Z")
                    "bafy-another-basis"))))))
