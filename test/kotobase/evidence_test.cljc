(ns kotobase.evidence-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [kotobase.code-graph :as code-graph]
            [kotobase.evidence :as evidence]
            [kotobase.execution-contract :as contract]
            [kotobase.governed-execution :as governed]
            [kotobase.governed-execution-test :as fixture]))

(defn- reason [f]
  (let [data (ex-data (try (f) nil
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs ExceptionInfo) e e)))]
    (:kotobase.evidence/reason data)))

(defn- data-of [f]
  (ex-data (try (f) nil
                (catch #?(:clj clojure.lang.ExceptionInfo
                          :cljs ExceptionInfo) e e))))

(def disclosure
  "The shape `grant.causal-trust/receipt` validates and `kotobase.causal-commit`
  commits. Only the parts a lift can read are spelled out."
  {:causal.receipt/id "bafy-disclosure"
   :causal.receipt/decision {:decision/status :allow}
   :causal.receipt/outcome {:outcome/status :disclosed :outcome/row-count 1}})

(def query-receipt
  {:cid "bafy-query-receipt"
   :block {}
   :execution-identity-cid "bafy-identity"
   :query-cid "bafy-query"
   :result-cid "bafy-result"
   :basis "bafy-basis"
   :policy-cid "bafy-policy"
   :tenant "acme"
   :purpose :payment-review
   :resource-cids ["bafy-invoice"]})

(def execution-identity
  {:plan-cid "bafy-plan"
   :db-basis "bafy-basis"
   :policy-cid "bafy-policy"
   :host-receipt-cids ["bafy-query-receipt"]})

(def code-graph-source
  {:receipt query-receipt :execution-identity execution-identity})

(defn- supplement-for [plane source]
  (select-keys {:request/digest "bafy-request"
                :execution/manifest "bafy-manifest"
                :query/plan-digest "bafy-plan"
                :result/root "bafy-result"
                :cost {:dependent-hops 1 :requests 2 :bytes 512
                       :cache-profile :cold}
                :implementation/build "kotobase@lift"
                :signature "sig"}
               (evidence/missing plane source)))

(deftest the-plane-count-is-a-partition-not-a-slogan
  (testing "every field of a version 1 receipt is somebody's job"
    ;; derived from the contract rather than restated, so a version 2 field
    ;; cannot appear without appearing here
    (is (= contract/receipt-keys
           (set/union evidence/answerable evidence/adapter-supplied)))
    (is (empty? (set/intersection evidence/answerable
                                  evidence/adapter-supplied))))
  (testing "and the five planes are sorted into two subjects"
    (is (= #{:causal-disclosure :code-graph-query :governed-execution}
           evidence/query-execution-planes))
    (is (= #{:code-graph-execution :admission}
           (set (keys evidence/effect-planes))))))

(deftest a-disclosure-receipt-answers-the-decision-and-almost-nothing-else
  (testing "what it carries"
    (is (= {:authority/decision :allow}
           (evidence/carried :causal-disclosure disclosure))))
  (testing "and what it cannot"
    ;; the disclosure binds an evaluated row count, which is a fact about how
    ;; many rows there were rather than which ones, so it cannot name a result
    (is (= #{:request/digest :execution/manifest :query/plan-digest
             :result/root :cost :implementation/build :signature}
           (evidence/missing :causal-disclosure disclosure))))
  (testing "a denial does carry that there is no result"
    (let [denied (assoc disclosure :causal.receipt/decision
                        {:decision/status :deny})]
      (is (= {:authority/decision :deny :result/root nil}
             (evidence/carried :causal-disclosure denied)))
      (is (not (contains? (evidence/missing :causal-disclosure denied)
                          :result/root)))))
  (testing "and a record whose decision cannot be read is not lifted"
    (is (= :unreadable-source
           (reason #(evidence/carried :causal-disclosure
                                      (assoc disclosure
                                             :causal.receipt/decision
                                             {:decision/status :maybe})))))))

(deftest a-query-receipt-is-read-with-the-identity-that-binds-it
  (testing "the pair answers three fields the disclosure cannot"
    (is (= {:authority/decision :allow
            :result/root "bafy-result"
            :query/plan-digest "bafy-plan"}
           (evidence/carried :code-graph-query code-graph-source)))
    (is (= #{:request/digest :execution/manifest :cost
             :implementation/build :signature}
           (evidence/missing :code-graph-query code-graph-source))))
  (testing "and the fixture is the shape the write path actually enforces"
    ;; derived from `code-graph`, so a receipt that gains or loses a field
    ;; breaks this rather than drifting away from it silently
    (is (= code-graph/query-receipt-keys (set (keys query-receipt)))))
  (testing "an identity that does not bind this receipt is two records"
    (is (= :receipt-not-bound-by-identity
           (reason #(evidence/carried
                     :code-graph-query
                     (assoc-in code-graph-source
                               [:execution-identity :host-receipt-cids]
                               ["bafy-somebody-else"]))))))
  (testing "and a pair that disagrees is not one execution"
    (is (= :basis-mismatch
           (reason #(evidence/carried
                     :code-graph-query
                     (assoc-in code-graph-source
                               [:execution-identity :db-basis] "bafy-other")))))
    (is (= :policy-mismatch
           (reason #(evidence/carried
                     :code-graph-query
                     (assoc-in code-graph-source
                               [:execution-identity :policy-cid]
                               "bafy-other")))))))

(deftest a-lift-is-exactly-as-complete-as-the-supplement-it-demands
  (doseq [[plane source] [[:causal-disclosure disclosure]
                          [:code-graph-query code-graph-source]]]
    (let [supplement (supplement-for plane source)
          lifted (evidence/lift plane source supplement)]
      (testing (str plane " lifts into a valid version 1 receipt")
        (is (= lifted (contract/validate-receipt! lifted)))
        (is (= contract/version (:receipt/version lifted))))
      (testing (str plane " refuses an incomplete lift")
        (doseq [field (keys supplement)]
          (let [data (data-of #(evidence/lift plane source
                                              (dissoc supplement field)))]
            (is (= :supplement-mismatch (:kotobase.evidence/reason data)))
            (is (= #{field} (:missing data))))))
      (testing (str plane " refuses a field it already answers")
        (doseq [field (keys (evidence/carried plane source))]
          (let [data (data-of #(evidence/lift plane source
                                              (assoc supplement field
                                                     :laundered)))]
            (is (= :laundered-field (:kotobase.evidence/reason data)))
            (is (= #{field} (:fields data))))))
      (testing (str plane " refuses a field nobody asked for")
        (is (= :supplement-mismatch
               (reason #(evidence/lift plane source
                                       (assoc supplement :retry-budget 3)))))))))

(deftest a-governed-receipt-is-already-on-the-plane
  ;; the closing measurement: the supplement is empty, and it is empty because
  ;; the record answers everything rather than because nothing was asked
  (let [receipt (:execution/receipt
                 (governed/execute! (fixture/options (atom []))))]
    (is (= #{} (evidence/missing :governed-execution receipt)))
    (is (= receipt (evidence/lift :governed-execution receipt {})))
    (testing "and there is nothing left to supplement it with"
      ;; every field is carried, so every field is laundering — a lifted
      ;; governed receipt cannot be given a different cost on the way through
      (is (= :laundered-field
             (reason #(evidence/lift :governed-execution receipt
                                     {:cost {:dependent-hops 0 :requests 0
                                             :bytes 0 :cache-profile :cold}})))))))

(deftest an-authorised-effect-is-not-a-query-execution
  ;; refused rather than mapped: a version 1 receipt requires a plan digest
  ;; and a result root, and an effect admission has neither. Inventing them is
  ;; how an evidence plane stops being evidence
  (doseq [plane (keys evidence/effect-planes)]
    (let [data (data-of #(evidence/lift plane {:anything true} {}))]
      (is (= :not-a-query-execution (:kotobase.evidence/reason data)))
      (is (= plane (:plane data)))
      (is (string? (:because data)))
      (is (seq (:because data)))))
  (testing "and a plane nobody has written an adapter for is not silently empty"
    (is (= :unknown-plane (reason #(evidence/lift :something-else {} {}))))))
