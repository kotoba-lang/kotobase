(ns kotobase.governed-effect-test
  "The effect contract, with a caller.

  A contract with no caller is a schema. What is being checked is that the
  record a governed effect leaves is about the effect that ran: the granted
  set the admission actually computed, the outcome the effect actually named,
  and a cost read after it."
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.effect-contract :as contract]
            [kotobase.evidence :as evidence]
            [kotobase.execution-identity :as id]
            [kotobase.governed-effect :as governed]))

(def package-receipt
  {:package/verified? true :package/lock-cid "bafy-lock"})

(def request
  {:request/version 1
   :principal "did:key:alice"
   :tenant "acme"
   :effect/action :execute
   :effect/resource "bafy-object"
   :code/lock "bafy-lock"
   :effect/requested #{:object/read}
   :authority/policy "bafy-policy"
   :authority/epoch 7
   :nonce "nonce-1"
   :expires-at "2026-09-02T15:00:00Z"})

(defn proof [record] (str "sig:" (id/value-cid (dissoc record :signature))))

(defn- verify [{:keys [payload-cid signature]}]
  (= signature (str "sig:" payload-cid)))

(defn nonce-ledger []
  (let [seen (atom #{})]
    (fn [nonce] (if (contains? @seen nonce)
                  false
                  (do (swap! seen conj nonce) true)))))

(defn options
  "The whole composition, with every host answer recorded."
  [journal & {:as overrides}]
  (merge
   {:request request
    :authority {:now "2026-09-02T14:30:00Z"
                :epoch 7
                :consume-nonce! (nonce-ledger)}
    :value-cid id/value-cid
    :verify verify
    :sign proof
    :cost (fn [] {:dependent-hops 1 :requests 2 :bytes 512
                  :cache-profile :cold})
    :implementation/build "kotobase@effect-test"
    :commit! (fn [receipt]
               (swap! journal conj [:commit receipt])
               {:receipt/durable? true :receipt/cid "bafy-effect-receipt"})
    :audit! (fn [decision]
              (swap! journal conj [:audit decision])
              {:audit/durable? true :audit/receipt-id "bafy-audit"})
    :effect! (fn [_]
               (swap! journal conj [:effect])
               {:roots ["bafy-output"] :result :done})
    :cid-verified? true
    :package-receipt package-receipt
    :delegated-effects #{:object/read}
    :local-policy-effects #{:object/read}}
   overrides))

(defn- reason [f]
  (let [data (ex-data (try (f) nil
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs ExceptionInfo) e e)))]
    (or (:kotobase.governed-effect/reason data)
        (:kotobase.authority-window/reason data)
        (:kotobase.effect-contract/reason data)
        (:type data))))

(defn- steps [journal] (mapv first @journal))

(defn- committed [journal]
  (->> @journal (filter #(= :commit (first %))) (map second) vec))

(deftest an-effect-leaves-a-record-of-what-it-did
  (let [journal (atom [])
        {:keys [result] :as outcome} (governed/execute! (options journal))
        receipt (:effect/receipt outcome)]
    (is (= :done result))
    (testing "the audit came before the effect, the receipt after it"
      ;; `kotobase.admission`'s rule survives: an effect is not reversible, so
      ;; its admission is recorded before it runs. The outcome does not exist
      ;; until afterwards, so the evidence is recorded after
      (is (= [:audit :effect :commit] (steps journal))))
    (testing "and the record is about this effect"
      (is (= (id/value-cid request) (:request/digest receipt)))
      (is (= :allow (:authority/decision receipt)))
      (is (= #{:object/read} (:effect/granted receipt)))
      (is (= ["bafy-output"] (:outcome/roots receipt)))
      (is (= (proof (dissoc receipt :signature)) (:signature receipt)))
      (is (= receipt (contract/validate-receipt! receipt))))
    (testing "and it is already on the evidence plane"
      (is (= #{} (evidence/missing :governed-effect receipt))))))

(deftest a-refusal-is-committed-and-the-effect-never-runs
  (let [journal (atom [])
        error (reason #(governed/execute!
                        (options journal
                                 :delegated-effects #{}
                                 :local-policy-effects #{})))]
    (is (= :admission-denied error))
    (is (= [:audit :commit] (steps journal)))
    (let [receipt (first (committed journal))]
      (is (= :deny (:authority/decision receipt)))
      (is (= [] (:outcome/roots receipt)))
      ;; the granted set is what the intersection actually produced, which is
      ;; the reason for the refusal rather than a restatement of it
      (is (= #{} (:effect/granted receipt))))))

(deftest an-audit-that-will-not-persist-stops-everything
  ;; not an admission decision, so no receipt: the effect never ran and there
  ;; is nothing to be evidence of
  (let [journal (atom [])]
    (is (= :kotobase/audit-denied
           (reason #(governed/execute!
                     (options journal :audit! (fn [_] {:audit/durable? false}))))))
    (is (= [] (committed journal)))
    (is (= [] (steps journal)))))

(deftest the-record-must-name-the-bytes-that-ran
  (let [journal (atom [])]
    (testing "the envelope's code lock is the package being admitted"
      (is (= :code-lock-mismatch
             (reason #(governed/execute!
                       (options journal
                                :package-receipt
                                (assoc package-receipt :package/lock-cid
                                       "bafy-other-lock")))))))
    (testing "and an effect that named no outcome has not said what it did"
      (is (= :effect-named-no-outcome
             (reason #(governed/execute!
                       (options journal
                                :effect! (fn [_] {:roots [] :result :done}))))))
      (is (= :invalid-effect-result
             (reason #(governed/execute!
                       (options journal :effect! (fn [_] :done)))))))
    (is (= [] (committed journal)))))

(deftest runtime-authority-applies-to-effects-too
  (let [journal (atom [])
        ledger (nonce-ledger)]
    (testing "an expired request does not run and does not spend its nonce"
      (is (= :request-expired
             (reason #(governed/execute!
                       (options journal
                                :authority {:now "2026-09-02T15:00:00Z"
                                            :epoch 7
                                            :consume-nonce! ledger})))))
      (is (true? (ledger "nonce-1"))))
    (testing "a superseded epoch is refused"
      (is (= :authority-epoch-revoked
             (reason #(governed/execute!
                       (options journal
                                :authority {:now "2026-09-02T14:30:00Z"
                                            :epoch 8
                                            :consume-nonce! (nonce-ledger)}))))))
    (testing "and a nonce is spent once"
      (let [opts (options journal :authority {:now "2026-09-02T14:30:00Z"
                                              :epoch 7
                                              :consume-nonce! (nonce-ledger)})]
        (is (= :done (:result (governed/execute! opts))))
        (is (= :nonce-replayed (reason #(governed/execute! opts))))))
    (testing "none of which reached the effect"
      (is (= 1 (count (filter #{:effect} (steps journal))))))))

(deftest the-composition-has-no-optional-parts
  (let [journal (atom [])
        opts (options journal)]
    (doseq [k (keys opts)]
      (is (= :invalid-effect-options
             (reason #(governed/execute! (dissoc opts k))))
          (str "removing " k " should refuse the effect")))
    (is (= :invalid-effect-options
           (reason #(governed/execute! (assoc opts :retry-budget 3)))))
    (is (= :missing-host-function
           (reason #(governed/execute! (assoc opts :sign "not-a-function")))))
    (is (= [] @journal))))
