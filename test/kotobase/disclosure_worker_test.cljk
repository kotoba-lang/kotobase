(ns kotobase.disclosure-worker-test
  (:require [cljs.test :refer [deftest is async]]
            [kotobase.disclosure-grant :as d]
            [kotobase.disclosure-grant-test :as f]
            [kotobase.execution-identity :as id]
            ["node:crypto" :as crypto]))

(defn real-options []
  (let [registry (into {} (for [p ["owner" "alice" "executor"]]
                           [p (crypto/generateKeyPairSync "ed25519")]))
        sign (fn [kind p record]
               {:key/id p :key/algorithm :ed25519
                :signature/value
                (.toString (crypto/sign nil (js/Buffer.from (d/signing-cid kind record))
                                         (.-privateKey (get registry p))) "base64")})
        unsigned (:grant (f/grant))
        grant (assoc unsigned :signature (sign :disclosure-grant "owner" unsigned))
        request (f/request grant f/context)
        request (assoc request :signature (sign :disclosure-request "alice" request))
        options (assoc (f/options) :chain [grant] :request request
                       :sign! (fn [{:keys [unsigned]}]
                                (sign :disclosure-delivery "executor" unsigned))
                       :verify! (fn [{:keys [principal signature payload-cid]}]
                                  (let [key (get registry principal)]
                                    (and (some? key) (= principal (:key/id signature))
                                         (= :ed25519 (:key/algorithm signature))
                                         (crypto/verify nil (js/Buffer.from payload-cid)
                                                        (.-publicKey key)
                                                        (js/Buffer.from (:signature/value signature)
                                                                        "base64"))))))]
    (reduce (fn [o k] (update o k (fn [port] (fn [x] (.then (js/Promise.resolve nil)
                                                           (fn [_] (port x)))))))
            options [:verify! :authorize! :consume-nonce! :sign! :commit! :read!])))

(deftest actual-ed25519-worker-delivery
  (async done
    (let [o (real-options)]
      (-> (d/release-async! o)
          (.then (fn [delivery]
                   (is (= (:grant/cid delivery) (id/value-cid (first (:chain o)))))
                   ((:verify! o) (d/delivery-verification f/context (:request o) delivery f/policy))))
          (.then #(is (true? %)))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest tampering-fails-real-signature-before-writing
  (async done
    (let [writes (atom 0)
          o (-> (real-options)
                (assoc-in [:request :nonce] "not-signed")
                (assoc :commit! (fn [_] (swap! writes inc))))]
      (-> (d/release-async! o)
          (.then (fn [_] (is false "tampered request released")))
          (.catch (fn [e]
                    (is (= :signature-rejected
                           (some #(get (ex-data %) :kotobase.disclosure-grant/reason)
                                 (take-while some? (iterate ex-cause e)))))
                    (is (zero? @writes))))
          (.finally done)))))

(deftest worker-waits-for-commit-and-readback
  (async done
    (let [journal (atom nil) unblock (atom nil) released? (atom false)
          o (assoc (real-options)
                   :commit! (fn [receipt]
                              (reset! journal receipt)
                              (js/Promise. (fn [resolve _] (reset! unblock resolve))))
                   :read! (fn [_] (js/Promise.resolve @journal)))
          result (-> (d/release-async! o)
                     (.then (fn [x] (reset! released? true) x)))]
      ;; Let every Promise stage before the blocked durable commit run.
      (js/setTimeout
       (fn []
         (is (some? @journal))
         (is (false? @released?))
         (if-let [resolve @unblock]
           (resolve {:receipt/durable? true :receipt/cid (id/value-cid @journal)})
           (is false "commit was not reached"))
         (-> result
             (.then #(is (= (:key-envelope o) (:key-envelope %))))
             (.catch #(is false (str %)))
             (.finally done))) 20))))

(deftest rejected-async-storage-does-not-release
  (async done
    (-> (d/release-async! (assoc (real-options) :read! (fn [_] (js/Promise.reject (js/Error. "offline")))))
        (.then (fn [_] (is false "released without readback")))
        (.catch #(is (= "offline" (.-message %))))
        (.finally done))))

(deftest sync-entry-refuses-promise-ports
  (is (= :async-port-in-sync-api
         (f/reason #(d/release! (assoc (f/options) :verify! (fn [_] (js/Promise.resolve true))))))))
