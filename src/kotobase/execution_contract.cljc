(ns kotobase.execution-contract
  "Versioned, codec-neutral records for one authorized query execution.

  Validation is deliberately exact and fail closed. Protocol adapters may
  translate their own ASTs into these records, but they may not add implicit
  defaults or protocol-specific fields to the signed contract."
  (:require [clojure.set :as set]))

(def version 1)

(def ^:private manifest-keys
  #{:execution/version :data/commit :authority/policy :authority/epoch
    :location/manifest :schema/root :parent :issued-at :signature})

(def ^:private request-keys
  #{:request/version :principal :tenant :graph :operation :query/digest
    :base/commit :authority/policy :authority/epoch :nonce :expires-at})

(def ^:private receipt-keys
  #{:receipt/version :request/digest :execution/manifest :query/plan-digest
    :authority/decision :result/root :cost :implementation/build :signature})

(def ^:private cost-keys
  #{:dependent-hops :requests :bytes :cache-profile})

(def ^:private forbidden-keys
  #{:query/text :query/ast :query/protocol :protocol :backend :provider
    :credential :credential/raw :token :authorization})

(defn- reject! [reason data]
  (throw (ex-info "execution contract rejected"
                  (assoc data :kotobase.execution-contract/reason reason))))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- exact-keys! [record expected record-type]
  (when-not (map? record)
    (reject! :not-a-map {:record/type record-type}))
  (let [actual (set (keys record))]
    (when-not (= expected actual)
      (reject! :invalid-keys
               {:record/type record-type
                :missing (set/difference expected actual)
                :unexpected (set/difference actual expected)}))))

(defn- contains-forbidden-field? [value]
  (cond
    (map? value) (or (some forbidden-keys (keys value))
                     (some contains-forbidden-field? (vals value)))
    (coll? value) (some contains-forbidden-field? value)
    :else false))

(defn- no-forbidden-fields! [value]
  (when (contains-forbidden-field? value)
    (reject! :forbidden-field {}))
  value)

(defn validate-manifest!
  "Return a valid ExecutionManifest, otherwise throw ExceptionInfo."
  [manifest]
  (exact-keys! manifest manifest-keys :execution-manifest)
  (no-forbidden-fields! manifest)
  (when-not (and (= version (:execution/version manifest))
                 (non-empty-string? (:data/commit manifest))
                 (non-empty-string? (:authority/policy manifest))
                 (nat-int? (:authority/epoch manifest))
                 (non-empty-string? (:location/manifest manifest))
                 (non-empty-string? (:schema/root manifest))
                 (or (nil? (:parent manifest))
                     (non-empty-string? (:parent manifest)))
                 (non-empty-string? (:issued-at manifest))
                 (some? (:signature manifest)))
    (reject! :invalid-manifest {}))
  manifest)

(defn validate-request!
  "Return a valid RequestEnvelope, otherwise throw ExceptionInfo."
  [request]
  (exact-keys! request request-keys :request-envelope)
  (no-forbidden-fields! request)
  (when-not (and (= version (:request/version request))
                 (non-empty-string? (:principal request))
                 (non-empty-string? (:tenant request))
                 (non-empty-string? (:graph request))
                 (keyword? (:operation request))
                 (non-empty-string? (:query/digest request))
                 (non-empty-string? (:base/commit request))
                 (non-empty-string? (:authority/policy request))
                 (nat-int? (:authority/epoch request))
                 (non-empty-string? (:nonce request))
                 (non-empty-string? (:expires-at request)))
    (reject! :invalid-request {}))
  request)

(defn validate-receipt!
  "Return a valid ExecutionReceipt, otherwise throw ExceptionInfo."
  [receipt]
  (exact-keys! receipt receipt-keys :execution-receipt)
  (no-forbidden-fields! receipt)
  (let [cost (:cost receipt)
        decision (:authority/decision receipt)
        result-root (:result/root receipt)]
    (when-not (and (= version (:receipt/version receipt))
                   (non-empty-string? (:request/digest receipt))
                   (non-empty-string? (:execution/manifest receipt))
                   (non-empty-string? (:query/plan-digest receipt))
                   (contains? #{:allow :deny} decision)
                   (if (= :allow decision)
                     (non-empty-string? result-root)
                     (nil? result-root))
                   (map? cost)
                   (= cost-keys (set (keys cost)))
                   (every? nat-int?
                           ((juxt :dependent-hops :requests :bytes) cost))
                   (keyword? (:cache-profile cost))
                   (non-empty-string? (:implementation/build receipt))
                   (some? (:signature receipt)))
      (reject! :invalid-receipt {})))
  receipt)

(defn validate-execution!
  "Validate all three records and their cross-record invariants.

  `manifest-cid` and `request-digest` are calculated by the host's canonical
  codec and supplied explicitly, keeping this namespace independent of an
  encoding or hash implementation."
  [{:keys [manifest manifest-cid request request-digest receipt] :as bundle}]
  (when-not (= #{:manifest :manifest-cid :request :request-digest :receipt}
               (set (keys bundle)))
    (reject! :invalid-bundle-keys {}))
  (validate-manifest! manifest)
  (validate-request! request)
  (validate-receipt! receipt)
  (when-not (and (non-empty-string? manifest-cid)
                 (non-empty-string? request-digest)
                 (= (:base/commit request) (:data/commit manifest))
                 (= (:authority/policy request) (:authority/policy manifest))
                 (= (:authority/epoch request) (:authority/epoch manifest))
                 (= manifest-cid (:execution/manifest receipt))
                 (= request-digest (:request/digest receipt)))
    (reject! :cross-record-mismatch {}))
  bundle)
