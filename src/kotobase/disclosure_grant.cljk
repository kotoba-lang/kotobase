(ns kotobase.disclosure-grant
  "Recipient-bound key delivery over immutable ciphertexts.

  Crypto and durable storage remain host ports. No plaintext or unwrapped key
  enters this module. A delivery receipt means authorised envelope release,
  never human reading, successful decryption, or absence of out-of-band copies."
  (:require [clojure.set :as set]
            [kotobase.authority-window :as window]
            [kotobase.execution-identity :as identity]
            [kotobase.execution-keys :as keys]
            [kotoba.security.crypto-policy :as crypto]
            [multiformats.core :as mf]))

(def grant-keys
  #{:disclosure/version :tenant :owner :issuer :recipient :recipient-key
    :resource :policy :operations :delegation-depth :parent :not-before
    :expires-at :epoch :key-envelope-cid :signature})
(def request-keys
  #{:disclosure/version :tenant :principal :recipient-key :resource :grant
    :audience :nonce :expires-at :epoch :signature})
(def context-keys
  #{:tenant :owner :principal :recipient-key :resource :policy :audience
    :executor :now :epoch})
(def operations #{:decrypt :propose-update})
(def max-chain-length 32)

(defn- reject! [reason]
  ;; Do not put key envelopes or graph membership into exception telemetry.
  (throw (ex-info "disclosure grant rejected"
                  {:kotobase.disclosure-grant/reason reason})))

(defn- exact! [ks value]
  (when-not (and (map? value) (= ks (set (keys value))))
    (reject! :invalid-shape)))

(defn- text? [x] (and (string? x) (not (empty? x))))
(defn- cid? [x]
  (and (text? x)
       (try (let [{:keys [version error]} (mf/cid->parts x)]
              (and (nil? error) (= 1 version)))
            (catch #?(:clj Exception :cljs :default) _ false))))

(defn ciphertext-cid
  "Raw CIDv1/SHA-256 of encrypted bytes; not the CID of the decrypted value."
  [bytes]
  (let [bytes #?(:clj (if (bytes? bytes) (mapv #(bit-and % 255) bytes) bytes)
                 :cljs bytes)]
    (when-not (and (seq bytes)
                   (every? #(and (integer? %) (<= 0 % 255)) bytes))
      (reject! :invalid-ciphertext-bytes))
    (mf/cidv1 0x55 (mf/multihash-sha256 bytes))))

(defn binding
  "Authenticated encryption context for a key envelope. Includes recipient,
  scope and parent, but excludes the envelope and signature to avoid a cycle.
  The crypto provider MUST bind this value as HPKE info/AAD (or equivalent)."
  [grant]
  (identity/value-cid
   {:domain :kotobase.disclosure/key-envelope-v1
    :grant (dissoc grant :key-envelope-cid :signature)}))

(defn signing-cid
  "Domain-separated signing input. HPKE authentication is not a public signature."
  [record-kind record]
  (identity/value-cid {:domain :kotobase.disclosure/signature-v1
                       :record-kind record-kind
                       :payload (dissoc record :signature)}))

(defn- signature-context [kind record principal context]
  (let [signature (keys/signature (:signature record))]
    {:record kind :principal principal :tenant (:tenant context)
     :epoch (:epoch context) :signature signature
     :payload-cid (signing-cid kind record)}))

(defn- check-context! [context]
  (exact! context-keys context)
  (doseq [k [:tenant :owner :principal :recipient-key :audience :executor]]
    (when-not (text? (get context k)) (reject! :invalid-context)))
  (when-not (and (cid? (:resource context)) (cid? (:policy context))
                 (nat-int? (:epoch context)) (window/instant-key (:now context)))
    (reject! :invalid-context)))

(defn- check-grant! [grant context consume-nonce!]
  (exact! grant-keys grant)
  (when-not (= 1 (:disclosure/version grant)) (reject! :unsupported-version))
  (when-not (cid? (:key-envelope-cid grant)) (reject! :invalid-envelope-cid))
  (doseq [k [:owner :issuer :recipient :recipient-key]]
    (when-not (text? (get grant k)) (reject! :invalid-principal)))
  (when-not (and (set? (:operations grant)) (seq (:operations grant))
                 (set/subset? (:operations grant) operations)
                 (nat-int? (:delegation-depth grant)))
    (reject! :invalid-scope))
  (doseq [k [:tenant :owner :resource :policy :epoch]]
    (when-not (= (get context k) (get grant k)) (reject! :scope-mismatch)))
  (when-not (window/instant-key (:not-before grant)) (reject! :invalid-time))
  (window/open! {:authority {:now (:now context) :epoch (:epoch context)
                            :consume-nonce! consume-nonce!}
                 :not-before (:not-before grant) :expires-at (:expires-at grant)
                 :epoch (:epoch grant)}))

(defn- check-edge! [parent child]
  (when-not (= (:parent child) (identity/value-cid parent))
    (reject! :parent-cid-mismatch))
  (when-not (= (:recipient parent) (:issuer child)) (reject! :wrong-delegator))
  (when-not (and (pos? (:delegation-depth parent))
                 (< (:delegation-depth child) (:delegation-depth parent))
                 (set/subset? (:operations child) (:operations parent)))
    (reject! :authority-amplification))
  (when (or (neg? (compare (window/instant-key (:not-before child))
                           (window/instant-key (:not-before parent))))
            (pos? (compare (window/instant-key (:expires-at child))
                           (window/instant-key (:expires-at parent)))))
    (reject! :time-amplification)))

(defn- check-key-envelope! [envelope grant context crypto-policy]
  (when-not (= (:key-envelope-cid grant) (identity/value-cid envelope))
    (reject! :key-envelope-mismatch))
  (when-not (= (binding grant) (:envelope/binding envelope))
    (reject! :envelope-binding-mismatch))
  (when-not (and (true? (:envelope/kem? envelope))
                 (true? (:envelope/hybrid? envelope))
                 (true? (:valid? (crypto/check-production-envelope crypto-policy envelope))))
    (reject! :crypto-policy))
  (when-not (= (:epoch context) (:envelope/epoch envelope))
    (reject! :envelope-epoch-mismatch))
  ;; Encoded provider ciphertext, including the KEM encapsulation as needed.
  ;; A canonical octet vector is portable across CLJ/CLJS value codecs.
  (when-not (vector? (:sealed/ciphertext envelope)) (reject! :invalid-key-ciphertext))
  (ciphertext-cid (:sealed/ciphertext envelope)))

(defn- validate! [{:keys [chain request context consume-nonce! crypto-policy key-envelope]}]
  (check-context! context)
  (when-not (and (vector? chain) (<= 1 (count chain) max-chain-length))
    (reject! :invalid-chain))
  (doseq [grant chain] (check-grant! grant context consume-nonce!))
  (let [root (first chain) leaf (peek chain)]
    (check-key-envelope! key-envelope leaf context crypto-policy)
    (when-not (and (nil? (:parent root)) (= (:owner context) (:issuer root)))
      (reject! :untrusted-root))
    (doseq [[parent child] (partition 2 1 chain)] (check-edge! parent child))
    (exact! request-keys request)
    (when-not (and (= 1 (:disclosure/version request)) (text? (:nonce request)))
      (reject! :invalid-request))
    (doseq [k [:tenant :principal :recipient-key :resource :audience :epoch]]
      (when-not (= (get context k) (get request k)) (reject! :request-mismatch)))
    (when-not (and (= (:grant request) (identity/value-cid leaf))
                   (= (:recipient leaf) (:principal request))
                   (= (:recipient-key leaf) (:recipient-key request))
                   (contains? (:operations leaf) :decrypt))
      (reject! :recipient-or-grant-mismatch))
    (window/open! {:authority {:now (:now context) :epoch (:epoch context)
                              :consume-nonce! consume-nonce!}
                   :expires-at (:expires-at request) :epoch (:epoch request)})))

(defn- require-true! [reason verdict]
  (when-not (true? verdict) (reject! reason)))

(defn- sync-bind [value f]
  #?(:cljs (when (and (some? value) (fn? (unchecked-get value "then")))
             (reject! :async-port-in-sync-api)))
  (f value))

(defn- release-with! [bind {:keys [chain request context verify! authorize!
                                  consume-nonce! sign! commit! read!] :as options}]
  (exact! #{:chain :request :context :crypto-policy :key-envelope :verify! :authorize!
            :consume-nonce! :sign! :commit! :read!} options)
  (doseq [port [verify! authorize! consume-nonce! sign! commit! read!]]
    (when-not (fn? port) (reject! :missing-port)))
  (validate! options)
  (let [checks (conj (mapv #(signature-context :disclosure-grant % (:issuer %) context)
                           chain)
                     (signature-context :disclosure-request request
                                        (:principal request) context))
        leaf (peek chain)
        unsigned {:disclosure/version 1 :event :key-envelope-release-authorized
                  :tenant (:tenant context) :executor (:executor context)
                  :request (identity/value-cid request)
                  :grant (identity/value-cid leaf) :resource (:resource context)
                  :recipient (:principal request) :recipient-key (:recipient-key request)
                  :policy (:policy context) :epoch (:epoch context) :at (:now context)}]
    (bind
     (reduce (fn [previous check]
               (bind previous
                     (fn [_] (bind (verify! check)
                                    #(require-true! :signature-rejected %)))))
             true checks)
     (fn [_]
       ;; This is an additional local-policy/individual-revocation gate, not
       ;; a replacement for grant.authority or governed-read query admission.
       (bind (authorize! {:context context :chain chain :request request})
             (fn [verdict]
               (require-true! :authority-denied verdict)
               (bind (consume-nonce! (select-keys request [:tenant :principal :audience :nonce]))
                     (fn [fresh?]
                       (require-true! :nonce-replayed fresh?)
                       (bind (sign! {:record :disclosure-delivery
                                    :payload-cid (signing-cid :disclosure-delivery unsigned)
                                    :unsigned unsigned})
                             (fn [signature]
                               (let [receipt (assoc unsigned :signature (keys/signature signature))
                                     receipt-cid (identity/value-cid receipt)]
                                 (bind (verify! (signature-context :disclosure-delivery receipt
                                                                  (:executor context) context))
                                       (fn [verified?]
                                         (require-true! :receipt-signature-rejected verified?)
                                         (bind (commit! receipt)
                                               (fn [ack]
                                                 (when-not (and (true? (:receipt/durable? ack))
                                                                (= receipt-cid (:receipt/cid ack)))
                                                   (reject! :receipt-not-durable))
                                                 (bind (read! receipt-cid)
                                                       (fn [stored]
                                                         (when-not (= stored receipt)
                                                           (reject! :receipt-readback-mismatch))
                                                         {:grant/cid (identity/value-cid leaf)
                                                          :grant leaf
                                                          :key-envelope (:key-envelope options)
                                                          :binding (binding leaf)
                                                          :receipt/cid receipt-cid
                                                          :receipt receipt})))))))))))))))))

(defn release!
  "Verify root-to-leaf grants and signed request, check current authority,
  consume a scoped nonce, sign/commit/read back a delivery receipt, then return
  the recipient's encrypted key envelope. All ports must be synchronous.

  VERIFY! must resolve the named signature key from trusted principal/tenant/
  epoch state and verify payload-cid; key ids supplied by the request are NOT
  a registry. CONTEXT is host-authenticated, never copied from the request.
  COMMIT!/READ! must use immutable durable storage (e.g. causal-commit)."
  [options]
  (release-with! sync-bind options))

#?(:cljs
   (defn release-async!
     "Worker equivalent; every effect is awaited before envelope release."
     [options]
     (try (js/Promise.resolve
           (release-with! (fn [value f] (.then (js/Promise.resolve value) f)) options))
          (catch :default error (js/Promise.reject error)))))

(defn delivery-verification
  "Validate a remote delivery's bindings; return the executor signature check.
  The caller MUST cryptographically verify this check before opening the key.
  CONTEXT is local trusted state, REQUEST is the exact signed request sent.
  A delivery receipt does not independently prove the remote log's durability."
  [context request delivery crypto-policy]
  (check-context! context)
  (exact! request-keys request)
  (when-not (and (= 1 (:disclosure/version request)) (text? (:nonce request)))
    (reject! :invalid-request))
  (exact! #{:grant/cid :grant :key-envelope :binding :receipt/cid :receipt} delivery)
  (let [{:keys [grant key-envelope receipt]} delivery]
    (check-grant! grant context (constantly true))
    (when-not (and (= (:grant request) (:grant/cid delivery) (identity/value-cid grant))
                   (= (:recipient grant) (:principal context))
                   (= (:recipient-key grant) (:recipient-key context))
                   (contains? (:operations grant) :decrypt)
                   (= (:key-envelope-cid grant) (identity/value-cid key-envelope))
                   (= (binding grant) (:binding delivery) (:envelope/binding key-envelope))
                   (= (:receipt/cid delivery) (identity/value-cid receipt)))
      (reject! :delivery-mismatch))
    (doseq [k [:tenant :principal :recipient-key :resource :audience :epoch]]
      (when-not (= (get request k) (get context k)) (reject! :request-mismatch)))
    (window/open! {:authority {:now (:now context) :epoch (:epoch context)
                              :consume-nonce! (constantly true)}
                   :expires-at (:expires-at request) :epoch (:epoch request)})
    (check-key-envelope! key-envelope grant context crypto-policy)
    (exact! #{:disclosure/version :event :tenant :executor :request :grant :resource
              :recipient :recipient-key :policy :epoch :at :signature} receipt)
    (when-not (and (= 1 (:disclosure/version receipt))
                   (= :key-envelope-release-authorized (:event receipt))
                   (= (:request receipt) (identity/value-cid request))
                   (= (:grant receipt) (:grant request))
                   (= (:recipient receipt) (:principal context))
                   (window/instant-key (:at receipt)))
      (reject! :receipt-mismatch))
    (doseq [k [:tenant :executor :resource :recipient-key :policy :epoch]]
      (when-not (= (get receipt k) (get context k)) (reject! :receipt-mismatch)))
    (signature-context :disclosure-delivery receipt (:executor context) context)))
