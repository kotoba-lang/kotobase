(ns kotobase.sealed-store
  "Fail-closed encrypted-at-rest adapter for remote Kotobase writes.

  The host owns cryptographic operations. This adapter proves that plaintext
  values never reach XRPC unless sealing, hybrid-policy admission, and
  ciphertext digest verification all succeed."
  (:require [kotoba.security.crypto-policy :as crypto]))

(def default-policy
  {:kotoba.security/crypto-policy-version 1
   :mode :hybrid-required
   :hybrid-epoch-floor 1})

(def payload-keys {:put :val :append :event})

(defn- present-ciphertext? [ciphertext]
  (and (some? ciphertext)
       (or (not (coll? ciphertext)) (seq ciphertext))))

(defn evaluate-seal
  [{:keys [seal-fn ciphertext-digest-fn crypto-policy]
    :or {crypto-policy default-policy}}
   plaintext]
  (let [sealed (when (ifn? seal-fn) (seal-fn plaintext))
        crypto-result (crypto/check-production-envelope crypto-policy sealed)
        computed-digest (when (and sealed (ifn? ciphertext-digest-fn))
                          (ciphertext-digest-fn (:sealed/ciphertext sealed)))
        violations (cond-> []
                     (not (ifn? seal-fn)) (conj :sealer-required)
                     (not (:valid? crypto-result)) (conj :hybrid-envelope)
                     (not (present-ciphertext? (:sealed/ciphertext sealed)))
                     (conj :ciphertext-required)
                     (not (and (some? computed-digest)
                               (= computed-digest
                                  (:sealed/ciphertext-digest sealed))))
                     (conj :ciphertext-digest))]
    {:sealed/allowed? (empty? violations)
     :sealed/violations violations
     :sealed/crypto crypto-result
     :sealed/value sealed}))

(defn seal-value! [options plaintext]
  (let [result (evaluate-seal options plaintext)]
    (when-not (:sealed/allowed? result)
      (throw (ex-info "kotobase sealed write denied" result)))
    (:sealed/value result)))

(defn- seal-put! [options put]
  (when-not (and (vector? put) (= 3 (count put)))
    (throw (ex-info "invalid sealed transaction put"
                    {:type :kotobase/invalid-transaction-put})))
  (update put 2 #(seal-value! options %)))

(defn- seal-append! [options append]
  (when-not (and (vector? append) (= 2 (count append)))
    (throw (ex-info "invalid sealed transaction append"
                    {:type :kotobase/invalid-transaction-append})))
  (update append 1 #(seal-value! options %)))

(defn seal-transaction!
  "Seal every value-bearing operation while preserving transaction metadata."
  [options request]
  (when-not (map? request)
    (throw (ex-info "invalid sealed transaction request"
                    {:type :kotobase/invalid-transaction-request})))
  (-> request
      (update :puts #(mapv (partial seal-put! options) (or % [])))
      (update :appends #(mapv (partial seal-append! options) (or % [])))))

(defn wrap-xrpc
  "Encrypt :put/:append and every value-bearing transaction payload before
  invoking xrpc. Any invalid item denies the entire batch before transport."
  [xrpc options]
  (fn [method params]
    (if-let [payload-key (get payload-keys method)]
      (xrpc method (update params payload-key #(seal-value! options %)))
      (if (= :transact method)
        (xrpc method (seal-transaction! options params))
        (xrpc method params)))))
