(ns kotobase.causal-trust
  "Durable Kotobase projection for causal identity and authority receipts.

  This namespace is the temporary `ITransactionalStore` compatibility route.
  It stores attributed records and CIDs, not raw identity evidence. New causal
  persistence uses `kotobase.causal-commit`, whose basis and acknowledgement
  are immutable Kotobase commit CIDs.

  The disclosure read path that used to live here is gone. It admitted a
  query, evaluated it, and committed a receipt that answered one of the eight
  fields of a version 1 ExecutionReceipt; `kotobase.governed-read` is its
  successor and commits the receipt itself. What remains here is authority
  decision and identity persistence, which is a different subject — see
  `docs/ADR-evidence-plane.md`."
  (:require [grant.causal-trust :as trust]
            [identity.adapters.ledger :as identity-ledger]
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
