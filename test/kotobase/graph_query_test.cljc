(ns kotobase.graph-query-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.graph-query :as graph]))

(def people
  [[1 :person/name "Ada" 1 true]
   [1 :person/knows 2 1 true]
   [2 :person/name "Grace" 1 true]
   [2 :person/name "old" 0 false]])

(deftest query-surfaces-share-one-datom-semantics
  (let [pattern '[[?person :person/name "Ada"]]
        expected [[1]]]
    (is (= expected (graph/execute people
                                   {:language :datalog :find '[?person]
                                    :where pattern})))
    (is (= expected (graph/execute people
                                   {:language :sparql :select '[?person]
                                    :where pattern})))
    (is (= expected (graph/execute people
                                   {:language :cypher :return '[?person]
                                    :match pattern})))))

(deftest joins-and-retractions-have-datomic-behaviour
  (is (= [["Grace"]]
         (graph/execute people
                        {:language :sparql :select '[?name]
                         :where '[[?ada :person/name "Ada"]
                                  [?ada :person/knows ?friend]
                                  [?friend :person/name ?name]]}))))

(deftest database-values-respect-retractions-and-time
  (let [history [[1 :status/value :draft 1 true]
                 [1 :status/value :draft 2 false]
                 [1 :status/value :published 2 true]]]
    (is (= [[1 :status/value :draft 1 true]] (graph/as-of history 1)))
    (is (= [[1 :status/value :published 2 true]] (graph/current history)))
    (is (= 2 (count (graph/since history 1))))))

(deftest git-is-a-projection-not-a-second-source-of-truth
  (testing "objects and refs become queryable datoms"
    (is (= [["a" :git.object/type :commit]
            ["a" :git.object/bytes 42]
            ["a" :git.commit/parent "p"]]
           (graph/git-object-datoms
            {:oid "a" :type :commit :bytes 42 :parents ["p"]})))
    (is (= [["refs/heads/main" :git.ref/target "a"]]
           (graph/git-object-datoms
            {:ref "refs/heads/main" :target "a"})))))

(deftest unsupported-language-fails-closed
  (is (= :graph/unsupported-language
         (:problem
          (ex-data
           (try (graph/compile-query {:language :sql :find '[] :where []})
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo
                          :cljs cljs.core.ExceptionInfo) e e)))))))
