(ns kotobase.execution-keys
  "Which key, whose, and was it allowed to sign this at this epoch.

  `kotobase.governed-execution` asks a `:verify` function a yes/no question
  and refuses anything that is not literally `true`. That closed one gap and
  left a larger one open: a verifier could answer `true` using any key it
  liked. *Verified* then meant `some key signed these bytes`, which is not the
  question an auditor asks. The question is whether **this** key was
  authorised to sign **this kind of record**, for **this tenant**, at **this
  revocation epoch** — and a signature verified under a key that was revoked
  two epochs ago is not a lesser form of valid.

  So a signature here is not an opaque proof. It names the key that made it:

      {:key/id \"…\" :key/algorithm :ed25519 :signature/value …}

  and this namespace refuses it unless the registry says that key id, with
  that algorithm, was authorised for that role, tenant and epoch. Only then is
  the actual cryptography asked to run, and it too must answer `true`.

  Two roles, because the two records are signed by different authorities:

  - `:issuer` signs the **ExecutionManifest** — the authority that bound a
    data commit, a policy snapshot and a pack location together.
  - `:executor` signs the **ExecutionReceipt** — whatever actually ran it.

  A registry that returns nothing is a **refusal**, not an allowance. That is
  the single most likely way for this to fail silently: a lookup that cannot
  answer looks exactly like a lookup that answered `no keys, so nothing is
  wrong here`.

  What stays outside: the cryptography itself (`verify-with` is injected,
  because this repository does not choose a signature scheme, exactly as it
  does not choose a codec), and how a key came to be in the registry."
  (:require [clojure.set :as set]))

(def roles
  "Which authority signs which record.

  An EffectReceipt is signed by whatever ran the effect, which is the same
  role as the one that ran a query:  is about what performed the
  work, not about which contract the record belongs to."
  {:execution-manifest :issuer
   :execution-receipt :executor
   :effect-receipt :executor})

(def signature-keys
  "Exactly the fields of a version 1 signature envelope."
  #{:key/id :key/algorithm :signature/value})

(def registered-key-keys
  "Exactly the fields the registry must return for each authorised key."
  #{:key/id :key/algorithm :key/material})

(defn- reject! [reason data]
  (throw (ex-info "execution key rejected"
                  (assoc data :kotobase.execution-keys/reason reason))))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- checked-signature [signature]
  (when-not (and (map? signature) (= signature-keys (set (keys signature))))
    (reject! :invalid-signature-envelope
             {:keys (when (map? signature) (set (keys signature)))}))
  (when-not (non-empty-string? (:key/id signature))
    (reject! :invalid-key-id {}))
  (when-not (keyword? (:key/algorithm signature))
    (reject! :invalid-algorithm {}))
  (when-not (or (non-empty-string? (:signature/value signature))
                (and (coll? (:signature/value signature))
                     (seq (:signature/value signature))))
    (reject! :empty-signature {}))
  signature)

(defn- checked-registered [entry scope]
  (when-not (and (map? entry) (= registered-key-keys (set (keys entry))))
    (reject! :invalid-registered-key
             (assoc scope :keys (when (map? entry) (set (keys entry))))))
  entry)

(defn- authorised
  "The one registered key this signature claims, or a refusal saying why not."
  [registry {:keys [record tenant epoch] :as context} signature]
  (let [role (get roles record)
        _ (when-not role
            (reject! :unknown-record-kind
                     {:record record :known (set (keys roles))}))
        scope {:role role :tenant tenant :epoch epoch}
        _ (when-not (non-empty-string? tenant)
            (reject! :invalid-tenant scope))
        _ (when-not (nat-int? epoch)
            (reject! :invalid-epoch scope))
        registered (registry scope)]
    ;; a registry that could not answer has not said this key is authorised
    (when-not (and (coll? registered) (seq registered))
      (reject! :no-key-registered scope))
    (run! #(checked-registered % scope) registered)
    (let [by-id (into {} (map (juxt :key/id identity)) registered)
          entry (get by-id (:key/id signature))]
      (when-not entry
        (reject! :key-not-authorised-here
                 (assoc scope :key-id (:key/id signature)
                        :authorised (set (keys by-id)))))
      (when-not (= (:key/algorithm entry) (:key/algorithm signature))
        ;; the same key id under a different algorithm is a different key
        (reject! :algorithm-mismatch
                 (assoc scope :key-id (:key/id signature)
                        :registered (:key/algorithm entry)
                        :claimed (:key/algorithm signature))))
      (assoc context :key entry))))

(defn verifier
  "Build the `:verify` function `kotobase.governed-execution` requires.

  REGISTRY is `(fn [{:keys [role tenant epoch]}] [registered-key …])` — what
  this deployment says may sign that kind of record, for that tenant, at that
  epoch. VERIFY-WITH is `(fn [{:keys [key payload-cid signature-value]}]
  boolean)` and is where the cryptography lives.

  Returns a function that answers `true` only when the registry authorised the
  key the signature names, under the algorithm it names, and the cryptography
  then agreed. Every other outcome throws with a reason rather than answering
  `false`, because `false` and `I could not tell` reach the caller as the same
  refusal and only one of them is a fact about the signature."
  [{:keys [registry verify-with] :as options}]
  (when-not (and (map? options) (= #{:registry :verify-with} (set (keys options))))
    (reject! :invalid-verifier-options {}))
  (when-not (ifn? registry) (reject! :missing-registry {}))
  (when-not (ifn? verify-with) (reject! :missing-verify-with {}))
  (fn [{:keys [payload-cid signature] :as context}]
    (when-not (non-empty-string? payload-cid)
      (reject! :invalid-payload-cid {}))
    (let [{:keys [key]} (authorised registry context (checked-signature signature))]
      (true? (verify-with {:key key
                           :payload-cid payload-cid
                           :signature-value (:signature/value signature)})))))

(defn static-registry
  "A registry from a literal table, for deployments whose keys are configured.

  TABLE is `{{:role … :tenant … :epoch …} [registered-key …]}`. A scope that is
  not in the table returns nothing, which the verifier refuses — so adding a
  tenant or rolling an epoch without adding its keys fails closed rather than
  quietly accepting whatever signed."
  [table]
  (when-not (map? table) (reject! :invalid-registry-table {}))
  (doseq [scope (keys table)]
    (when-not (and (map? scope) (= #{:role :tenant :epoch} (set (keys scope))))
      (reject! :invalid-registry-scope {:scope scope}))
    (when-not (contains? (set (vals roles)) (:role scope))
      (reject! :unknown-role {:role (:role scope)
                              :known (set (vals roles))})))
  (fn [scope] (get table scope)))

(defn signature
  "Build the signature envelope this namespace accepts."
  [{:keys [key/id key/algorithm signature/value] :as envelope}]
  (when-not (= signature-keys (set (keys envelope)))
    (reject! :invalid-signature-envelope
             {:missing (set/difference signature-keys (set (keys envelope)))}))
  (checked-signature {:key/id id
                      :key/algorithm algorithm
                      :signature/value value}))
