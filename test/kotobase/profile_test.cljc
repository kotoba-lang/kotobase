(ns kotobase.profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.local :as local]
            [kotobase.profile :as profile]))

(def managed-evidence
  #{:tenant-auth :durable-object-store :backup-restore
    :service-observability})

(deftest standalone-is-inferred-only-from-a-transactional-store
  (let [declaration (profile/declare-profile {:profile :standalone
                                              :store (local/local-store)})]
    (is (= :standalone (:kotobase.profile/id declaration)))
    (is (contains? (:kotobase.profile/guarantees declaration) :graph-query))
    (is (not (contains? (:kotobase.profile/guarantees declaration)
                        :durable-hosting)))))

(deftest higher-profile-names-do-not-create-evidence
  (testing "managed fails closed without operational evidence"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"evidence is incomplete"
         (profile/declare-profile {:profile :managed :store (local/local-store)}))))
  (testing "consensus fails even when all managed evidence is supplied"
    (try
      (profile/declare-profile {:profile :consensus
                                :store (local/local-store)
                                :evidence managed-evidence})
      (is false "consensus declaration must fail without replica/QC evidence")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) error
        (is (contains? (:missing (ex-data error)) :verified-votes))
        (is (contains? (:missing (ex-data error)) :real-transport))))))

(deftest managed-profile-negotiates-only-its-actual-guarantees
  (let [declaration (profile/declare-profile {:profile :managed
                                              :store (local/local-store)
                                              :evidence managed-evidence})]
    (is (= declaration
           (profile/require-guarantees declaration
                                       #{:graph-query :durable-hosting})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"does not provide"
         (profile/require-guarantees declaration #{:three-chain-finality})))))

(deftest decentralized-profile-needs-the-complete-evidence-chain
  (let [all-evidence (profile/required-evidence :decentralized)
        declaration (profile/declare-profile {:profile :decentralized
                                              :store (local/local-store)
                                              :evidence all-evidence})]
    (is (contains? (:kotobase.profile/guarantees declaration)
                   :f1-byzantine-finality))
    (is (contains? (:kotobase.profile/guarantees declaration)
                   :atomic-local-transaction))))

(deftest unknown-profile-is-rejected
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
       #"unknown Kotobase profile"
       (profile/declare-profile {:profile :magic :store (local/local-store)}))))
