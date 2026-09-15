(ns kotobase.deployment-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.deployment :as deployment]
            [kotobase.local :as local]
            [kotobase.store :as store]))

(defn- xrpc [backend]
  (fn [method params]
    (case method
      :put (store/-put backend (:coll params) (:key params) (:val params))
      :get (store/-get backend (:coll params) (:key params))
      :list (store/-list backend (:coll params))
      :append (store/-append backend (:stream params) (:event params))
      :read (store/-read backend (:stream params) (:since params))
      :snapshot (store/-snapshot backend params)
      :transact (store/-transact backend params))))

(def managed-evidence
  #{:tenant-auth :durable-object-store :backup-restore
    :service-observability})

(deftest standalone-construction-keeps-store-and-declaration-together
  (let [d (deployment/standalone)]
    (is (store/store? (deployment/store d)))
    (is (= :standalone
           (:kotobase.profile/id (deployment/declaration d))))
    (is (= d (deployment/require-guarantees d #{:graph-query})))))

(deftest remote-does-not-infer-managed-from-transport
  (let [transport (xrpc (local/local-store))]
    (testing "a legacy remote cannot even claim standalone atomicity"
      (is (thrown-with-msg?
           #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
           #"evidence is incomplete"
           (deployment/remote transport {}))))
    (testing "transactional remote defaults only to standalone"
      (let [d (deployment/remote transport {:transactional? true})]
        (is (= :standalone
               (:kotobase.profile/id (deployment/declaration d))))
        (is (thrown-with-msg?
             #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
             #"does not provide"
             (deployment/require-guarantees d #{:durable-hosting})))))))

(deftest managed-remote-requires-explicit-evidence
  (let [transport (xrpc (local/local-store))
        d (deployment/remote
           transport
           {:transactional? true
            :profile :managed
            :evidence managed-evidence
            :requires #{:tenant-isolation :durable-hosting}})]
    (is (= :managed
           (:kotobase.profile/id (deployment/declaration d))))
    (is (store/transactional-store? (deployment/store d)))))

(deftest consensus-cannot-be-selected-as-a-marketing-label
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
       #"evidence is incomplete"
       (deployment/remote
        (xrpc (local/local-store))
        {:transactional? true
         :profile :consensus
         :evidence managed-evidence}))))
