(ns kotobase.authorized-query-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.authorized-query :as query]))

(defn v [name] (symbol (str "?" name)))

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
                          (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))))))))

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
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))))))
  (is (= :basis-mismatch
         (:kotobase.query/reason
          (ex-data (try (query/compile! #(assoc (authorize %) :basis "other") request)
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))))))

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
                                          :cljs ExceptionInfo) e e)))))]
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


;; --- Static analysis: recursion detection -----------------------------------

(def recursive-query
  {:find [(v "x") (v "y") (v "z")]
   :where [[(v "x") :parent (v "y")] [(v "y") :parent (v "z")] [(v "z") :parent (v "x")]]
   :scope {:tenant "acme" :resources #{"INV-42"} :purpose :payment-review
           :basis "bafy-basis"}
   :limit 10})

(deftest recursive-query-rejected
  "Detects cyclic patterns like [?x :parent ?y] [?y :parent ?z] [?z :parent ?x]"
  (is (= :invalid-query
         (:kotobase.query/reason
          (ex-data (try (query/compile! authorize recursive-query)
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))

;; Mutual recursion: A -> B -> A
(def mutual-recursive-query
  {:find [(v "a") (v "b")]
   :where [[(v "a") :knows (v "b")] [(v "b") :knows (v "a")]]
   :scope {:tenant "acme" :resources #{"INV-42"} :purpose :payment-review
           :basis "bafy-basis"}
   :limit 10})

(deftest mutual-recursive-query-rejected
  "Detects mutual recursion patterns"
  (is (= :invalid-query
         (:kotobase.query/reason
          (ex-data (try (query/compile! authorize mutual-recursive-query)
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))

;; --- Static analysis: join explosion detection ------------------------------

;; 4-way join with shared variables causing high fanout
(def join-explosion-query
  {:find [(v "a") (v "b") (v "c") (v "d")]
   :where [[(v "a") :rel1 (v "b")] [(v "b") :rel2 (v "c")] [(v "c") :rel3 (v "d")] [(v "d") :rel4 (v "a")]
           [(v "a") :rel5 (v "x")] [(v "b") :rel6 (v "y")] [(v "c") :rel7 (v "z")] [(v "d") :rel8 (v "w")]]
   :scope {:tenant "acme" :resources #{"INV-42"} :purpose :payment-review
           :basis "bafy-basis"}
   :limit 10})

(deftest join-explosion-query-rejected
  "Detects high-fanout joins with cycles causing exponential intermediate results"
  (is (= :invalid-query
         (:kotobase.query/reason
          (ex-data (try (query/compile! authorize join-explosion-query)
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e))))))

;; Star join pattern - center variable connects to many leaves
(def star-join-query
  {:find [(v "center") (v "leaf1") (v "leaf2") (v "leaf3") (v "leaf4") (v "leaf5")]
   :where [[(v "center") :connects (v "leaf1")] [(v "center") :connects (v "leaf2")]
           [(v "center") :connects (v "leaf3")] [(v "center") :connects (v "leaf4")]
           [(v "center") :connects (v "leaf5")]]
   :scope {:tenant "acme" :resources #{"INV-42"} :purpose :payment-review
           :basis "bafy-basis"}
   :limit 10})

(deftest star-join-query-rejected
  "Detects star join patterns with high fanout"
  (is (= :invalid-query
         (:kotobase.query/reason
          (ex-data (try (query/compile! authorize star-join-query)
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))
)
