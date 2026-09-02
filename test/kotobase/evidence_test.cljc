(ns kotobase.evidence-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [kotobase.code-graph :as code-graph]
            [kotobase.effect-contract :as effect]
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

(def decision
  "The shape `grant.causal-trust/receipt` validates and `kotobase.causal-commit`
  commits. Only the parts a lift can read are spelled out."
  {:causal.receipt/id "bafy-decision"
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
                :authority/policy "bafy-policy"
                :authority/epoch 7
                :effect/action :execute
                :effect/resource "bafy-artifact"
                :code/lock "bafy-lock"
                :effect/granted #{:object/read}
                :outcome/roots ["bafy-output"]
                :cost {:dependent-hops 1 :requests 2 :bytes 512
                       :cache-profile :cold}
                :implementation/build "kotobase@lift"
                :signature "sig"}
               (evidence/missing plane source)))

(def admission-decision
  {:admission/allowed? true
   :admission/code nil
   :admission/action :execute
   :admission/resource "bafy-object"
   :admission/package-lock-cid "bafy-lock"
   :admission/requested #{:object/read}
   :admission/granted #{:object/read}
   :admission/missing #{}})

(def build-receipt
  {:cid "bafy-execution"
   :artifact-cid "bafy-artifact"
   :package-lock-cid "bafy-lock"
   :policy-cid "bafy-policy"
   :granted-effects #{:object/read}
   :output-root-cids ["bafy-output"]})

(deftest the-plane-count-is-a-partition-not-a-slogan
  (testing "every field of each contract is somebody's job"
    ;; derived from the contracts rather than restated, so a version 2 field
    ;; cannot appear without appearing here
    (doseq [[subject fields] [[:query-execution contract/receipt-keys]
                              [:authorised-effect effect/receipt-keys]]]
      (is (= fields (set/union (evidence/answerable subject)
                               (get evidence/adapter-supplied subject))))
      (is (empty? (set/intersection (evidence/answerable subject)
                                    (get evidence/adapter-supplied subject))))))
  (testing "and the five planes are sorted into two subjects"
    (is (= {:causal-decision :query-execution
            :code-graph-query :query-execution
            :governed-execution :query-execution
            :admission :authorised-effect
            :code-graph-execution :authorised-effect}
           (update-vals evidence/planes :subject))))
  (testing "and a plane nobody has written an adapter for is not silently empty"
    (is (= :unknown-plane (reason #(evidence/lift :something-else {} {}))))
    (is (= :unknown-plane (reason #(evidence/subject :something-else))))))

(deftest a-decision-receipt-answers-the-decision-and-almost-nothing-else
  (testing "what it carries"
    (is (= {:authority/decision :allow}
           (evidence/carried :causal-decision decision))))
  (testing "and what it cannot"
    ;; the outcome binds a row count when it binds anything, which is a fact
    ;; about how many rows there were rather than which ones — and an
    ;; authority decision has no result to name in the first place
    (is (= #{:request/digest :execution/manifest :query/plan-digest
             :result/root :cost :implementation/build :signature}
           (evidence/missing :causal-decision decision))))
  (testing "a denial does carry that there is no result"
    (let [denied (assoc decision :causal.receipt/decision
                        {:decision/status :deny})]
      (is (= {:authority/decision :deny :result/root nil}
             (evidence/carried :causal-decision denied)))
      (is (not (contains? (evidence/missing :causal-decision denied)
                          :result/root)))))
  (testing "and a record whose decision cannot be read is not lifted"
    (is (= :unreadable-source
           (reason #(evidence/carried :causal-decision
                                      (assoc decision
                                             :causal.receipt/decision
                                             {:decision/status :maybe})))))
    ;; a challenge is evidence still to be gathered, and version 1 has an
    ;; allow and a deny and no third
    (is (= :unreadable-source
           (reason #(evidence/carried :causal-decision
                                      (assoc decision
                                             :causal.receipt/decision
                                             {:decision/status :challenge})))))))

(deftest a-query-receipt-is-read-with-the-identity-that-binds-it
  (testing "the pair answers three fields a decision receipt cannot"
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
  (doseq [[plane source] [[:causal-decision decision]
                          [:code-graph-query code-graph-source]
                          [:admission admission-decision]
                          [:code-graph-execution build-receipt]]]
    (let [supplement (supplement-for plane source)
          lifted (evidence/lift plane source supplement)]
      (testing (str plane " lifts into a valid version 1 record")
        (let [{:keys [validate version version-key]}
              (get evidence/subjects (evidence/subject plane))]
          (is (= lifted (validate lifted)))
          (is (= version (get lifted version-key)))))
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

(deftest an-authorised-effect-is-evidence-of-a-different-subject
  ;; not refused any more, and not mapped onto a query execution either: an
  ;; admission has no plan digest and no served result, so it lifts onto the
  ;; contract that binds an action, a resource and the code lock it ran under
  (testing "an admission answers five fields and owes an outcome"
    (is (= {:authority/decision :allow
            :effect/action :execute
            :effect/resource "bafy-object"
            :code/lock "bafy-lock"
            :effect/granted #{:object/read}}
           (evidence/carried :admission admission-decision)))
    (is (contains? (evidence/missing :admission admission-decision)
                   :outcome/roots)))
  (testing "a refused one owes nothing, because there is nothing to name"
    (let [refused (assoc admission-decision
                         :admission/allowed? false
                         :admission/code :admission/capability
                         :admission/granted #{})]
      (is (= [] (:outcome/roots (evidence/carried :admission refused))))
      (is (not (contains? (evidence/missing :admission refused)
                          :outcome/roots)))))
  (testing "and a build answers what its existence means"
    ;; `:build` and `:allow` are constants for the same reason the query
    ;; receipt's decision is one: the record exists only because an artifact
    ;; was built from an admitted code graph
    (is (= {:authority/decision :allow
            :effect/action :build
            :effect/resource "bafy-artifact"
            :code/lock "bafy-lock"
            :effect/granted #{:object/read}
            :authority/policy "bafy-policy"
            :outcome/roots ["bafy-output"]}
           (evidence/carried :code-graph-execution build-receipt))))
  (testing "a record missing what the plane is supposed to have is not lifted"
    (is (= :unreadable-source
           (reason #(evidence/carried :admission
                                      (dissoc admission-decision
                                              :admission/package-lock-cid)))))
    (is (= :unreadable-source
           (reason #(evidence/carried :code-graph-execution
                                      (assoc build-receipt :output-root-cids
                                             [])))))))
