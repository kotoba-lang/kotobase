(ns kotobase.authorized-query-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.authorized-query :as query]))

(def request
  {:find [:invoice/id :invoice/amount]
   :where [[:invoice/tenant-id "acme"]]
   :scope {:tenant "acme" :resources #{"INV-42"} :purpose :payment-review
           :basis "bafy-basis"}
   :limit 20})

(defn authorize [q]
  {:allowed? true :projection (set (:find q)) :basis "bafy-basis" :policy-cid "bafy-policy"})

(deftest query-is-bounded-authorized-and-basis-bound
  (let [compiled (query/compile! authorize request)]
    (is (= "bafy-basis" (get-in compiled [:provenance :basis])))
    (is (= [{:invoice/id "INV-42" :invoice/amount 5000}]
           (:rows (query/execute! (fn [_ _] [{:invoice/id "INV-42" :invoice/amount 5000}]) compiled))))
    (is (= :projection-denied
           (:kotobase.query/reason
            (ex-data (try (query/compile! #(assoc (authorize %) :projection #{:invoice/id}) request)
                          (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e))))))))

(deftest query-receipt-projection-keeps-the-query-basis-and-scope
  (let [compiled (query/compile! authorize request)]
    (is (= {:query-cid "bafy-query" :result-cid "bafy-result"
            :basis "bafy-basis" :policy-cid "bafy-policy"
            :tenant "acme" :purpose :payment-review :resource-cids ["INV-42"]}
           (query/receipt-projection compiled "bafy-query" "bafy-result")))))

(deftest query-rejects-ambient-or-stale-authority
  (is (= :invalid-query
         (:kotobase.query/reason
          (ex-data (try (query/compile! authorize (dissoc request :scope))
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e))))))
  (is (= :basis-mismatch
         (:kotobase.query/reason
          (ex-data (try (query/compile! #(assoc (authorize %) :basis "other") request)
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))
