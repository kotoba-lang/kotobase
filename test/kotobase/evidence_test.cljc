(ns kotobase.evidence-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
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

(def code-graph-query
  "A plane this repository does not own, defined by its caller.

  `kotoba-lang/code-graph` depends on this repository, so a carrier for its
  records cannot live here — it would be either a dependency cycle or a copy
  of a shape, and a copy of a shape is what this namespace exists to stop
  being necessary. What is exercised here is the extension point: the same
  lift rule, applied to a plane defined outside the registry."
  {:subject :query-execution
   :carry (fn [{:keys [result-cid plan-cid]}]
            {:authority/decision :allow
             :result/root result-cid
             :query/plan-digest plan-cid})})

(def foreign-source {:result-cid "bafy-result" :plan-cid "bafy-plan"})

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

(def build-plane
  "The other plane `kotoba-lang/code-graph` owns, defined by its caller."
  {:subject :authorised-effect
   :carry (fn [record]
            {:authority/decision :allow
             :effect/action :build
             :effect/resource (:artifact-cid record)
             :code/lock (:package-lock-cid record)
             :effect/granted (:granted-effects record)
             :authority/policy (:policy-cid record)
             :outcome/roots (:output-root-cids record)})})

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
  (testing "and every plane written here is sorted into one of the two"
    ;; only the planes this repository writes. `kotoba-lang/code-graph`
    ;; depends on this one and registers its own
    (is (= {:causal-decision :query-execution
            :governed-execution :query-execution
            :governed-effect :authorised-effect
            :admission :authorised-effect}
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

(deftest a-plane-may-be-defined-by-whoever-writes-its-records
  (testing "an explicit definition works exactly like a registered one"
    (is (= :query-execution (evidence/subject code-graph-query)))
    (is (= {:authority/decision :allow
            :result/root "bafy-result"
            :query/plan-digest "bafy-plan"}
           (evidence/carried code-graph-query foreign-source)))
    (is (= #{:request/digest :execution/manifest :cost
             :implementation/build :signature}
           (evidence/missing code-graph-query foreign-source))))
  (testing "and it is checked as exactly as a registered one"
    ;; a definition that can be handed in is a place a wrong subject could be
    ;; handed in with it
    (is (= :unknown-subject
           (reason #(evidence/carried (assoc code-graph-query :subject :other)
                                      foreign-source))))
    (is (= :missing-carrier
           (reason #(evidence/carried (assoc code-graph-query :carry nil)
                                      foreign-source))))
    (is (= :invalid-plane
           (reason #(evidence/carried (dissoc code-graph-query :carry)
                                      foreign-source))))
    (is (= :invalid-plane (reason #(evidence/carried "code-graph-query" {}))))
    (is (= :unknown-plane (reason #(evidence/carried :something-else {}))))))

(deftest a-lift-is-exactly-as-complete-as-the-supplement-it-demands
  (doseq [[plane source] [[:causal-decision decision]
                          [code-graph-query foreign-source]
                          [:admission admission-decision]
                          [build-plane build-receipt]]]
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
  ;; not refused, and not mapped onto a query execution either: an admission
  ;; has no plan digest and no served result, so it lifts onto the contract
  ;; that binds an action, a resource and the code lock it ran under
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
  (testing "and a build, on the plane its own repository defines"
    (is (= 5 (count (evidence/missing build-plane build-receipt)))))
  (testing "a record missing what the plane is supposed to have is not lifted"
    (is (= :unreadable-source
           (reason #(evidence/carried :admission
                                      (dissoc admission-decision
                                              :admission/package-lock-cid)))))))
