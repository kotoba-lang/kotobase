(ns kotobase.profile
  "Fail-closed runtime capability negotiation for Kotobase deployment profiles.

  A profile is a statement about evidence supplied by the host, not a name a
  deployment may self-assign. Higher profiles never change Datom/query
  semantics; they add operational or consensus guarantees around the same
  database value."
  (:require [clojure.set :as set]
            [kotobase.store :as store]))

(def profile-order
  [:standalone :managed :replicated :consensus :decentralized])

(def requirements
  {:standalone #{:transactional-store}
   :managed #{:transactional-store :tenant-auth :durable-object-store
              :backup-restore :service-observability}
   :replicated #{:managed :cid-verification :closed-car
                 :independent-recovery-source}
   :consensus #{:replicated :proposal-blocks :verified-votes
                :quorum-certificates :real-transport
                :finalized-head-resolver}
   :decentralized #{:consensus :four-independent-witnesses
                    :adversarial-recovery-tests :external-security-review
                    :measured-operator-concentration}})

(def guarantees
  {:standalone #{:datoms :graph-query :atomic-local-transaction
                 :history :as-of :since}
   :managed #{:tenant-isolation :operator-ordered-transactions
              :durable-hosting :audit :service-slo}
   :replicated #{:content-identity :verifiable-recovery
                 :multi-copy-availability :signed-head-chain}
   :consensus #{:canonical-order :three-chain-finality
                :deterministic-en-replay}
   :decentralized #{:f1-byzantine-finality :no-single-finalizer}})

(defn known-profile? [profile-id]
  (contains? requirements profile-id))

(defn- profile-prefix [profile-id]
  (let [rank (.indexOf profile-order profile-id)]
    (when-not (neg? rank)
      (subvec profile-order 0 (inc rank)))))

(defn required-evidence
  "All evidence required through PROFILE-ID, including lower profiles."
  [profile-id]
  (when-let [ids (profile-prefix profile-id)]
    (apply set/union (map requirements ids))))

(defn provided-guarantees
  "All guarantees supplied through PROFILE-ID."
  [profile-id]
  (when-let [ids (profile-prefix profile-id)]
    (apply set/union (map guarantees ids))))

(defn evidence-for-store
  "Evidence that can be inferred safely from protocol satisfaction alone.
  Operational, replication, and consensus evidence must always be supplied by
  the host and is never guessed from a product name or endpoint."
  [s]
  (cond-> #{}
    (store/transactional-store? s) (conj :transactional-store)))

(defn declare-profile
  "Validate a host profile declaration.

  OPTS is {:profile id :store IStore :evidence #{...}}. Store-derived evidence
  is unioned with explicit evidence. Returns an immutable capability statement,
  or throws with :missing evidence. Merely choosing :consensus or
  :decentralized can never grant those guarantees."
  [{:keys [profile store evidence] :or {evidence #{}}}]
  (when-not (known-profile? profile)
    (throw (ex-info "unknown Kotobase profile"
                    {:type :kotobase.profile/unknown-profile
                     :profile profile
                     :known profile-order})))
  (when-not (store/store? store)
    (throw (ex-info "profile declaration requires an IStore"
                    {:type :kotobase.profile/store-required
                     :profile profile})))
  (let [observed (set/union (set evidence) (evidence-for-store store))
        required (required-evidence profile)
        missing (set/difference required observed)]
    (when (seq missing)
      (throw (ex-info "Kotobase profile evidence is incomplete"
                      {:type :kotobase.profile/missing-evidence
                       :profile profile
                       :missing missing
                       :observed observed})))
    {:kotobase.profile/id profile
     :kotobase.profile/evidence observed
     :kotobase.profile/guarantees (provided-guarantees profile)}))

(defn require-guarantees
  "Return DECLARATION when every requested guarantee is present; otherwise
  throw. Applications use this before entering durability/finality-sensitive
  paths instead of inferring guarantees from a hostname."
  [declaration requested]
  (let [provided (:kotobase.profile/guarantees declaration #{})
        missing (set/difference (set requested) provided)]
    (when (seq missing)
      (throw (ex-info "Kotobase deployment does not provide required guarantees"
                      {:type :kotobase.profile/missing-guarantees
                       :profile (:kotobase.profile/id declaration)
                       :missing missing
                       :provided provided})))
    declaration))
