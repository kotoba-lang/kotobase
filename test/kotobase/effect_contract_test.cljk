(ns kotobase.effect-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.effect-contract :as effect]))

(def request
  {:request/version 1
   :principal "did:key:alice"
   :tenant "acme"
   :effect/action :execute
   :effect/resource "bafy-object"
   :code/lock "bafy-lock"
   :effect/requested #{:object/read :object/write}
   :authority/policy "bafy-policy"
   :authority/epoch 7
   :nonce "nonce-1"
   :expires-at "2026-09-02T15:00:00Z"})

(def receipt
  {:effect/version 1
   :request/digest "bafy-request"
   :authority/policy "bafy-policy"
   :authority/epoch 7
   :effect/action :execute
   :effect/resource "bafy-object"
   :code/lock "bafy-lock"
   :effect/granted #{:object/read :object/write}
   :authority/decision :allow
   :outcome/roots ["bafy-output"]
   :cost {:dependent-hops 1 :requests 2 :bytes 512 :cache-profile :cold}
   :implementation/build "kotobase@test"
   :signature "sig"})

(def bundle
  {:request request :request-digest "bafy-request" :receipt receipt})

(defn- reason [f]
  (:kotobase.effect-contract/reason
   (ex-data (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo
                                   :cljs ExceptionInfo) e e)))))

(deftest the-records-are-exact
  (is (= receipt (effect/validate-receipt! receipt)))
  (is (= request (effect/validate-request! request)))
  (testing "a field nobody declared is not carried along"
    (is (= :invalid-keys (reason #(effect/validate-receipt!
                                   (assoc receipt :retries 3)))))
    (is (= :invalid-keys (reason #(effect/validate-request!
                                   (dissoc request :nonce))))))
  (testing "and the thunk, its bytes, and credentials have no slot"
    ;; a receipt that can hold the effect it authorised is a receipt that can
    ;; hold what it was not supposed to keep
    (doseq [field [:effect/thunk :package/bytes :credential/raw :token]]
      (is (= :invalid-keys (reason #(effect/validate-receipt!
                                     (assoc receipt field "x"))))))
    (is (= :forbidden-field
           (reason #(effect/validate-receipt!
                     (assoc-in receipt [:cost :cache-profile]
                               {:credential/raw "x"})))))))

(deftest an-outcome-belongs-to-an-admitted-effect-and-only-to-one
  (testing "a refusal produced nothing"
    (let [refused (assoc receipt :authority/decision :deny :outcome/roots [])]
      (is (= refused (effect/validate-receipt! refused))))
    (is (= :invalid-receipt
           (reason #(effect/validate-receipt!
                     (assoc receipt :authority/decision :deny))))))
  (testing "and an admitted effect that names nothing has not said what it did"
    (is (= :invalid-receipt
           (reason #(effect/validate-receipt!
                     (assoc receipt :outcome/roots []))))))
  (testing "roots are addresses, not blanks"
    (is (= :invalid-receipt
           (reason #(effect/validate-receipt!
                     (assoc receipt :outcome/roots ["bafy-output" ""])))))))

(deftest what-was-granted-has-to-have-been-asked-for
  (is (= bundle (effect/validate-effect! bundle)))
  (testing "an effect nobody requested cannot survive an intersection"
    (is (= :granted-outside-request
           (reason #(effect/validate-effect!
                     (assoc-in bundle [:receipt :effect/granted]
                               #{:object/read :object/delete}))))))
  (testing "and an allow that granted less than was asked is two decisions"
    ;; admission allows only when nothing requested is missing, so this shape
    ;; records a refusal and an approval at the same time
    (is (= :allowed-with-missing-effects
           (reason #(effect/validate-effect!
                     (assoc-in bundle [:receipt :effect/granted]
                               #{:object/read}))))))
  (testing "while a denial may name the subset it did reach"
    (is (map? (effect/validate-effect!
               (-> bundle
                   (assoc-in [:receipt :authority/decision] :deny)
                   (assoc-in [:receipt :outcome/roots] [])
                   (assoc-in [:receipt :effect/granted] #{:object/read})))))))

(deftest the-two-records-must-be-about-the-same-effect
  (doseq [[field value] [[:authority/policy "bafy-other-policy"]
                         [:authority/epoch 8]
                         [:effect/action :pin]
                         [:effect/resource "bafy-other"]
                         [:code/lock "bafy-other-lock"]]]
    (is (= :cross-record-mismatch
           (reason #(effect/validate-effect!
                     (assoc-in bundle [:receipt field] value))))
        (str field " must agree across the two records")))
  (testing "and the digest the host calculated must be the one bound"
    (is (= :cross-record-mismatch
           (reason #(effect/validate-effect!
                     (assoc bundle :request-digest "bafy-elsewhere")))))
    (is (= :invalid-bundle-keys
           (reason #(effect/validate-effect! (dissoc bundle :request-digest)))))))
