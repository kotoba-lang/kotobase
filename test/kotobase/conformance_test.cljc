(ns kotobase.conformance-test
  "Two frontends, one request, and whether the harness can tell.

  The frontends here are two implementations of one query surface, not two
  protocols — this repository has one. That is enough to show the harness
  discriminates and not enough to claim cross-protocol agreement, and the
  namespace docstring says so. What is being tested is the comparison, so the
  interesting cases are the ones where it must refuse."
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.conformance :as conformance]
            [kotobase.execution-identity :as id]
            [kotobase.governed-execution :as governed]
            [kotobase.governed-execution-test :as fixture]))

(def rows
  [{:invoice/id "INV-42" :invoice/amount 5000}
   {:invoice/id "INV-43" :invoice/amount 250}])

(defn- execute
  "Run the fixture composition with one frontend's evaluator and plan digest."
  [frontend {:keys [evaluate! plan]}]
  (let [journal (atom [])
        result (governed/execute!
                (fixture/options journal
                                 :evaluate! (fn [_ _] (evaluate!))
                                 :plan-digest (fn [compiled]
                                                (if compiled plan "none"))))]
    {:frontend frontend
     :receipt (:execution/receipt result)
     :rows (:rows result)}))

(def index-scan
  {:evaluate! (fn [] rows)
   :plan (str "plan:index-scan")})

(def reverse-scan
  "The same answer, served the other way round, under its own plan."
  {:evaluate! (fn [] (vec (reverse rows)))
   :plan "plan:reverse-scan"})

(defn- reason [f]
  (:kotobase.conformance/reason
   (ex-data (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo
                                   :cljs ExceptionInfo) e e)))))

(defn- agreement [& frontends]
  (conformance/agree!
   {:value-cid id/value-cid
    :query fixture/query
    :executions (mapv (fn [[name spec]] (execute name spec)) frontends)}))

(deftest two-frontends-may-differ-about-the-plan-and-not-about-the-answer
  (let [report (agreement [:index-scan index-scan] [:reverse-scan reverse-scan])]
    (testing "they answered the same question"
      (is (= (id/value-cid fixture/request) (:request/digest report))))
    (testing "and gave the same answer"
      (is (= (conformance/canonical-result id/value-cid fixture/query rows)
             (:result/canonical report))))
    (testing "under different plans, which the contract permits"
      (is (= {:index-scan "plan:index-scan"
              :reverse-scan "plan:reverse-scan"}
             (into {} (:frontends report))))
      (is (contains? (:divergent-fields report) :query/plan-digest)))))

(deftest a-result-root-is-not-the-identity-of-an-answer
  ;; the finding this harness produced. `:result/root` is the address of the
  ;; rows as served, and an address is order sensitive; the query grammar has
  ;; no ordering clause, so the answer is a multiset and two conformant
  ;; frontends can legitimately serve it in different orders
  (let [report (agreement [:index-scan index-scan] [:reverse-scan reverse-scan])]
    (is (contains? (:divergent-fields report) :result/root))
    (testing "and the caller is told, rather than left to notice"
      (is (map? (:order-divergence report)))
      (is (= #{:index-scan :reverse-scan}
             (set (keys (:order-divergence report)))))
      (is (= 2 (count (set (vals (:order-divergence report))))))))
  (testing "while identical serving order reports none"
    (let [report (agreement [:one index-scan] [:two index-scan])]
      (is (nil? (:order-divergence report)))
      (is (not (contains? (:divergent-fields report) :result/root))))))

(deftest disagreeing-about-the-answer-is-refused
  (testing "a row too few"
    (is (= :result-divergence
           (reason #(agreement [:index-scan index-scan]
                               [:partial {:evaluate! (fn [] [(first rows)])
                                          :plan "plan:partial"}])))))
  (testing "a row too many — a multiset is not a set"
    ;; a frontend that returned a row twice has said something different
    (is (= :result-divergence
           (reason #(agreement [:index-scan index-scan]
                               [:doubled {:evaluate! (fn [] (conj rows
                                                                  (first rows)))
                                          :plan "plan:doubled"}])))))
  (testing "and a different value in the same shape"
    (is (= :result-divergence
           (reason #(agreement [:index-scan index-scan]
                               [:wrong {:evaluate!
                                        (fn [] (assoc-in rows
                                                         [1 :invoice/amount] 251))
                                        :plan "plan:wrong"}]))))))

(deftest answering-a-different-question-is-refused
  (let [same (execute :index-scan index-scan)
        elsewhere (assoc-in (execute :other index-scan)
                            [:receipt :request/digest] "bafy-another-request")]
    (is (= :request-divergence
           (reason #(conformance/agree! {:value-cid id/value-cid
                                         :query fixture/query
                                         :executions [same elsewhere]}))))))

(deftest one-frontend-agreeing-with-itself-is-not-conformance
  (is (= :too-few-frontends
         (reason #(conformance/agree! {:value-cid id/value-cid
                                       :query fixture/query
                                       :executions [(execute :only index-scan)]}))))
  (testing "and neither is the same frontend listed twice"
    (let [one (execute :index-scan index-scan)]
      (is (= :duplicate-frontend
             (reason #(conformance/agree! {:value-cid id/value-cid
                                           :query fixture/query
                                           :executions [one one]}))))))
  (testing "and a receipt that is not a valid receipt is not compared"
    (let [one (execute :index-scan index-scan)]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                   (conformance/agree!
                    {:value-cid id/value-cid
                     :query fixture/query
                     :executions [one (update (execute :two index-scan)
                                              :receipt dissoc :signature)]}))))))

(deftest an-ordered-query-would-not-be-a-multiset
  ;; the canonicalisation is only lossless while the grammar cannot ask for an
  ;; order. This is where that stops being true, so it refuses rather than
  ;; silently sorting an answer whose order was the point
  (is (false? (conformance/ordered-query? fixture/query)))
  (is (true? (conformance/ordered-query? (assoc fixture/query :order-by
                                                [:invoice/amount]))))
  (is (= :ordered-query-cannot-be-canonicalised
         (reason #(conformance/canonical-result
                   id/value-cid (assoc fixture/query :order-by [:invoice/amount])
                   rows)))))
