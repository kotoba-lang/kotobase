(ns kotobase.erasure-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.erasure :as erasure]))

(def schema
  {:person/did {:class :public}
   :mail/thread {:class :internal}
   :mail/body {:class :restricted :erasure-scope :person}
   :case/note {:class :personal :erasure-scope :case}})

(deftest a-scope-with-no-key-is-a-deletion-request-with-nothing-to-destroy
  (is (= [] (erasure/scope-errors schema {:person "k-person" :case "k-case"})))
  (testing "and it fails here rather than when somebody asks"
    (is (some #(= :erasure-scope-has-no-key (:error %))
              (erasure/scope-errors schema {:person "k-person"}))))
  (testing "a key bound to nothing is a scope somebody believes is covered"
    (is (some #(= :key-bound-to-no-scope (:error %))
              (erasure/scope-errors schema {:person "k1" :case "k2"
                                            :invoice "k3"}))))
  (is (some #(= :key-id-must-be-a-non-empty-string (:error %))
            (erasure/scope-errors schema {:person "" :case "k2"}))))

(deftest erased-and-missing-must-not-look-the-same
  ;; one is a completed obligation with a record, the other is a fault. A
  ;; reader who cannot tell them apart reports a broken store as a fulfilled
  ;; deletion request, or the reverse.
  (let [state (fn [m] (:content/state (erasure/resolve-reference m)))
        base {:address "bafy-body" :key-id "k-person"}]
    (is (= :available (state (assoc base :key-state :active))))
    (testing "retired for new sealing is still readable — that is why the state exists"
      (is (= :available (state (assoc base :key-state :decrypt-or-verify-only)))))
    (testing "revoked withholds; it does not claim erasure"
      ;; a revoked key still exists, so `erased` would be a false claim on a
      ;; compliance record
      (is (= :withheld (state (assoc base :key-state :revoked))))
      (is (= :withheld (state (assoc base :key-state :preactive)))))
    (testing "destroyed is erased, and carries when"
      (let [answer (erasure/resolve-reference
                    (assoc base :key-state :destroyed :destroyed-at "2026-07-30"))]
        (is (= :erased (:content/state answer)))
        (is (= "2026-07-30" (:content/erased-at answer)))
        ;; the hash stays: a dangling reference is the honest record that a
        ;; fact existed here and its content is gone
        (is (= "bafy-body" (:content/address answer)))))
    (testing "sealed bytes with no key named is neither, and says so"
      (is (= :invalid (state (dissoc base :key-id))))
      (is (= :no-key-id (:content/reason
                         (erasure/resolve-reference (dissoc base :key-id)))))
      (is (= :invalid (state (assoc base :key-state :something-new)))))))

(deftest a-plan-names-what-destroying-a-key-will-not-reach
  (let [plan (erasure/erasure-plan schema {:person "k-person" :case "k-case"}
                                   #{:person})]
    (is (= #{"k-person"} (:erasure/keys plan)))
    (is (= #{:mail/body} (:erasure/attributes plan)))
    (is (:erasure/complete? plan)))

  (testing "an inline attribute inside the scope is named, not silently missed"
    ;; destroying the key does nothing to it and it stays in the chain forever,
    ;; so a plan that omitted it would promise an erasure it cannot perform
    (let [leaky (assoc schema :mail/subject {:class :internal
                                             :erasure-scope :person})
          plan (erasure/erasure-plan leaky {:person "k-person" :case "k-case"}
                                     #{:person})]
      (is (= #{:mail/subject} (:erasure/not-erasable-inline plan)))
      (is (not (:erasure/complete? plan))))))

(deftest destruction-does-not-happen-here
  ;; a plane that could destroy a key from a query path would be a plane that
  ;; can erase evidence. The plan returns key ids; the vault destroys, under
  ;; its own governor and its own ledger.
  (is (every? #(not (re-find #"destroy!|shred!|erase!" (name %)))
              (keys (ns-publics 'kotobase.erasure)))))
