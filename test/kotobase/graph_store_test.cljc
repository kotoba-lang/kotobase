(ns kotobase.graph-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.graph-store :as graph]
            [kotobase.local :as local]))

(deftest transaction-database-values-and-retractions
  (let [s (local/local-store)
        tx1 (graph/transact! s {:tx-id "people-1"
                                :tx-data [[:db/add 1 :person/name "Ada"]
                                          [:db/add 1 :person/role :engineer]]})
        tx2 (graph/transact! s {:tx-id "people-2"
                                :tx-data [[:db/retract 1 :person/role :engineer]
                                          [:db/add 1 :person/role :founder]]})]
    (is (= 1 (:basis-t tx1)))
    (is (= 2 (:basis-t tx2)))
    (is (= [[1 :person/name "Ada" 1 true]
            [1 :person/role :engineer 1 true]]
           (:datoms (graph/db s 1))))
    (is (= [[1 :person/name "Ada" 1 true]
            [1 :person/role :founder 2 true]]
           (:datoms (graph/db s))))
    (is (= 2 (count (graph/since s 1))))))

(deftest query-surfaces-share-the-stored-db-value
  (let [s (local/local-store)
        _ (graph/transact! s {:tx-id "graph-1"
                              :tx-data [[:db/add 1 :person/name "Ada"]
                                        [:db/add 1 :person/knows 2]
                                        [:db/add 2 :person/name "Grace"]]})
        pattern '[[?a :person/name "Ada"]
                  [?a :person/knows ?friend]
                  [?friend :person/name ?name]]]
    (is (= [["Grace"]]
           (graph/q s {:language :datalog :find '[?name] :where pattern})))
    (is (= [["Grace"]]
           (graph/q s {:language :sparql :select '[?name] :where pattern})))
    (is (= [["Grace"]]
           (graph/q s {:language :cypher :return '[?name] :match pattern})))))

(deftest transaction-id-is-idempotent
  (let [s (local/local-store)
        request {:tx-id "same" :tx-data [[:db/add 1 :x/value 1]]}]
    (is (= (graph/transact! s request) (graph/transact! s request)))
    (is (= 1 (count (graph/history s))))))

(deftest git-shares-the-graph-history
  (let [s (local/local-store)]
    (graph/transact-git! s "git-1"
                         {:oid "abc" :type :commit :bytes 12 :parents ["def"]})
    (is (= [["def"]]
           (graph/q s {:language :git :find '[?parent]
                       :where '[["abc" :git.commit/parent ?parent]]})))))

(deftest non-transactional-store-fails-closed
  (let [s (reify kotobase.store/IStore
            (-put [_ _ _ v] v) (-get [_ _ _] nil) (-list [_ _] [])
            (-append [_ _ e] e) (-read [_ _ _] []))]
    (is (= :graph/transactional-store-required
           (:problem
            (ex-data
             (try (graph/transact! s {:tx-id "x" :tx-data []})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core.ExceptionInfo) e e))))))))
