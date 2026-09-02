(ns kotobase.execution-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.execution-contract :as contract]))

(def manifest
  {:execution/version 1
   :data/commit "bafy-data"
   :authority/policy "bafy-policy"
   :authority/epoch 42
   :location/manifest "bafy-pack-map"
   :schema/root "bafy-schema"
   :parent nil
   :issued-at "2026-09-02T00:00:00Z"
   :signature "sig:manifest"})

(def request
  {:request/version 1
   :principal "did:key:alice"
   :tenant "acme"
   :graph "invoices"
   :operation :query/execute
   :query/digest "bafy-plan"
   :base/commit "bafy-data"
   :authority/policy "bafy-policy"
   :authority/epoch 42
   :nonce "nonce:1"
   :expires-at "2026-09-02T00:05:00Z"})

(def receipt
  {:receipt/version 1
   :request/digest "bafy-request"
   :execution/manifest "bafy-manifest"
   :query/plan-digest "bafy-plan"
   :authority/decision :allow
   :result/root "bafy-result"
   :cost {:dependent-hops 2
          :requests 6
          :bytes 183204
          :cache-profile :cold}
   :implementation/build "kotobase:test"
   :signature "sig:receipt"})

(def bundle
  {:manifest manifest
   :manifest-cid "bafy-manifest"
   :request request
   :request-digest "bafy-request"
   :receipt receipt})

(defn- reason [f]
  (:kotobase.execution-contract/reason
   (ex-data
    (try
      (f)
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) error
        error)))))

(deftest all-three-records-accept-the-canonical-shape
  (is (= manifest (contract/validate-manifest! manifest)))
  (is (= request (contract/validate-request! request)))
  (is (= receipt (contract/validate-receipt! receipt)))
  (is (= bundle (contract/validate-execution! bundle))))

(deftest records-are-versioned-and-exact
  (testing "unknown fields cannot acquire different meanings in two codecs"
    (is (= :invalid-keys
           (reason #(contract/validate-request! (assoc request :sql/text "select 1"))))))
  (testing "unknown versions are rejected instead of guessed"
    (is (= :invalid-manifest
           (reason #(contract/validate-manifest!
                     (assoc manifest :execution/version 2)))))))

(deftest protocol-and-credential-fields-never-enter-the-contract
  (is (= :forbidden-field
         (reason #(contract/validate-request!
                   (assoc request :principal {:id "did:key:alice"
                                              :credential/raw "secret"})))))
  (is (= :invalid-keys
         (reason #(contract/validate-receipt!
                   (assoc receipt :backend :datalog)))))
  (is (= :invalid-receipt
         (reason #(contract/validate-receipt!
                   (assoc receipt :signature false))))))

(deftest receipts-close-both-allow-and-deny-decisions
  (is (= :invalid-receipt
         (reason #(contract/validate-receipt!
                   (assoc receipt :authority/decision :deny)))))
  (is (map? (contract/validate-receipt!
             (assoc receipt :authority/decision :deny :result/root nil))))
  (is (= :invalid-receipt
         (reason #(contract/validate-receipt!
                   (assoc-in receipt [:cost :requests] -1))))))

(deftest cross-record-invariants-fail-closed
  (is (= :cross-record-mismatch
         (reason #(contract/validate-execution!
                   (assoc bundle :request
                          (assoc request :base/commit "bafy-other"))))))
  (is (= :cross-record-mismatch
         (reason #(contract/validate-execution!
                   (assoc bundle :receipt
                          (assoc receipt :request/digest "bafy-other"))))))
  (is (= :cross-record-mismatch
         (reason #(contract/validate-execution!
                   (assoc bundle :receipt
                          (assoc receipt :execution/manifest "bafy-other"))))))
  (is (= :invalid-bundle-keys
         (reason #(contract/validate-execution! (assoc bundle :backend :sql))))))
