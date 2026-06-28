(ns kotobase.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.store :as st]
            [kotobase.local :as local]
            [kotobase.kotobase :as kb]
            [kotobase.contract :as contract]))

(defn- mock-xrpc
  "An xrpc fn that faithfully routes kotobase XRPC methods to a backing LocalStore —
  stands in for the real kotobase.net so the SAME contract proves KotobaseStore's
  op→XRPC mapping is correct."
  [backend]
  (fn [method params]
    (case method
      :put    (st/-put backend (:coll params) (:key params) (:val params))
      :get    (st/-get backend (:coll params) (:key params))
      :list   (st/-list backend (:coll params))
      :append (st/-append backend (:stream params) (:event params))
      :read   (st/-read backend (:stream params) (:since params)))))

(deftest local-store-satisfies-contract
  (testing "the in-process LocalStore is a correct IStore"
    (contract/verify (local/local-store)
                     (fn [ok? label] (is ok? (str "local: " label))))))

(deftest kotobase-store-equals-local
  (testing "KotobaseStore over a faithful xrpc reproduces LocalStore (≡ oracle)"
    (contract/verify (kb/kotobase-store (mock-xrpc (local/local-store)))
                     (fn [ok? label] (is ok? (str "kotobase: " label))))))

(deftest snapshot-is-a-value
  (testing "a LocalStore snapshots to a plain value for checkpoint/cloud-push"
    (let [s (local/local-store)]
      (st/-put s "c" "k" 1)
      (st/-append s "str" {:x 1})
      (let [snap (local/snapshot s)]
        (is (= 1 (get-in snap [:docs "c" "k"])))
        (is (= 1 (count (get-in snap [:streams "str"]))))
        (is (= 1 (:seq snap)))))))

(deftest xrpc-path-mapping
  (testing "store methods map to kotoba XRPC paths"
    (is (= "net.kotobase.store.append" (kb/xrpc-method->path :append)))))
