(ns kotobase.causal-trust
  "Durable Kotobase projection for causal identity and authority receipts.

  This namespace is the temporary `ITransactionalStore` compatibility route.
  It stores attributed records and CIDs, not raw identity evidence. New causal
  persistence uses `kotobase.causal-commit`, whose basis and acknowledgement
  are immutable Kotobase commit CIDs."
  (:require [grant.causal-trust :as trust]
            [identity.adapters.ledger :as identity-ledger]
            [kotobase.guarded :as guarded]
            [kotobase.store :as store]))

(def identity-stream "causal-identity")
(def decision-stream "causal-decisions")

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- reject! [reason data]
  (throw (ex-info "causal trust persistence rejected"
                  (assoc data :kotobase.causal-trust/reason reason))))

(defn- require-transactional! [backend]
  (when-not (store/transactional-store? backend)
    (reject! :transactional-store-required {}))
  backend)

(defn- durable-transaction!
  [backend stream receipt-cid expected-revision events]
  (require-transactional! backend)
  (when-not (non-empty-string? receipt-cid)
    (reject! :invalid-receipt-cid {}))
  (when-not (and (integer? expected-revision)
                 (not (neg? expected-revision)))
    (reject! :invalid-expected-revision {}))
  (when-not (and (vector? events) (seq events) (every? map? events))
    (reject! :invalid-events {}))
  (let [request {:tx-id receipt-cid
                 :expected-revision expected-revision
                 :puts []
                 :deletes []
                 :appends (mapv (fn [event] [stream event]) events)}
        receipt (store/-transact backend request)
        appends (:appends receipt)
        persisted-events (mapv (fn [[persisted-stream event]]
                                 (when-not (= stream persisted-stream)
                                   (reject! :transaction-stream-mismatch
                                            {:expected stream
                                             :actual persisted-stream}))
                                 (dissoc event :seq))
                               appends)]
    (when-not (= receipt-cid (:tx-id receipt))
      (reject! :transaction-id-mismatch
               {:expected receipt-cid :actual (:tx-id receipt)}))
    (when-not (= events persisted-events)
      (reject! :transaction-events-mismatch
               {:expected events :actual persisted-events}))
    (when-not (and (integer? (:revision receipt))
                   (> (:revision receipt) expected-revision))
      (reject! :invalid-store-revision {:receipt receipt}))
    {:receipt/durable? true
     :receipt/cid receipt-cid
     :receipt/store-revision (:revision receipt)}))

(defn identity-ledger
  "Adapt a transactional Kotobase store to identity's append-only ledger.

  `:tx/receipt-cid` is both the idempotency key and the durable acknowledgement
  returned to the caller. `:tx/expected-revision` prevents a transition from
  being committed against a different causal basis."
  [backend]
  (require-transactional! backend)
  (reify identity-ledger/ILedger
    (transact! [_ datoms opts]
      (when-not (= #{:tx/receipt-cid :tx/expected-revision}
                   (set (keys opts)))
        (reject! :invalid-identity-transaction-options {}))
      (let [receipt-cid (:tx/receipt-cid opts)
            events (mapv (fn [datom]
                           {:causal.record/type :identity-datom
                            :causal.record/receipt-cid receipt-cid
                            :causal.record/datom datom})
                         datoms)]
        (durable-transaction! backend identity-stream receipt-cid
                              (:tx/expected-revision opts) events)))))

(defn persist-decision!
  "Validate and append a secret-free causal authority receipt atomically."
  [backend receipt expected-revision]
  (let [receipt (trust/receipt receipt)]
    (durable-transaction! backend decision-stream
                          (:causal.receipt/id receipt)
                          expected-revision [receipt])))

(def disclosure-template-keys
  #{:causal.receipt/intent-cid :causal.receipt/principal
    :causal.receipt/epoch-cid :causal.receipt/policy-cid
    :causal.receipt/basis-cid :causal.receipt/claim-cids
    :causal.receipt/decision})

(defn disclosure-plan
  "Validate the authority bindings before a protected query is evaluated.

  The returned plan is persistence-neutral and is shared by the legacy
  transactional-store sink and the canonical CID commit sink."
  [template receipt-cid-fn at]
  (when-not (and (map? template)
                 (= disclosure-template-keys (set (keys template))))
    (reject! :invalid-disclosure-template {}))
  (when-not (= :allow (get-in template
                              [:causal.receipt/decision :decision/status]))
    (reject! :disclosure-not-allowed {}))
  (when-not (= (:causal.receipt/principal template)
               (get-in template
                       [:causal.receipt/decision
                        :decision/runtime-capability-spec
                        :capability/principal]))
    (reject! :principal-decision-mismatch {}))
  (when-not (ifn? receipt-cid-fn)
    (reject! :missing-receipt-cid-function {}))
  (when-not (non-empty-string? at)
    (reject! :invalid-receipt-time {}))
  (trust/receipt
   (assoc template
          :causal.receipt/id "urn:kotobase:receipt:preflight"
          :causal.receipt/outcome {:outcome/status :pending}
          :causal.receipt/at at))
  {:template template :receipt-cid-fn receipt-cid-fn :at at})

(defn disclosure-receipt
  "Bind an evaluated row count to a validated disclosure plan."
  [{:keys [template receipt-cid-fn at] :as plan}
   {:keys [compiled row-count] :as execution}]
  (when-not (= #{:template :receipt-cid-fn :at} (set (keys plan)))
    (reject! :invalid-disclosure-plan {}))
  ;; `:rows` is present because the gateway hands the served rows to its sink
  ;; (see `kotobase.authorized-query/execute!`); this receipt binds the count,
  ;; not the rows, and deliberately does not read them
  (when-not (= #{:compiled :rows :row-count} (set (keys execution)))
    (reject! :invalid-read-execution {}))
  (when-not (and (integer? row-count) (not (neg? row-count)))
    (reject! :invalid-row-count {}))
  (let [provenance (:provenance compiled)]
    (when-not (= (:causal.receipt/basis-cid template)
                 (:basis provenance))
      (reject! :query-basis-mismatch {}))
    (when-not (= (:causal.receipt/policy-cid template)
                 (:policy-cid provenance))
      (reject! :query-policy-mismatch {})))
  (let [without-id (assoc template
                          :causal.receipt/outcome
                          {:outcome/status :disclosed
                           :outcome/row-count row-count}
                          :causal.receipt/at at)
        receipt-cid (receipt-cid-fn without-id)]
    (when-not (non-empty-string? receipt-cid)
      (reject! :invalid-receipt-cid {}))
    (trust/receipt (assoc without-id :causal.receipt/id receipt-cid))))

(defn disclosure-receipt-sink
  "Build the only receipt sink accepted by `read!`.

  The host supplies a canonical CID function. The sink binds the guarded-query
  provenance and row count into the same epoch, policy, basis, and evaluator
  claims as the authority decision before making one durable append."
  [backend {:keys [template expected-revision receipt-cid-fn at] :as disclosure}]
  (require-transactional! backend)
  (when-not (= #{:template :expected-revision :receipt-cid-fn :at}
               (set (keys disclosure)))
    (reject! :invalid-disclosure-options {}))
  (when-not (and (integer? expected-revision)
                 (not (neg? expected-revision)))
    (reject! :invalid-expected-revision {}))
  (let [plan (disclosure-plan template receipt-cid-fn at)]
    (fn [execution]
      (persist-decision! backend
                         (disclosure-receipt plan execution)
                         expected-revision))))

(defn require-query-capability! [request]
  (let [query (:query request)
        capability (get-in request
                           [:disclosure :template :causal.receipt/decision
                            :decision/runtime-capability-spec])
        query-scope (:scope query)]
    (when-not (= :object/read (:capability/action capability))
      (reject! :read-capability-required {}))
    (when-not (= (:resources query-scope) (:capability/resource capability))
      (reject! :query-resource-mismatch {}))
    (when-not (= (:tenant query-scope) (:capability/tenant capability))
      (reject! :query-tenant-mismatch {}))))

(defn read!
  "Admit, evaluate, durably record, then return a protected read.

  There is deliberately no caller-provided `:receipt!` escape hatch."
  [{:keys [store disclosure] :as request}]
  (require-query-capability! request)
  (guarded/read!
   (assoc (dissoc request :store :disclosure :receipt!)
          :receipt! (disclosure-receipt-sink store disclosure))))
