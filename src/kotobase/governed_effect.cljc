(ns kotobase.governed-effect
  "The one path that runs an effect and leaves evidence of what it did.

  `kotobase.effect-contract` defined the records. Nothing constructed them,
  which is the criticism this whole series started from: a contract with no
  caller is a schema, and a schema is not a boundary.

  This is the caller. It is the effect-side twin of
  `kotobase.governed-execution`, and it composes, in this order:

  1. the **EffectRequest** is validated and bound to the admission about to be
     made — the code lock in the envelope must be the lock of the package
     receipt being admitted, or the record describes bytes other than the ones
     that ran;
  2. **runtime authority** — expiry, current revocation epoch, nonce ledger,
     via `kotobase.authority-window`, which refuses when the host's answers
     are absent;
  3. **`kotobase.admission/guard!` unchanged**, so its rule survives: the
     audit must be durable *before* the effect thunk runs;
  4. the **EffectReceipt** is built from what happened — the granted set the
     admission actually computed, the outcome roots the effect actually
     produced, a cost read after it — signed, its signature verified, and
     cross-checked against the envelope;
  5. the receipt is **committed and read back**, and only then does the
     effect's result return.

  ## Why there are still two records here, and why that is not a leftover

  A read's receipt can bind its result because reading is repeatable. An
  effect's cannot: an outcome does not exist until the effect has run, so a
  record that binds one is necessarily written afterwards. `admission`'s audit
  is the *precondition* — written before anything happens, so a refusal that
  never ran leaves a trace — and this receipt is the *evidence* — written
  after, so what happened is named. They answer different questions and
  collapsing them would lose one of the answers.

  What has changed is that the second question is now answered at all, and
  `kotobase.evidence` lifts both records onto the same contract, so they are
  comparable rather than merely adjacent.

  A refusal still produces a receipt. Being refused should not be the cheapest
  way to leave no trace."
  (:require [kotobase.admission :as admission]
            [kotobase.authority-window :as window]
            [kotobase.effect-contract :as contract]))

(def ^:private effect-keys
  #{:request :authority :value-cid :verify :sign :cost :implementation/build
    :commit! :audit! :effect!
    :cid-verified? :package-receipt :delegated-effects :local-policy-effects})

(defn- reject! [reason data]
  (throw (ex-info "governed effect rejected"
                  (assoc data :kotobase.governed-effect/reason reason))))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- non-empty-proof? [value]
  (or (non-empty-string? value)
      (and (coll? value) (seq value))))

(defn- validate-shape!
  [{:keys [value-cid verify sign cost commit! audit! effect!] :as options}]
  (when-not (and (map? options) (= effect-keys (set (keys options))))
    (reject! :invalid-effect-options
             {:missing (vec (remove (set (keys options)) effect-keys))
              :unexpected (vec (remove effect-keys (keys options)))}))
  (when-not (non-empty-string? (:implementation/build options))
    (reject! :invalid-implementation-build {}))
  (doseq [[label f] [[:value-cid value-cid] [:verify verify] [:sign sign]
                     [:cost cost] [:commit! commit!] [:audit! audit!]
                     [:effect! effect!]]]
    (when-not (ifn? f)
      (reject! :missing-host-function {:function label})))
  options)

(defn- bind-lock!
  "The envelope's code lock must be the lock of the package being admitted.

  Without this the receipt names one set of admitted bytes and the admission
  decides about another."
  [request package-receipt]
  (when-not (= (:code/lock request) (:package/lock-cid package-receipt))
    (reject! :code-lock-mismatch
             {:envelope (:code/lock request)
              :package (:package/lock-cid package-receipt)}))
  request)

(defn- checked-outcome [produced]
  (when-not (and (map? produced) (= #{:roots :result} (set (keys produced))))
    (reject! :invalid-effect-result
             {:keys (when (map? produced) (set (keys produced)))}))
  (when-not (and (vector? (:roots produced))
                 (seq (:roots produced))
                 (every? non-empty-string? (:roots produced)))
    ;; an effect that ran and named nothing has not said what it did
    (reject! :effect-named-no-outcome {}))
  produced)

(defn- signed-receipt!
  [{:keys [request value-cid sign verify cost] :as options} decision roots]
  (let [unsigned {:effect/version contract/version
                  :request/digest (value-cid request)
                  :authority/policy (:authority/policy request)
                  :authority/epoch (:authority/epoch request)
                  :effect/action (:effect/action request)
                  :effect/resource (:effect/resource request)
                  :code/lock (:code/lock request)
                  :effect/granted (:admission/granted decision)
                  :authority/decision (if (:admission/allowed? decision)
                                        :allow :deny)
                  :outcome/roots roots
                  :cost (cost)
                  :implementation/build (:implementation/build options)}
        proof (sign unsigned)]
    (when-not (non-empty-proof? proof)
      (reject! :invalid-signature {}))
    (let [signed (assoc unsigned :signature proof)]
      (when-not (true? (verify {:record :effect-receipt
                                :payload-cid (value-cid unsigned)
                                :signature proof
                                :tenant (:tenant request)
                                :epoch (:authority/epoch request)}))
        (reject! :signature-not-verified {:record :effect-receipt}))
      (contract/validate-effect! {:request request
                                  :request-digest (value-cid request)
                                  :receipt signed})
      signed)))

(defn- durable-ack! [ack]
  (when-not (and (map? ack)
                 (true? (:receipt/durable? ack))
                 (non-empty-string? (:receipt/cid ack)))
    (reject! :effect-receipt-not-durable {:ack ack}))
  ack)

(defn- refused! [error decision ack]
  (throw (ex-info "governed effect refused"
                  {:kotobase.governed-effect/reason :admission-denied
                   :kotobase.governed-effect/deny-receipt-cid (:receipt/cid ack)
                   :kotobase.governed-effect/code (:admission/code decision)}
                  error)))

(defn execute!
  "Admit, run, commit the EffectReceipt, and only then return the result.

  `:effect!` is handed the admission decision and must return
  `{:roots [address …] :result …}`. A refusal commits a deny receipt and then
  rethrows, with `kotobase.admission`'s own refusal as the cause."
  [{:keys [request authority commit! audit! effect! package-receipt] :as options}]
  (validate-shape! options)
  (contract/validate-request! request)
  (bind-lock! request package-receipt)
  (let [consume-nonce! (window/open! {:authority authority
                                      :expires-at (:expires-at request)
                                      :epoch (:authority/epoch request)
                                      :not-before nil})]
    (window/spent! (:nonce request) (consume-nonce! (:nonce request)))
    (let [produced (atom nil)
          admitted (try
                     (admission/guard!
                      {:action (:effect/action request)
                       :resource (:effect/resource request)
                       :requested-effects (:effect/requested request)
                       :delegated-effects (:delegated-effects options)
                       :local-policy-effects (:local-policy-effects options)
                       :cid-verified? (:cid-verified? options)
                       :package-receipt package-receipt
                       :audit! audit!
                       :effect (fn [decision]
                                 (reset! produced
                                         (checked-outcome (effect! decision))))})
                     (catch #?(:clj Exception :cljs :default) error
                       (let [decision (:decision (ex-data error))]
                         (if (= :kotobase/admission-denied
                                (:type (ex-data error)))
                           (let [signed (signed-receipt! options decision [])]
                             (refused! error decision
                                       (durable-ack! (commit! signed))))
                           ;; an audit that would not persist, or a broken
                           ;; effect, is not an admission decision
                           (throw error)))))
          signed (signed-receipt! options (:decision admitted)
                                  (:roots @produced))]
      (durable-ack! (commit! signed))
      {:result (:result @produced)
       :decision (:decision admitted)
       :effect/receipt signed})))
