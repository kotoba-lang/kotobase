(ns kotobase.core-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.core :as kotobase]
            [kotobase.storage.memory :as memory]))

(deftest public-facade-hides-engine-and-storage-details
  (let [database (kotobase/open
                  {:storage (memory/memory-store)
                   :encrypt-fn identity
                   :decrypt-fn identity
                   :blind-fn pr-str
                   :visible? (constantly true)})]
    (is (string? (kotobase/transact!
                  database [["alice" "role" "admin"]])))
    (is (= #{{:s "alice" :p "role" :o "admin"}}
           (kotobase/q database ["alice" "role" nil])))))

(deftest public-facade-supports-the-immutable-canonical-route
  (let [database (kotobase/open
                  {:storage (memory/memory-store)
                   :encrypt-fn identity
                   :decrypt-fn identity
                   :blind-fn pr-str
                   :visible? (constantly true)})
        basis (kotobase/commit-at!
               database nil [["receipt" "status" "durable"]])]
    (is (nil? (kotobase/head database)))
    (is (= #{{:s "receipt" :p "status" :o "durable"}}
           (kotobase/q (kotobase/at-cid database basis)
                       ["receipt" "status" nil])))))
