(ns kotobase.execution-keys-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.execution-identity :as id]
            [kotobase.execution-keys :as keys]
            [kotobase.governed-execution :as governed]
            [kotobase.governed-execution-test :as fixture]))

(def issuer-key
  {:key/id "key:issuer-1" :key/algorithm :toy-hmac :key/material "issuer-secret"})

(def executor-key
  {:key/id "key:executor-1" :key/algorithm :toy-hmac
   :key/material "executor-secret"})

(defn- toy-sign [{:keys [key/id key/algorithm key/material]} payload-cid]
  {:key/id id
   :key/algorithm algorithm
   :signature/value (str "sig:" material ":" payload-cid)})

(defn- toy-verify-with [{:keys [key payload-cid signature-value]}]
  (= signature-value (str "sig:" (:key/material key) ":" payload-cid)))

(def registry-table
  {{:role :issuer :tenant "acme" :epoch 7} [issuer-key]
   {:role :executor :tenant "acme" :epoch 7} [executor-key]})

(defn- verifier
  ([] (verifier (keys/static-registry registry-table)))
  ([registry] (keys/verifier {:registry registry
                              :verify-with toy-verify-with})))

(def manifest
  (let [payload (dissoc fixture/manifest :signature)]
    (assoc payload :signature (toy-sign issuer-key (id/value-cid payload)))))

(defn- options
  "The governed composition, signed and verified through a key registry."
  [journal & {:as overrides}]
  (merge
   (fixture/options journal
                    :manifest manifest
                    :verify (verifier)
                    :sign (fn [unsigned]
                            (toy-sign executor-key (id/value-cid unsigned))))
   overrides))

(defn- reason [f]
  (let [data (ex-data (try (f) nil
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs ExceptionInfo) e e)))]
    (or (:kotobase.execution-keys/reason data)
        (:kotobase.governed-execution/reason data))))

(deftest a-signature-names-the-key-that-made-it
  (let [journal (atom [])
        result (governed/execute! (options journal))
        receipt (:execution/receipt result)]
    (is (= fixture/served (:rows result)))
    (testing "and the receipt carries the envelope, not an opaque proof"
      (is (= keys/signature-keys (set (keys (:signature receipt)))))
      (is (= "key:executor-1" (:key/id (:signature receipt))))
      (is (= :toy-hmac (:key/algorithm (:signature receipt)))))
    (testing "an opaque proof is refused"
      (is (= :invalid-signature-envelope
             (reason #(governed/execute!
                       (options journal :sign (constantly "sig-anything")))))))))

(deftest the-registry-is-asked-about-this-request-not-about-itself
  ;; the structural half: a verifier that closed over its own idea of tenant
  ;; and epoch could be handed a request for another tenant or a superseded
  ;; epoch and never notice. It is told by the executor instead
  (let [asked (atom [])
        registry (fn [scope]
                   (swap! asked conj scope)
                   (get registry-table scope))]
    (governed/execute! (options (atom []) :verify (verifier registry)))
    (is (= [{:role :issuer :tenant "acme" :epoch 7}
            {:role :executor :tenant "acme" :epoch 7}]
           @asked))))

(deftest a-key-authorised-somewhere-else-is-not-authorised-here
  (let [journal (atom [])]
    (testing "a superseded epoch has its own keys, and none of them are these"
      ;; the request names epoch 8 and the host agrees it is current, so this
      ;; is not the revocation check — it is the key that was never registered
      ;; for the epoch actually being executed
      (is (= :no-key-registered
             (reason #(governed/execute!
                       (options journal
                                :request (assoc fixture/request
                                                :authority/epoch 8)
                                :manifest (let [payload (assoc
                                                         (dissoc manifest
                                                                 :signature)
                                                         :authority/epoch 8)]
                                            (assoc payload :signature
                                                   (toy-sign
                                                    issuer-key
                                                    (id/value-cid payload))))
                                :authority {:now "2026-09-02T14:30:00Z"
                                            :epoch 8
                                            :consume-nonce!
                                            (fixture/nonce-ledger)}))))))
    (testing "and so does another tenant"
      ;; the whole request moves tenant — envelope and query together — so
      ;; this is the registry refusing, not the scope binding
      (let [query (assoc-in fixture/query [:scope :tenant] "other-tenant")]
        (is (= :no-key-registered
               (reason #(governed/execute!
                         (options journal
                                  :query query
                                  :request (assoc fixture/request
                                                  :tenant "other-tenant"
                                                  :query/digest
                                                  (id/value-cid query)))))))))
    (testing "a registry that cannot answer has not authorised anything"
      ;; the most likely silent failure: a lookup that returns nothing looks
      ;; exactly like a lookup that said there is nothing wrong here
      (doseq [empty-answer [nil [] #{}]]
        (is (= :no-key-registered
               (reason #(governed/execute!
                         (options journal
                                  :verify (verifier
                                           (constantly empty-answer)))))))))))

(deftest the-key-and-the-algorithm-are-both-checked
  (let [journal (atom [])]
    (testing "a key id nobody registered"
      (is (= :key-not-authorised-here
             (reason #(governed/execute!
                       (options journal
                                :sign (fn [unsigned]
                                        (toy-sign (assoc executor-key :key/id
                                                         "key:borrowed")
                                                  (id/value-cid unsigned))))))))) 
    (testing "the same key id under a different algorithm is a different key"
      (is (= :algorithm-mismatch
             (reason #(governed/execute!
                       (options journal
                                :sign (fn [unsigned]
                                        (assoc (toy-sign executor-key
                                                         (id/value-cid unsigned))
                                               :key/algorithm :something-else))))))))
    (testing "and the cryptography still has to agree"
      ;; registered, right algorithm, wrong bytes
      (is (= :signature-not-verified
             (reason #(governed/execute!
                       (options journal
                                :sign (fn [_]
                                        (assoc (toy-sign executor-key "elsewhere")
                                               :key/id "key:executor-1")))))))) 
    (testing "and an empty proof is not a proof"
      (is (= :empty-signature
             (reason #(governed/execute!
                       (options journal
                                :sign (fn [unsigned]
                                        (assoc (toy-sign executor-key
                                                         (id/value-cid unsigned))
                                               :signature/value ""))))))))))

(deftest a-registry-that-is-wrong-is-refused-when-it-is-built
  (testing "the table's scopes are exact"
    (is (= :invalid-registry-scope
           (reason #(keys/static-registry {{:role :issuer :tenant "acme"} []}))))
    (is (= :unknown-role
           (reason #(keys/static-registry
                     {{:role :auditor :tenant "acme" :epoch 7} []}))))
    (is (= :invalid-registry-table (reason #(keys/static-registry [])))))
  (testing "and a registered key must be a whole key"
    (is (= :invalid-registered-key
           (reason #(governed/execute!
                     (options (atom [])
                              :verify (verifier
                                       (constantly [{:key/id "key:issuer-1"}]))))))))
  (testing "the verifier itself has no optional parts"
    (is (= :invalid-verifier-options
           (reason #(keys/verifier {:registry (constantly [])}))))
    (is (= :missing-verify-with
           (reason #(keys/verifier {:registry (constantly [])
                                    :verify-with nil}))))))
