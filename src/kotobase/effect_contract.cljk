(ns kotobase.effect-contract
  "Versioned, codec-neutral records for one authorised effect.

  `kotobase.execution-contract` binds one *query* execution: a plan digest and
  a result root are required fields. Two of this repository's receipt planes
  are not that. `kotobase.admission` decides whether an effect thunk — hydrate,
  execute, pin — may run at all; `kotobase.code-graph` records that an
  artifact was built from an admitted code graph under granted effects.
  Neither has a query plan, and mapping them onto version 1 of the execution
  contract would have meant inventing one.

  `docs/ADR-evidence-plane.md` measured that and named the shape of the
  answer: **two subjects, not one**, the second needing its own versioned
  record. This is it.

  What an authorised effect binds, and what a query execution does not:

  - the **action** and the **resource** it was authorised over;
  - the **code lock** it ran under — a CID proves bytes, never authority, and
    the package lock is what says which bytes were admitted;
  - the **effects granted**, post-intersection of what was requested, what was
    delegated, and what local policy allows.

  What it shares with a query execution, deliberately and by the same names:
  policy snapshot, revocation epoch, request digest, measured cost,
  implementation build, signature. Two subjects should still be one vocabulary.

  Validation is exact and fail closed, and version 1 is closed the same way:
  new fields require a new version and an explicit compatibility decision."
  (:require [clojure.set :as set]))

(def version 1)

(def request-keys
  "Exactly the fields of a version 1 EffectRequest."
  #{:request/version :principal :tenant :effect/action :effect/resource
    :code/lock :effect/requested :authority/policy :authority/epoch
    :nonce :expires-at})

(def receipt-keys
  "Exactly the fields of a version 1 EffectReceipt."
  #{:effect/version :request/digest :authority/policy :authority/epoch
    :effect/action :effect/resource :code/lock :effect/granted
    :authority/decision :outcome/roots :cost :implementation/build :signature})

(def cost-keys
  #{:dependent-hops :requests :bytes :cache-profile})

(def ^:private forbidden-keys
  #{:credential :credential/raw :token :authorization :secret/raw
    :effect/thunk :package/bytes})

(defn- reject! [reason data]
  (throw (ex-info "effect contract rejected"
                  (assoc data :kotobase.effect-contract/reason reason))))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- non-empty-proof? [value]
  (or (non-empty-string? value)
      (and (coll? value) (seq value))))

(defn- effect-set? [value]
  (and (set? value) (every? keyword? value)))

(defn- root-vector? [value]
  (and (vector? value) (every? non-empty-string? value)))

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

(defn validate-request!
  "Return a valid EffectRequest, otherwise throw ExceptionInfo."
  [request]
  (exact-keys! request request-keys :effect-request)
  (no-forbidden-fields! request)
  (when-not (and (= version (:request/version request))
                 (non-empty-string? (:principal request))
                 (non-empty-string? (:tenant request))
                 (keyword? (:effect/action request))
                 (non-empty-string? (:effect/resource request))
                 (non-empty-string? (:code/lock request))
                 (effect-set? (:effect/requested request))
                 (seq (:effect/requested request))
                 (non-empty-string? (:authority/policy request))
                 (nat-int? (:authority/epoch request))
                 (non-empty-string? (:nonce request))
                 (non-empty-string? (:expires-at request)))
    (reject! :invalid-request {}))
  request)

(defn validate-receipt!
  "Return a valid EffectReceipt, otherwise throw ExceptionInfo."
  [receipt]
  (exact-keys! receipt receipt-keys :effect-receipt)
  (no-forbidden-fields! receipt)
  (let [cost (:cost receipt)
        decision (:authority/decision receipt)
        roots (:outcome/roots receipt)]
    (when-not (and (= version (:effect/version receipt))
                   (non-empty-string? (:request/digest receipt))
                   (non-empty-string? (:authority/policy receipt))
                   (nat-int? (:authority/epoch receipt))
                   (keyword? (:effect/action receipt))
                   (non-empty-string? (:effect/resource receipt))
                   (non-empty-string? (:code/lock receipt))
                   (effect-set? (:effect/granted receipt))
                   (contains? #{:allow :deny} decision)
                   (root-vector? roots)
                   ;; a refused effect produced nothing, and an admitted one
                   ;; that produced nothing has not said what it did
                   (if (= :allow decision) (seq roots) (empty? roots))
                   (map? cost)
                   (= cost-keys (set (keys cost)))
                   (every? nat-int?
                           ((juxt :dependent-hops :requests :bytes) cost))
                   (keyword? (:cache-profile cost))
                   (non-empty-string? (:implementation/build receipt))
                   (non-empty-proof? (:signature receipt)))
      (reject! :invalid-receipt {})))
  receipt)

(defn validate-effect!
  "Validate both records and their cross-record invariants.

  `request-digest` is calculated by the host's canonical codec and supplied
  explicitly, keeping this namespace independent of an encoding or a hash."
  [{:keys [request request-digest receipt] :as bundle}]
  (when-not (= #{:request :request-digest :receipt} (set (keys bundle)))
    (reject! :invalid-bundle-keys {}))
  (validate-request! request)
  (validate-receipt! receipt)
  (when-not (and (non-empty-string? request-digest)
                 (= request-digest (:request/digest receipt))
                 (= (:authority/policy request) (:authority/policy receipt))
                 (= (:authority/epoch request) (:authority/epoch receipt))
                 (= (:effect/action request) (:effect/action receipt))
                 (= (:effect/resource request) (:effect/resource receipt))
                 (= (:code/lock request) (:code/lock receipt)))
    (reject! :cross-record-mismatch {}))
  (when-not (set/subset? (:effect/granted receipt) (:effect/requested request))
    ;; an effect nobody asked for cannot have been granted by intersecting
    ;; what was asked with anything
    (reject! :granted-outside-request
             {:granted (:effect/granted receipt)
              :requested (:effect/requested request)}))
  (when (and (= :allow (:authority/decision receipt))
             (not= (:effect/granted receipt) (:effect/requested request)))
    ;; admission allows only when nothing requested is missing, so an allow
    ;; that granted less than was asked is a record of two different decisions
    (reject! :allowed-with-missing-effects
             {:granted (:effect/granted receipt)
              :requested (:effect/requested request)}))
  bundle)
