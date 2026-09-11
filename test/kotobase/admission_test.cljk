(ns kotobase.admission-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.admission :as admission]))

(def base
  {:cid-verified? true
   :package-receipt {:package/verified? true
                     :package/lock-cid "bafy-lock"}
   :delegated-effects #{:code/hydrate :code/execute :store/pin}
   :local-policy-effects #{:code/hydrate :code/execute :store/pin}
   :resource "bafy-root"})

(defn durable-audit [entries]
  (fn [decision]
    (swap! entries conj decision)
    {:audit/durable? true :audit/receipt-id
     (str "receipt-" (count @entries))}))

(deftest cid-integrity-alone-never-authorizes
  (doseq [action admission/production-actions]
    (let [decision (admission/decide
                    {:action action :cid-verified? true
                     :requested-effects #{:code/execute}
                     :resource "bafy-root"})]
      (is (false? (:admission/allowed? decision)))
      (is (= :admission/package (:admission/code decision))))))

(deftest hydrate-execute-and-pin-require-full-intersection-and-audit
  (doseq [[action effect]
          [[:hydrate :code/hydrate]
           [:execute :code/execute]
           [:pin :store/pin]]]
    (let [effects (atom 0)
          audits (atom [])
          result (admission/guard!
                  (merge base {:action action :requested-effects #{effect}
                               :audit! (durable-audit audits)
                               :effect (fn [_] (swap! effects inc) :ok)}))]
      (is (= :ok (:result result)))
      (is (= 1 @effects))
      (is (= 1 (count @audits)))
      (is (= #{effect}
             (get-in result [:decision :admission/granted]))))))

(deftest every-denial-is-audited-and-never-invokes-effect
  (doseq [bad [(assoc base :package-receipt nil)
               (assoc base :delegated-effects #{})
               (assoc base :local-policy-effects #{})
               (assoc base :delegated-effects #{:any})
               (assoc base :cid-verified? false)]]
    (let [effects (atom 0)
          audits (atom [])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (admission/guard!
                    (merge bad {:action :execute
                                :requested-effects #{:code/execute}
                                :audit! (durable-audit audits)
                                :effect #(swap! effects inc)}))))
      (is (= 0 @effects))
      (is (= 1 (count @audits)))
      (is (false? (:admission/allowed? (first @audits)))))))

(deftest audit-failure-blocks-even-otherwise-authorized-effect
  (let [effects (atom 0)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"audit persistence failed"
         (admission/guard!
          (merge base {:action :execute
                       :requested-effects #{:code/execute}
                       :audit! (constantly {:audit/durable? false})
                       :effect #(swap! effects inc)}))))
    (is (= 0 @effects))))
