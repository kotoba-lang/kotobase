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

(defn wrap-xrpc
  "Encrypt :put/:append payloads before invoking xrpc.

  Transaction batches require a purpose-built batch envelope and therefore
  fail closed here instead of accidentally forwarding nested plaintext."
  [xrpc options]
  (fn [method params]
    (if-let [payload-key (get payload-keys method)]
      (xrpc method (update params payload-key #(seal-value! options %)))
      (if (= :transact method)
        (throw (ex-info "sealed transaction envelope required"
                        {:type :kotobase/sealed-transaction-required}))
        (xrpc method params)))))
