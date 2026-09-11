(ns kotobase.disclosure-grant-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.disclosure-grant :as d]
            [kotobase.execution-identity :as id]))

;; These fixtures test protocol composition, not a cryptographic provider.
;; disclosure_worker_test.cljs additionally uses real Ed25519 signatures.
(def policy {:kotoba.security/crypto-policy-version 1
             :mode :hybrid-required :hybrid-epoch-floor 1})
(def context {:tenant "t" :owner "owner" :principal "alice"
              :recipient-key "alice-encryption-key" :executor "executor"
              :resource (d/ciphertext-cid [1 2 3]) :policy (id/value-cid :policy)
              :audience "key-service" :epoch 2 :now "2026-09-06T12:00:00Z"})

(defn sign [kind principal record]
  (assoc record :signature
         {:key/id principal :key/algorithm :fixture
          :signature/value (str principal ":" (d/signing-cid kind record))}))
(defn verify [{:keys [principal signature payload-cid]}]
  (and (= principal (:key/id signature))
       (= :fixture (:key/algorithm signature))
       (= (:signature/value signature) (str principal ":" payload-cid))))

(defn grant
  ([] (grant {}))
  ([overrides]
   (let [g (merge {:disclosure/version 1 :tenant "t" :owner "owner" :issuer "owner"
                   :recipient "alice" :recipient-key "alice-encryption-key"
                   :resource (:resource context) :policy (:policy context)
                   :operations #{:decrypt} :delegation-depth 2 :parent nil
                   :not-before "2026-09-06T11:00:00Z"
                   :expires-at "2026-09-06T13:00:00Z" :epoch 2}
                  overrides)
         envelope {:envelope/provider {:provider/id :fixture :provider/fips-validated false}
                   :envelope/algorithms [:x25519 :ml-kem-768 :aes-256-gcm]
                   :envelope/kem? true :envelope/hybrid? true :envelope/epoch 2
                   :envelope/binding (d/binding g) :sealed/ciphertext [10 20 30]}]
     {:grant (sign :disclosure-grant (:issuer g)
                   (assoc g :key-envelope-cid (id/value-cid envelope)))
      :envelope envelope})))

(defn request [g ctx]
  (sign :disclosure-request (:principal ctx)
        (merge (select-keys ctx [:tenant :principal :recipient-key :resource :audience :epoch])
               {:disclosure/version 1 :grant (id/value-cid g)
                :nonce "unique-request" :expires-at "2026-09-06T12:30:00Z"})))

(defn options
  ([] (options [(grant)] context))
  ([entries ctx]
   (let [chain (mapv :grant entries) journal (atom {}) spent (atom #{})]
     {:chain chain :request (request (peek chain) ctx) :context ctx
      :crypto-policy policy :key-envelope (:envelope (peek entries))
      :verify! verify :authorize! (constantly true)
      :consume-nonce! (fn [nonce]
                        (let [old @spent]
                          (and (not (contains? old nonce))
                               (compare-and-set! spent old (conj old nonce)))))
      :sign! (fn [{:keys [unsigned]}]
               (:signature (sign :disclosure-delivery "executor" unsigned)))
      :commit! (fn [r] (let [cid (id/value-cid r)]
                         (swap! journal assoc cid r)
                         {:receipt/durable? true :receipt/cid cid}))
      :read! (fn [cid] (get @journal cid))})))

(defn reason [f]
  (try (f) nil
       (catch #?(:clj Exception :cljs :default) e
         (or (:kotobase.disclosure-grant/reason (ex-data e))
             (:kotobase.authority-window/reason (ex-data e))))))

(deftest authorised-delivery-is-bound-and-replay-safe
  (let [o (options) delivered (d/release! o)]
    (is (= (:key-envelope o) (:key-envelope delivered)))
    (is (= :key-envelope-release-authorized (get-in delivered [:receipt :event])))
    (is (true? (verify (d/delivery-verification context (:request o) delivered policy))))
    (is (= :nonce-replayed (reason #(d/release! o))))
    (is (not (contains? (:receipt delivered) :key-envelope)))
    (is (not (contains? (first (:chain o)) :key-envelope)))))

(deftest narrowing-delegation-and-parent-cid
  (let [a (grant)
        b (grant {:issuer "alice" :recipient "bob" :recipient-key "bob-key"
                  :parent (id/value-cid (:grant a)) :delegation-depth 1})
        ctx (assoc context :principal "bob" :recipient-key "bob-key")]
    (is (= (:envelope b) (:key-envelope (d/release! (options [a b] ctx)))))
    (doseq [[change expected]
            [[{:parent (id/value-cid :other)} :parent-cid-mismatch]
             [{:issuer "mallory"} :wrong-delegator]
             [{:delegation-depth 2} :authority-amplification]
             [{:operations #{:decrypt :propose-update}} :authority-amplification]
             [{:expires-at "2026-09-06T14:00:00Z"} :time-amplification]
             [{:not-before "2026-09-06T10:00:00Z"} :time-amplification]]]
      (let [child (grant (merge (dissoc (:grant b) :signature :key-envelope-cid) change))]
        (is (= expected (reason #(d/release! (options [a child] ctx)))))))))

(deftest reject-before-any-release-or-log
  (doseq [[change expected]
          [[{:context (assoc context :owner "mallory")} :scope-mismatch]
           [{:context (assoc context :epoch 3)} :scope-mismatch]
           [{:context (assoc context :now "2026-09-06T13:00:00Z")} :request-expired]
           [{:verify! (constantly nil)} :signature-rejected]
           [{:authorize! (constantly nil)} :authority-denied]
           [{:consume-nonce! (constantly nil)} :nonce-replayed]
           [{:chain []} :invalid-chain]]]
    (let [writes (atom 0) o (assoc (merge (options) change)
                                 :commit! (fn [_] (swap! writes inc)))]
      (is (= expected (reason #(d/release! o))))
      (is (zero? @writes)))))

(deftest reject-tampering-and-envelope-substitution
  (let [o (options)]
    (is (= :signature-rejected
           (reason #(d/release! (assoc-in o [:request :nonce] "tampered")))))
    (is (= :request-mismatch
           (reason #(d/release! (assoc-in o [:request :recipient-key] "attacker")))))
    (is (= :key-envelope-mismatch
           (reason #(d/release! (assoc-in o [:key-envelope :sealed/ciphertext] [99])))))
    (is (= :invalid-shape
           (reason #(d/release! (assoc-in o [:chain 0 :unknown] true)))))))

(deftest refusing-persistence-withholds-envelope
  (doseq [[change expected]
          [[{:commit! (constantly nil)} :receipt-not-durable]
           [{:commit! (constantly {:receipt/durable? true :receipt/cid "wrong"})}
            :receipt-not-durable]
           [{:read! (constantly nil)} :receipt-readback-mismatch]
           [{:sign! (fn [_] {:key/id "other" :key/algorithm :fixture :signature/value "wrong"})}
            :receipt-signature-rejected]]]
    (is (= expected (reason #(d/release! (merge (options) change)))))))

(deftest remote-client-refuses-swapped-artifacts
  (let [o (options) delivery (d/release! o)]
    (doseq [changed [(assoc delivery :binding "wrong")
                     (assoc delivery :receipt/cid "wrong")
                     (assoc-in delivery [:key-envelope :sealed/ciphertext] [99])
                     (assoc-in delivery [:grant :recipient] "mallory")]]
      (is (some? (reason #(d/delivery-verification context (:request o) changed policy)))))))

(deftest public-key-information-is-not-a-signature
  (let [o (options)
        request (assoc (:request o) :signature {:key/id "alice" :key/algorithm :fixture
                                               :signature/value "alice-encryption-key"})]
    (is (= :signature-rejected (reason #(d/release! (assoc o :request request)))))))

(deftest even-owner-signed-crypto-downgrades-are-refused
  (doseq [change [{:envelope/algorithms [:x25519 :aes-256-gcm]}
                  {:envelope/hybrid? false}
                  {:envelope/kem? false}
                  {:envelope/binding "other-recipient"}
                  {:sealed/ciphertext []}]]
    (let [o (options)
          envelope (merge (:key-envelope o) change)
          g (sign :disclosure-grant "owner"
                  (assoc (first (:chain o)) :key-envelope-cid (id/value-cid envelope)))
          writes (atom 0)]
      (is (some? (reason #(d/release! (assoc o :chain [g] :request (request g context)
                                            :key-envelope envelope
                                            :commit! (fn [_] (swap! writes inc)))))))
      (is (zero? @writes)))))
