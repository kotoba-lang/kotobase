(ns kotobase.governed-execution-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.governed-execution :as governed]))

(def schema
  {:invoice/id {:class :public}
   :invoice/amount {:class :internal}
   :invoice/payer-did {:class :personal :erasure-scope :person}})

(def grant {:granted #{:public :internal} :scopes #{}})

(def query
  {:find [:invoice/id :invoice/amount]
   :where [[:invoice/tenant-id "acme"]]
   :scope {:tenant "acme" :resources #{"INV-42"} :purpose :payment-review
           :basis "bafy-basis"}
   :limit 20})

(def served [{:invoice/id "INV-42" :invoice/amount 5000}])

(defn authorize [q]
  {:allowed? true :projection (set (:find q))
   :basis "bafy-basis" :policy-cid "bafy-policy"})

(def request
  {:request/version 1
   :principal "did:key:z6MkCaller"
   :tenant "acme"
   :graph "invoices"
   :operation :read
   :query/digest "bafy-query"
   :base/commit "bafy-basis"
   :authority/policy "bafy-policy"
   :authority/epoch 7
   :nonce "nonce-1"
   :expires-at "2026-09-02T15:00:00Z"})

(def manifest
  {:execution/version 1
   :data/commit "bafy-basis"
   :authority/policy "bafy-policy"
   :authority/epoch 7
   :location/manifest "bafy-packs"
   :schema/root "bafy-schema"
   :parent nil
   :issued-at "2026-09-02T14:00:00Z"
   :signature "sig-manifest"})

(defn nonce-ledger
  "A ledger that answers `true` once per nonce and `false` after that."
  []
  (let [seen (atom #{})]
    (fn [nonce]
      (if (contains? @seen nonce)
        false
        (do (swap! seen conj nonce) true)))))

(defn options
  "The whole composition, with every host answer recorded so a test can ask
  what actually happened rather than what was returned.

  Public because `kotobase.governed-execution-commit-test` runs this same
  fixture against a real database and the canonical commit sink; if the two
  suites drifted apart, the portable one could stay green about a shape the
  storage one no longer accepts."
  [journal & {:as overrides}]
  (merge
   {:request request
    :request-digest "bafy-request"
    :manifest manifest
    :manifest-cid "bafy-manifest"
    :authority {:now "2026-09-02T14:30:00Z"
                :epoch 7
                :consume-nonce! (nonce-ledger)}
    :query-digest (fn [_] "bafy-query")
    :plan-digest (fn [compiled] (if compiled "bafy-plan" "bafy-no-plan"))
    :cost (fn [] {:dependent-hops 1 :requests 2 :bytes 512
                  :cache-profile :cold})
    :implementation/build "kotobase@test"
    :result-root (fn [rows] (str "bafy-rows-" (count rows)))
    :sign (fn [unsigned] (str "sig-" (name (:authority/decision unsigned))))
    :commit! (fn [receipt]
               (swap! journal conj [:commit receipt])
               {:receipt/durable? true :receipt/cid "bafy-exec-receipt"})
    :authorize! authorize
    :schema schema
    :grant grant
    :query query
    :evaluate! (fn [_ _]
                 (swap! journal conj [:evaluate])
                 served)}
   overrides))

(defn- reason
  "Whichever layer refused. A caller should not have to know which said no."
  [f]
  (let [data (ex-data (try (f) nil
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs ExceptionInfo) e e)))]
    (or (:kotobase.governed-execution/reason data)
        (:kotobase.guarded/reason data)
        (:kotobase.query/reason data))))

(defn- steps [journal] (mapv first @journal))

(defn- committed [journal]
  (->> @journal (filter #(= :commit (first %))) (map second) vec))

(deftest an-execution-produces-its-evidence-before-it-produces-rows
  (let [journal (atom [])
        {:keys [rows] :as result} (governed/execute! (options journal))
        receipt (:execution/receipt result)]
    (is (= served rows))
    (testing "and the receipt was committed before the rows came back"
      ;; the ordering is the property. A receipt written after the caller has
      ;; the rows is a log entry, not a precondition
      (is (= [:evaluate :commit] (steps journal))))
    (testing "and it is about this execution, not about a shape"
      (is (= :allow (:authority/decision receipt)))
      ;; derived from the rows that were actually served
      (is (= "bafy-rows-1" (:result/root receipt)))
      (is (= "bafy-request" (:request/digest receipt)))
      (is (= "bafy-manifest" (:execution/manifest receipt)))
      (is (= "bafy-plan" (:query/plan-digest receipt)))
      (is (= "sig-allow" (:signature receipt)))
      (is (= {:dependent-hops 1 :requests 2 :bytes 512 :cache-profile :cold}
             (:cost receipt))))
    (testing "and the guarded provenance still names the same receipt"
      (is (= "bafy-exec-receipt" (get-in result [:provenance :receipt-cid]))))))

(deftest rows-are-withheld-when-the-receipt-is-not-durable
  (let [journal (atom [])]
    (is (= :execution-receipt-not-durable
           (reason #(governed/execute!
                     (options journal
                              :commit! (fn [_] {:receipt/durable? false}))))))
    ;; the query ran — that is unavoidable, the root is a fact about its rows
    ;; — but nothing was returned
    (is (= [:evaluate] (steps journal)))))

(deftest an-envelope-must-name-the-query-that-actually-runs
  (let [journal (atom [])]
    (testing "the semantic digest"
      (is (= :query-digest-mismatch
             (reason #(governed/execute!
                       (options journal
                                :query-digest (fn [_] "bafy-other-query")))))))
    (testing "the tenant"
      (is (= :tenant-mismatch
             (reason #(governed/execute!
                       (options journal
                                :request (assoc request :tenant "other")))))))
    (testing "the immutable basis"
      (is (= :basis-mismatch
             (reason #(governed/execute!
                       (options journal
                                :request (assoc request
                                                :base/commit "bafy-elsewhere"
                                                :query/digest "bafy-query")
                                :manifest (assoc manifest :data/commit
                                                 "bafy-elsewhere")))))))
    (testing "and the policy the compiler actually applied"
      (is (= :policy-mismatch
             (reason #(governed/execute!
                       (options journal
                                :authorize!
                                (fn [q] (assoc (authorize q)
                                               :policy-cid "bafy-other-policy"))
                                :manifest manifest))))))
    (testing "none of which ran a query"
      ;; the policy-mismatch case is decided after compilation but before
      ;; evaluation, so nothing here reached the evaluator
      (is (= [] (steps journal))))))

(deftest expiry-is-decided-against-the-clock-not-against-the-field-existing
  (let [journal (atom [])
        ledger (nonce-ledger)]
    (is (= :request-expired
           (reason #(governed/execute!
                     (options journal
                              :authority {:now "2026-09-02T15:00:00Z"
                                          :epoch 7
                                          :consume-nonce! ledger})))))
    (testing "and an expired request does not spend its nonce"
      ;; otherwise a clock-skewed retry of a legitimate request is refused as
      ;; a replay of itself
      (is (true? (ledger "nonce-1"))))
    (testing "a subsecond margin is a margin"
      ;; the naive string compare is wrong exactly here: "…:00Z" sorts after
      ;; "…:00.5Z", so an unpadded comparison calls this expired
      (is (= served
             (:rows (governed/execute!
                     (options journal
                              :request (assoc request :expires-at
                                              "2026-09-02T14:30:00.5Z")
                              :authority {:now "2026-09-02T14:30:00Z"
                                          :epoch 7
                                          :consume-nonce! (nonce-ledger)}))))))
    (testing "and it is still a margin in the other direction"
      (is (= :request-expired
             (reason #(governed/execute!
                       (options journal
                                :request (assoc request :expires-at
                                                "2026-09-02T14:30:00Z")
                                :authority {:now "2026-09-02T14:30:00.5Z"
                                            :epoch 7
                                            :consume-nonce!
                                            (nonce-ledger)}))))))))

(deftest an-instant-this-namespace-cannot-order-is-refused
  (testing "the format it can order"
    (is (= "2026-09-02T14:30:00.000000000"
           (governed/instant-key "2026-09-02T14:30:00Z")))
    (is (= "2026-09-02T14:30:00.500000000"
           (governed/instant-key "2026-09-02T14:30:00.5Z"))))
  (testing "and the ones it cannot"
    ;; an offset is orderable, but not by comparing these strings, so it is
    ;; refused rather than compared wrongly
    (is (nil? (governed/instant-key "2026-09-02T14:30:00+09:00")))
    (is (nil? (governed/instant-key "2026-09-02T14:30:00z")))
    (is (nil? (governed/instant-key "2026-09-02T14:30Z")))
    (is (nil? (governed/instant-key "2026-09-02")))
    (is (nil? (governed/instant-key nil))))
  (testing "and an unorderable clock refuses the execution"
    (is (= :invalid-now
           (reason #(governed/execute!
                     (options (atom [])
                              :authority {:now "2026-09-02T14:30:00+09:00"
                                          :epoch 7
                                          :consume-nonce! (nonce-ledger)})))))))

(deftest an-epoch-the-host-has-superseded-is-not-a-current-authority
  (is (= :authority-epoch-revoked
         (reason #(governed/execute!
                   (options (atom [])
                            :authority {:now "2026-09-02T14:30:00Z"
                                        :epoch 8
                                        :consume-nonce! (nonce-ledger)}))))))

(deftest a-nonce-is-spent-once
  (let [journal (atom [])
        ledger (nonce-ledger)
        opts (options journal :authority {:now "2026-09-02T14:30:00Z"
                                          :epoch 7
                                          :consume-nonce! ledger})]
    (is (= served (:rows (governed/execute! opts))))
    (is (= :nonce-replayed (reason #(governed/execute! opts))))
    (testing "and a ledger that cannot answer has not said the nonce is fresh"
      ;; nil, false, and a Promise all mean `I did not confirm this`
      (is (= :nonce-replayed
             (reason #(governed/execute!
                       (options journal
                                :authority {:now "2026-09-02T14:30:00Z"
                                            :epoch 7
                                            :consume-nonce! (fn [_] nil)}))))))
    (testing "and a missing ledger is a refusal, not an allowance"
      (is (= :invalid-authority
             (reason #(governed/execute!
                       (options journal
                                :authority {:now "2026-09-02T14:30:00Z"
                                            :epoch 7})))))
      (is (= :missing-nonce-ledger
             (reason #(governed/execute!
                       (options journal
                                :authority {:now "2026-09-02T14:30:00Z"
                                            :epoch 7
                                            :consume-nonce! "not-a-function"}))))))))

(deftest a-refusal-is-as-durable-as-a-disclosure
  (let [journal (atom [])
        denied (fn [q] (assoc (authorize q) :allowed? false))]
    (is (= :authority-denied
           (reason #(governed/execute! (options journal :authorize! denied)))))
    (let [receipts (committed journal)]
      (is (= 1 (count receipts)))
      (let [receipt (first receipts)]
        (is (= :deny (:authority/decision receipt)))
        ;; a denial has no result, and the contract refuses a root on one
        (is (nil? (:result/root receipt)))
        (is (= "bafy-no-plan" (:query/plan-digest receipt)))
        (is (= "sig-deny" (:signature receipt)))))
    (testing "and the evaluator never ran"
      (is (= [:commit] (steps journal))))))

(deftest a-crash-is-not-an-authority-decision
  (let [journal (atom [])
        boom (fn [_ _] (throw (ex-info "provider unreachable" {:io/error true})))]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                          #"provider unreachable"
                          (governed/execute! (options journal :evaluate! boom))))
    (testing "so nothing was recorded as denied"
      ;; recording it as `:deny` would put a policy decision in the evidence
      ;; plane for something policy never decided
      (is (= [] (committed journal))))))

(deftest a-root-the-host-could-not-compute-is-not-a-result
  (let [journal (atom [])]
    (is (= :invalid-result-root
           (reason #(governed/execute!
                     (options journal :result-root (fn [_] ""))))))
    (is (= [] (committed journal)))))

(deftest the-composition-has-no-optional-parts
  (let [journal (atom [])
        opts (options journal)]
    (doseq [k (keys opts)]
      (is (= :invalid-execution-options
             (reason #(governed/execute! (dissoc opts k))))
          (str "removing " k " should refuse the execution")))
    (is (= :invalid-execution-options
           (reason #(governed/execute! (assoc opts :retry-budget 3)))))
    (testing "and no host answer may be missing"
      (is (= :missing-host-function
             (reason #(governed/execute! (assoc opts :sign "not-a-function"))))))
    (is (= [] @journal))))
