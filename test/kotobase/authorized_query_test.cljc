(ns kotobase.authorized-query-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.authorized-query :as query]))

(def request
  {:find [:invoice/id :invoice/amount]
   :where [[:invoice/tenant-id "acme"]]
   :scope {:tenant "acme" :resources #{"INV-42"} :purpose :payment-review
           :basis "bafy-basis"}
   :limit 20})

(defn receipt! [_]
  {:receipt/durable? true :receipt/cid "bafy-receipt"})

(defn authorize [q]
  {:allowed? true :projection (set (:find q)) :basis "bafy-basis" :policy-cid "bafy-policy"})

(deftest query-is-bounded-authorized-and-basis-bound
  (let [compiled (query/compile! authorize request)]
    (is (= "bafy-basis" (get-in compiled [:provenance :basis])))
    (is (= [{:invoice/id "INV-42" :invoice/amount 5000}]
           (:rows (query/execute! (fn [_ _] [{:invoice/id "INV-42" :invoice/amount 5000}])
                                  receipt! compiled))))
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

(deftest a-read-that-leaves-no-receipt-is-refused
  ;; `kotobase.admission` will not run an effect until its audit says durable,
  ;; so `who did this` is answerable. Without the same requirement here `who
  ;; read this` is not: a query could be admitted, evaluated, and leave nothing
  ;; behind. The rows are withheld, not merely logged as unrecorded.
  (let [compiled (query/compile! authorize request)
        rows (fn [_ _] [{:invoice/id "INV-42" :invoice/amount 5000}])
        reason (fn [sink]
                 (:kotobase.query/reason
                  (ex-data (try (query/execute! rows sink compiled)
                                (catch #?(:clj clojure.lang.ExceptionInfo
                                          :cljs cljs.core.ExceptionInfo) e e)))))]
    (is (= :missing-receipt-sink (reason nil)))
    (is (= :receipt-not-durable (reason (fn [_] nil))))
    (is (= :receipt-not-durable (reason (fn [_] {:receipt/durable? false
                                                :receipt/cid "bafy"}))))
    ;; durable but unidentifiable is not a receipt either
    (is (= :receipt-not-durable (reason (fn [_] {:receipt/durable? true
                                                :receipt/cid ""}))))
    ;; and the receipt identity comes back in the provenance, with the count,
    ;; because how much was read is part of what was read
    (let [{:keys [provenance]} (query/execute! rows receipt! compiled)]
      (is (= "bafy-receipt" (:receipt-cid provenance)))
      (is (= 1 (:row-count provenance))))))

(deftest the-sink-is-told-what-it-is-recording
  ;; a receipt sink that is handed nothing cannot write a receipt about
  ;; anything; it has to see the compiled query and how much came back
  (let [seen (atom nil)
        compiled (query/compile! authorize request)]
    (query/execute! (fn [_ _] [{:invoice/id "INV-42" :invoice/amount 5000}])
                    (fn [payload]
                      (reset! seen payload)
                      {:receipt/durable? true :receipt/cid "bafy-receipt"})
                    compiled)
    (is (= #{:compiled :row-count} (set (keys @seen))))
    (is (= 1 (:row-count @seen)))
    (is (= "acme" (get-in @seen [:compiled :query :scope :tenant])))
    (is (= :payment-review (get-in @seen [:compiled :query :scope :purpose])))))
