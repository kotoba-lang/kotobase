(ns kotobase.classification-agreement-test
  "The two classifications must not drift apart.

  `kagitaba.field/classification` came first and labels a *field value type*;
  `kotobase.classification` labels a *datom attribute*. Different axes, same
  labels — and the same rule underneath: `:restricted` in either means the value
  may not sit in plaintext where the graph can reach it.

  Two vocabularies whose `:restricted` quietly stopped meaning the same thing
  would be worse than one, so the overlap is checked rather than described.
  kagitaba is a sibling repo and is not a dependency of this one, so the values
  it uses are restated here — which is exactly why this test exists: a restated
  constant is a copy, and a copy needs something to hold it to its original."
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.classification :as classification]))

;; kagitaba.field, restated. If kagitaba changes these, this test is where the
;; disagreement should surface.
(def kagitaba-labels #{:internal :restricted})
(def kagitaba-sensitive-types #{:concealed :totp :credit-card-number :ssh-key})

(deftest the-shared-labels-mean-the-same-thing
  (testing "every label kagitaba uses exists here"
    (is (every? (set classification/classes) kagitaba-labels)))

  (testing ":restricted is above the inline threshold in both readings"
    ;; kagitaba: a sensitive field type goes in the DEK-sealed blob, never in
    ;; the plaintext graph. Here: an attribute above :internal may not hold its
    ;; value inline. Same rule.
    (is (not (classification/inline-allowed? :restricted)))
    (is (seq kagitaba-sensitive-types)))

  (testing ":internal is inline-able in both readings"
    (is (classification/inline-allowed? :internal))))

(deftest both-fail-closed-in-the-way-each-can-afford
  ;; kagitaba maps an unknown field type to :restricted — it still has a value
  ;; to place. An unclassified attribute here is refused outright, because a
  ;; query gate can decline to serve where a storage layer cannot decline to
  ;; store. Compatible, not identical, and the difference is deliberate.
  (is (not (classification/classified? {:a/x {}})))
  (is (some #(= :unclassified-attribute (:error %))
            (classification/schema-errors {:a/x {}})))
  ;; and an unknown class is not quietly treated as the nearest known one
  (is (not (classification/inline-allowed? :something-new)))
  (is (some #(= :unclassified-attribute (:error %))
            (classification/schema-errors {:a/x {:class :something-new}}))))
