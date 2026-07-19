(ns kotobase.sealed-store-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.kotobase :as kb]
            [kotobase.sealed-store :as sealed]
            [kotobase.store :as st]))

(defn good-seal [plaintext]
  {:envelope/algorithms [:x25519 :ml-kem-768 :aes-256-gcm]
   :envelope/provider {:provider/id :host-crypto
                       :provider/fips-validated false}
   :envelope/epoch 2 :envelope/kem? true :envelope/hybrid? true
   :sealed/ciphertext [:encrypted (hash plaintext)]
   :sealed/ciphertext-digest (str "digest:" (hash plaintext))})

(def options
  {:seal-fn good-seal
   :ciphertext-digest-fn
   (fn [[tag digest]] (when (= :encrypted tag) (str "digest:" digest)))})

(deftest plaintext-never-reaches-remote-write
  (let [calls (atom [])
        remote (sealed/wrap-xrpc
                (fn [method params] (swap! calls conj [method params]) :ok)
                options)
        secret {:password "do-not-forward"}]
    (is (= :ok (remote :put {:coll "vault" :key "one" :val secret})))
    (is (= :ok (remote :append {:stream "audit" :event secret})))
    (is (= 2 (count @calls)))
    (is (every? #(not= secret %)
                [(get-in @calls [0 1 :val]) (get-in @calls [1 1 :event])]))
    (is (= [:encrypted (hash secret)]
           (get-in @calls [0 1 :val :sealed/ciphertext])))))

(deftest downgrade-and-tamper-fail-before-xrpc
  (doseq [bad-options
          [(assoc options :seal-fn #(assoc (good-seal %)
                                           :envelope/algorithms [:x25519]
                                           :envelope/hybrid? false))
           (assoc options :seal-fn #(assoc (good-seal %) :envelope/epoch 0))
           (assoc options :seal-fn #(assoc (good-seal %) :sealed/ciphertext []))
           (assoc options :ciphertext-digest-fn (constantly "wrong"))
           (dissoc options :seal-fn)
           (dissoc options :ciphertext-digest-fn)]]
    (let [calls (atom 0)
          remote (sealed/wrap-xrpc
                  (fn [_ _] (swap! calls inc)) bad-options)]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                            #"kotobase sealed write denied"
                            (remote :put {:coll "vault" :key "one"
                                          :val {:secret true}})))
      (is (zero? @calls)))))

(deftest transaction-cannot-bypass-payload-sealing
  (let [calls (atom 0)
        remote (sealed/wrap-xrpc (fn [_ _] (swap! calls inc)) options)]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"sealed transaction envelope required"
                          (remote :transact {:puts [["vault" "one" :secret]]})))
    (is (zero? @calls))))

(deftest production-store-factory-applies-sealing-to-put
  (let [calls (atom [])
        store (kb/kotobase-store
               (fn [method params] (swap! calls conj [method params]) :ok)
               {:sealed-store-options options})]
    (is (= :ok (st/-put store "vault" "one" {:secret true})))
    (is (= :encrypted (get-in @calls [0 1 :val :sealed/ciphertext 0])))
    (is (nil? (get-in @calls [0 1 :val :secret])))))
