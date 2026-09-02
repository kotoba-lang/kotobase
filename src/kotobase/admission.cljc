(ns kotobase.admission
  "Authorization membrane for hydrate, execute, and pin operations.

  A CID proves bytes, never authority. Production callers must pass package
  admission, delegated grants, local policy, and durable audit admission
  before the effect thunk can run."
  (:require [clojure.set :as set]))

(def production-actions #{:hydrate :execute :pin})

(defn decide
  [{:keys [action cid-verified? package-receipt requested-effects
           delegated-effects local-policy-effects resource]}]
  (let [requested (set requested-effects)
        delegated (set delegated-effects)
        local (set local-policy-effects)
        granted (set/intersection requested delegated local)
        missing (set/difference requested granted)
        package-ok? (and (true? (:package/verified? package-receipt))
                         (string? (:package/lock-cid package-receipt))
                         (seq (:package/lock-cid package-receipt)))
        code (cond
               (not (contains? production-actions action))
               :admission/unknown-action
               (not cid-verified?) :admission/cid-invalid
               (not package-ok?) :admission/package
               (or (contains? delegated :any) (contains? local :any)
                   (contains? requested :any))
               :admission/wildcard
               (empty? requested) :admission/no-requested-effects
               (seq missing) :admission/capability
               (not (and (string? resource) (seq resource)))
               :admission/resource
               :else nil)]
    {:admission/allowed? (nil? code)
     :admission/code code
     :admission/action action
     :admission/resource resource
     :admission/package-lock-cid (:package/lock-cid package-receipt)
     :admission/requested requested
     :admission/granted granted
     :admission/missing missing}))

(defn- durable-audit? [audit-ack]
  (and (map? audit-ack)
       (true? (:audit/durable? audit-ack))
       (string? (:audit/receipt-id audit-ack))
       (seq (:audit/receipt-id audit-ack))))

(defn- admitted!
  "Refuse unless the audit is durable and the decision allows the effect.

  Split out so the Promise-aware path below decides the same way rather than
  deciding again: an audit rule that exists in two places has two behaviours
  as soon as one of them is edited."
  [decision audit-ack effect]
  (when-not (durable-audit? audit-ack)
    (throw (ex-info "authorization audit persistence failed"
                    {:type :kotobase/audit-denied
                     :decision decision})))
  (when-not (:admission/allowed? decision)
    (throw (ex-info "content authorization denied"
                    {:type :kotobase/admission-denied
                     :decision decision
                     :audit audit-ack})))
  (when-not (ifn? effect)
    (throw (ex-info "authorized effect missing"
                    {:type :kotobase/effect-missing})))
  true)

(defn guard!
  "Audit the authorization decision before invoking EFFECT.
   AUDIT! must return a durable receipt acknowledgement."
  [{:keys [audit! effect] :as request}]
  (let [decision (decide request)
        audit-ack (when (ifn? audit!) (audit! decision))]
    (admitted! decision audit-ack effect)
    {:decision decision :audit audit-ack :result (effect decision)}))

#?(:cljs
   (defn guard-async!
     "The Worker path. AUDIT! and EFFECT may each return a Promise.

     The effect is not invoked while the audit acknowledgement is still in
     flight — an unawaited Promise is truthy, and a rule that admits one has
     admitted every effect whose audit had not landed yet."
     [{:keys [audit! effect] :as request}]
     (try
       (let [decision (decide request)]
         (-> (js/Promise.resolve (when (ifn? audit!) (audit! decision)))
             (.then
              (fn [audit-ack]
                (admitted! decision audit-ack effect)
                (-> (js/Promise.resolve (effect decision))
                    (.then (fn [result]
                             {:decision decision
                              :audit audit-ack
                              :result result})))))))
       (catch :default error
         (js/Promise.reject error)))))
