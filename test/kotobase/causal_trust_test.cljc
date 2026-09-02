(ns kotobase.causal-trust-test
  (:require [clojure.test :refer [deftest is testing]]
            [grant.causal-trust :as trust]
            [identity.adapters.ledger :as identity-ledger]
            [identity.causal :as causal]
            [kotobase.causal-trust :as causal-store]
            [kotobase.local :as local]
            [kotobase.store :as store]))

(def new-epoch
  (causal/epoch "epoch:new" "did:key:alice"
                {:previous "epoch:old"
                 :sequence 1
                 :started-at "2026-08-27T00:00:00Z"}))

(def epoch-transition
  (causal/transition
   "transition:repentance" "epoch:old" "epoch:new"
   {:commitment-cid "bafy-commitment"
    :open-obligations ["obligation:repair"]
    :revoked-grants ["grant:old"]
    :witness-claims ["claim:witness"]
    :policy-cid "bafy-transition-policy"
    :basis-cid "bafy-transition-basis"
    :occurred-at "2026-08-27T00:00:00Z"
    :proof "bafy-transition-proof"}))

(def authority-input
  {:authority/principal {:principal/id "did:key:alice"
                         :principal/kind :human
                         :principal/authenticated? true
                         :principal/tenant "acme"
                         :principal/assurance :high}
   :authority/actor {:actor/id :agent/reader :actor/kind :agent}
   :authority/intent {:intent/action :object/read
                      :intent/resource #{"INV-42"}}
   :authority/effects #{:object/read}
   :authority/grants [{:grant/id "grant:new"
                       :grant/subject "did:key:alice"
                       :grant/actions #{:object/read}
                       :grant/resources #{#{"INV-42"}}
                       :grant/tenant "acme"}]
   :authority/policy {:policy/public #{}
                      :policy/deny-actions #{}
                      :policy/min-assurance {}
                      :policy/required-approvals {}}
   :authority/context {:context/now 1
                       :context/tenant "acme"
                       :context/approvals #{}
                       :context/nonce-used? false}})

(def reader-claim
  (causal/trust-claim
   "claim:reader" "epoch:new" :fulfilled-obligation
   {:scope [:transaction :reader]
    :issuer "did:key:evaluator"
    :evaluator {:evaluator/id "agent:risk"
                :evaluator/kind :llm
                :evaluator/model-cid "bafy-model"}
    :evidence ["bafy-evidence"]
    :policy-cid "bafy-evaluator-policy"
    :confidence 0.9
    :issued-at "2026-08-27T00:00:00Z"}))

(def reader-decision
  (trust/decide
   {:causal.trust/authority authority-input
    :causal.trust/epoch new-epoch
    :causal.trust/claims [reader-claim]
    :causal.trust/requirements
    [{:trust.requirement/scope [:transaction :reader]
      :trust.requirement/predicate :fulfilled-obligation
      :trust.requirement/min-confidence 0.8
      :trust.requirement/min-independent-issuers 1}]
    :causal.trust/policy-cid "bafy-authority-policy"
    :causal.trust/intent-cid "bafy-intent"
    :causal.trust/basis-cid "bafy-basis"
    :causal.trust/now "2026-08-27T01:00:00Z"}))

(def classified-schema
  {:invoice/id {:class :public}
   :invoice/amount {:class :internal}})

(def invoice-query
  {:find [:invoice/id :invoice/amount]
   :where [[:invoice/id "INV-42"]]
   :scope {:tenant "acme"
           :resources #{"INV-42"}
           :purpose :payment-review
           :basis "bafy-basis"}
   :limit 1})

(def receipt-template
  {:causal.receipt/intent-cid "bafy-intent"
   :causal.receipt/principal "did:key:alice"
   :causal.receipt/epoch-cid "epoch:new"
   :causal.receipt/policy-cid "bafy-authority-policy"
   :causal.receipt/basis-cid "bafy-basis"
   :causal.receipt/claim-cids ["claim:reader"]
   :causal.receipt/decision reader-decision})

(defn- non-transactional-store []
  (reify store/IStore
    (-put [_ _ _ value] value)
    (-get [_ _ _] nil)
    (-list [_ _] [])
    (-append [_ _ event] event)
    (-read [_ _ _] [])))

(deftest epoch-transition-and-successor-are-one-transaction
  (let [backend (local/local-store)
        ack (identity-ledger/persist-transition!
             (causal-store/identity-ledger backend)
             epoch-transition new-epoch
             {:basis-cid "bafy-transition-basis"
              :open-obligation-ids ["obligation:repair"]
              :active-grant-ids ["grant:old"]}
             {:tx/receipt-cid "bafy-transition-receipt"
              :tx/expected-revision 0})
        events (get-in (local/snapshot backend)
                       [:streams causal-store/identity-stream])
        datoms (mapv :causal.record/datom events)]
    (is (true? (:receipt/durable? ack)))
    (is (= "bafy-transition-receipt" (:receipt/cid ack)))
    (is (= 2 (count events)))
    (is (= ["transition:repentance" "epoch:new"]
           (mapv :db/id datoms)))
    (is (= ["obligation:repair"]
           (:identity.transition/open-obligations (first datoms))))
    (is (= ["grant:old"]
           (:identity.transition/revoked-grants (first datoms))))
    (is (zero? (:identity.epoch/initial-trust (second datoms))))))

(deftest an-authority-decision-is-appended-atomically
  ;; the read path that used to be exercised here is gone — `kotobase.
  ;; governed-read` commits an ExecutionReceipt instead of a disclosure
  ;; receipt that could answer one of the contract's eight fields. What this
  ;; namespace still owns is authority decision persistence, which is a
  ;; different subject and has no result to name
  (let [backend (local/local-store)
        ack (causal-store/persist-decision!
             backend
             (assoc receipt-template
                    :causal.receipt/id "bafy-decision"
                    :causal.receipt/outcome {:outcome/status :pending}
                    :causal.receipt/at "2026-09-02T01:00:01Z")
             0)
        stored (get-in (local/snapshot backend)
                       [:streams causal-store/decision-stream 0])]
    (is (true? (:receipt/durable? ack)))
    (is (= "bafy-decision" (:receipt/cid ack)))
    (is (= ["claim:reader"] (:causal.receipt/claim-cids stored)))
    (is (= :allow (get-in stored [:causal.receipt/decision :decision/status])))
    (is (not (contains? stored :credential/raw)))))

(deftest persistence-and-authority-fail-closed
  (testing "causal records require atomic persistence"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (causal-store/identity-ledger
                  (non-transactional-store)))))
  (testing "raw credentials have no receipt slot"
    ;; still a live path: a decision receipt is validated by
    ;; `grant.causal-trust/receipt`, whose key set is exact
    (let [backend (local/local-store)]
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (causal-store/persist-decision!
                    backend
                    (assoc receipt-template
                           :causal.receipt/id "bafy-no"
                           :causal.receipt/outcome {:outcome/status :pending}
                           :causal.receipt/at "2026-09-02T01:00:01Z"
                           :credential/raw "secret")
                    0))))))
