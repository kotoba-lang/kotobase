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
   :unseal-fn (fn [sealed]
                (get-in sealed [:sealed/private :plaintext]))
   :ciphertext-digest-fn
   (fn [[tag digest]] (when (= :encrypted tag) (str "digest:" digest)))})

(defn roundtrip-seal [plaintext]
  (assoc (good-seal plaintext) :sealed/private {:plaintext plaintext}))

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

(deftest transaction-seals-every-value-and-preserves-control-fields
  (let [calls (atom [])
        remote (sealed/wrap-xrpc
                (fn [method params] (swap! calls conj [method params]) :ok)
                options)
        request {:tx-id "tx-1" :expected-revision 4
                 :puts [["vault" "one" {:password "a"}]
                        ["vault" "two" {:password "b"}]]
                 :deletes [["vault" "old"]]
                 :appends [["audit" {:secret "event"}]]}]
    (is (= :ok (remote :transact request)))
    (is (= "tx-1" (get-in @calls [0 1 :tx-id])))
    (is (= 4 (get-in @calls [0 1 :expected-revision])))
    (is (= [["vault" "old"]] (get-in @calls [0 1 :deletes])))
    (is (every? #(= :encrypted (get-in % [2 :sealed/ciphertext 0]))
                (get-in @calls [0 1 :puts])))
    (is (= :encrypted
           (get-in @calls [0 1 :appends 0 1 :sealed/ciphertext 0])))))

(deftest one-invalid-batch-value-denies-the-entire-xrpc
  (let [calls (atom 0)
        seal-calls (atom 0)
        bad-options
        (assoc options :seal-fn
               (fn [value]
                 (if (= 2 (swap! seal-calls inc))
                   (assoc (good-seal value) :envelope/hybrid? false)
                   (good-seal value))))
        remote (sealed/wrap-xrpc (fn [_ _] (swap! calls inc)) bad-options)]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"kotobase sealed write denied"
                          (remote :transact
                                  {:puts [["vault" "one" :a]
                                          ["vault" "two" :b]]
                                   :appends []})))
    (is (zero? @calls))))

(deftest malformed-batch-shape-fails-closed
  (let [calls (atom 0)
        remote (sealed/wrap-xrpc (fn [_ _] (swap! calls inc)) options)]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"invalid sealed transaction put"
                          (remote :transact {:puts [["missing-value"]]})))
    (is (zero? @calls))))

(deftest production-store-factory-applies-sealing-to-put
  (let [calls (atom [])
        store (kb/kotobase-store
               (fn [method params] (swap! calls conj [method params]) :ok)
               {:sealed-store-options options})]
    (is (= :ok (st/-put store "vault" "one" {:secret true})))
    (is (= :encrypted (get-in @calls [0 1 :val :sealed/ciphertext 0])))
    (is (nil? (get-in @calls [0 1 :val :secret])))))

(deftest sealed-read-opens-only-after-envelope-verification
  (let [remote (sealed/wrap-xrpc
                (fn [method _]
                  (case method
                    :get (roundtrip-seal {:secret "value"})
                    :read [(assoc (roundtrip-seal {:tx-data [[:db/add 1 :x 2]]})
                                  :seq 7)]))
                options)]
    (is (= {:secret "value"} (remote :get {:coll "c" :key "k"})))
    (is (= [{:tx-data [[:db/add 1 :x 2]] :seq 7}]
           (remote :read {:stream "s" :since 0})))))

(deftest plaintext-and-tampered-remote-reads-fail-closed
  (doseq [response [{:secret "plaintext"}
                    (assoc (roundtrip-seal {:secret true})
                           :sealed/ciphertext-digest "wrong")]]
    (let [remote (sealed/wrap-xrpc (fn [_ _] response) options)]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                            #"kotobase sealed read denied"
                            (remote :get {:coll "c" :key "k"}))))))

(deftest snapshot-and-transaction-receipt-open-value-bearing-members
  (let [remote (sealed/wrap-xrpc
                (fn [method _]
                  (case method
                    :snapshot {:revision 3
                               :docs {"c" {"k" (roundtrip-seal {:v 1})}}
                               :streams {"s" [(assoc (roundtrip-seal {:v 2})
                                                     :seq 9)]}}
                    :transact {:tx-id "tx" :revision 4
                               :appends [["s" (assoc (roundtrip-seal {:v 3})
                                                      :seq 10)]]}))
                (assoc options :seal-fn roundtrip-seal))]
    (is (= {:revision 3 :docs {"c" {"k" {:v 1}}}
            :streams {"s" [{:v 2 :seq 9}]}}
           (remote :snapshot {:collections ["c"] :streams ["s"]})))
    (is (= {:tx-id "tx" :revision 4 :appends [["s" {:v 3 :seq 10}]]}
           (remote :transact {:tx-id "tx" :expected-revision 3
                              :puts [] :deletes [] :appends []})))))
