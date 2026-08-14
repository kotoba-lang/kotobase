(ns kotobase.classification-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.classification :as class*]))

(def schema
  {:person/did {:class :public}
   :mail/thread {:class :internal}
   :mail/participant {:class :personal :erasure-scope :person}
   :mail/body {:class :restricted :erasure-scope :person}})

(deftest an-unclassified-attribute-is-not-internal-by-default
  ;; a default is how personal data acquires the wrong class without anybody
  ;; deciding
  (is (class*/classified? schema))
  (let [errors (class*/schema-errors (assoc schema :mail/subject {}))]
    (is (some #(= :unclassified-attribute (:error %)) errors))
    (is (not (class*/classified? (assoc schema :mail/subject {}))))))

(deftest a-sensitive-value-may-not-live-in-the-chain
  ;; the chain preserves retracted facts on purpose (ADR-2607071610: that is
  ;; what an audit view is for), so a body written inline can never be erased.
  ;; A key can be destroyed; a datom cannot.
  (testing "inline is allowed up to :internal and no further"
    (is (class*/inline-allowed? :public))
    (is (class*/inline-allowed? :internal))
    (is (not (class*/inline-allowed? :personal)))
    (is (not (class*/inline-allowed? :restricted))))
  (testing "declaring a sensitive attribute inline is an error, not a warning"
    (is (some #(= :sensitive-value-stored-inline (:error %))
              (class*/schema-errors
               (assoc-in schema [:mail/body :inline?] true)))))
  (testing "and the attributes that may appear in the chain are enumerable"
    (is (= #{:person/did :mail/thread} (class*/inline-attributes schema)))))

(deftest erasure-needs-a-scope-or-it-cannot-be-answered
  ;; `erase this person's data` is unanswerable without knowing which unit's
  ;; key destruction erases it
  (is (some #(= :no-erasure-scope (:error %))
            (class*/schema-errors
             (assoc schema :mail/attachment {:class :restricted})))))

(deftest a-projection-is-refused-attribute-by-attribute
  (let [ok {:granted #{:public :internal :personal} :scopes #{:person}}]
    (is (= [] (class*/projection-errors schema [:person/did :mail/participant] ok)))

    (testing "a class the caller does not hold"
      (is (some #(= :class-not-granted (:error %))
                (class*/projection-errors schema [:mail/body] ok))))

    (testing "an erasure scope the caller cannot reach"
      (is (some #(= :erasure-scope-not-granted (:error %))
                (class*/projection-errors schema [:mail/participant]
                                          {:granted #{:personal} :scopes #{}}))))

    (testing "an attribute nobody classified is refused, not assumed"
      ;; a read of something nobody classified is a read nobody authorised
      (is (some #(= :attribute-not-in-schema (:error %))
                (class*/projection-errors schema [:mail/subject] ok))))))

(deftest class-order-consistency
  ;; Prevents configuration drift: if `classes` order changes, inline-threshold
  ;; must stay at :internal (index 1). This test fails if order is modified
  ;; without updating inline-threshold-index.
  ;;
  ;; The threshold is :internal (index 1). Classes <= :internal must be
  ;; inline-allowed, classes > :internal must not be.
  (testing "inline threshold is at :internal boundary"
    (is (class*/inline-allowed? :public)
        ":public must be inline-allowed")
    (is (class*/inline-allowed? :internal)
        ":internal must be inline-allowed (threshold)")
    (is (not (class*/inline-allowed? :personal))
        ":personal must NOT be inline-allowed")
    (is (not (class*/inline-allowed? :restricted))
        ":restricted must NOT be inline-allowed"))
  (testing "ordering is preserved"
    ;; If classes order changed (e.g. :personal and :restricted swapped),
    ;; the above assertions would fail because :personal would become
    ;; inline-allowed or :restricted would become inline-allowed incorrectly.
    (is (= [true true false false]
           (map class*/inline-allowed? [:public :internal :personal :restricted]))
        "Sensitivity order must be :public < :internal < :personal < :restricted")))
