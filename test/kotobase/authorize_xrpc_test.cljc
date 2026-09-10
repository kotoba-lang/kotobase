(ns kotobase.authorize-xrpc-test
  "ADR-2607280100 Step 3: the lattice is evaluated on READS too, and a label
  that was never declared stops looking like a label that passed.

  `authorize-xrpc` had no tests before this namespace. The four-axis wrapper
  is the only thing between a host transport and every remote kotobase
  operation, so the first thing each test below states is which direction it
  pins -- an allow that cannot be made to deny is not evidence of anything."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing] :include-macros true])
            [kotobase.kotobase :as kb]))

(defn- calls [] (atom []))

(defn- wrap
  "authorize-xrpc over a transport that records what reached it."
  [seen opts]
  (kb/authorize-xrpc (fn [method params] (swap! seen conj [method params]) :ok)
                     opts))

(def ^:private permissive {})

(defn- denied-type [f]
  (try (f) nil (catch #?(:clj Exception :cljs :default) e (:type (ex-data e)))))

;; ------------------------------------------------------------------ control

(deftest control-a-permissive-wrapper-passes-both-directions
  (testing "without this, every denial below could be denying for its own reasons"
    (let [seen (calls)
          call (wrap seen {:abac-policy permissive :abac-attributes {}})]
      (is (= :ok (call :get {:coll "c" :key "k"})))
      (is (= :ok (call :put {:coll "c" :key "k"})))
      (is (= 2 (count @seen)) "both reached the transport"))))

(deftest control-abac-still-denies-what-it-denied-before
  (let [seen (calls)
        call (wrap seen {:abac-policy {:subject/ids #{"alice"}}
                         :abac-attributes {:subject {:id "mallory"}}})]
    (is (= :kotobase/abac-denied (denied-type #(call :get {:coll "c" :key "k"}))))
    (is (empty? @seen) "denied before the transport is invoked")))

;; ----------------------------------------------- the read half of the lattice

(deftest a-read-now-produces-a-classification-decision
  (testing "before Step 3 a :get produced no lattice record at all"
    (let [audited (atom [])
          call (wrap (calls)
                     {:abac-policy permissive :abac-attributes {}
                      :classification-audit! #(swap! audited conj %)})]
      (call :get {:coll "c" :key "k"})
      (call :list {:coll "c"})
      (call :snapshot {})
      (is (= 3 (count @audited)) "every read is recorded, not only writes")
      (is (every? #(= :kotobase/read (:classification/action %)) @audited)))))

(deftest the-two-halves-of-the-lattice-are-labelled
  (let [audited (atom [])
        call (wrap (calls) {:abac-policy permissive :abac-attributes {}
                            :classification-audit! #(swap! audited conj %)})]
    (call :get {:coll "c" :key "k"})
    (call :transact {})
    (is (= [:kotobase/read :kotobase/write]
           (mapv :classification/action @audited))
        "no-read-up on reads, and writes keep no-write-down via flow-decision")))

;; -------------------------------------------- skipped is no longer passed

(deftest an-undeclared-classification-is-visible-in-the-decision
  (let [audited (atom [])
        call (wrap (calls) {:abac-policy permissive :abac-attributes {}
                            :classification-audit! #(swap! audited conj %)})]
    (call :get {:coll "c" :key "k"})
    (let [d (first @audited)]
      (is (false? (:classification/declared? d)))
      (is (false? (:classification/allowed? d))
          "abac could not have evaluated this; the record says so"))))

(deftest classification-required-denies-what-abac-silently-permits
  (testing "abac's required-rank is nil for an undeclared resource, and nil raises no violation"
    (let [attrs {:subject {:clearance :confidential}}   ; resource has no class
          seen (calls)
          without (wrap seen {:abac-policy permissive :abac-attributes attrs})
          with (wrap (calls) {:abac-policy permissive :abac-attributes attrs
                              :classification-required? true})]
      (is (= :ok (without :get {:coll "c" :key "k"}))
          "this is the fail-open the ADR calls problem 3, still true when not required")
      (is (= :kotobase/classification-undeclared
             (denied-type #(with :get {:coll "c" :key "k"})))))))

(deftest an-unrankable-label-is-not-a-label
  (let [call (wrap (calls)
                   {:abac-policy permissive
                    :abac-attributes {:subject {:clearance :confidential}
                                      :resource {:classification :top-sekrit}}
                    :classification-required? true})]
    (is (= :kotobase/classification-undeclared
           (denied-type #(call :get {:coll "c" :key "k"})))
        "declared but unrankable denies, the same as undeclared")))

(deftest declared-and-ranked-passes-and-abac-decides-the-comparison
  (testing "the boundary: clearance == classification is allowed"
    (let [seen (calls)
          call (wrap seen {:abac-policy permissive
                           :abac-attributes {:subject {:clearance :confidential}
                                             :resource {:classification :confidential}}
                           :classification-required? true})]
      (is (= :ok (call :get {:coll "c" :key "k"})))))
  (testing "one above it is denied, and by ABAC rather than by this layer"
    (let [call (wrap (calls) {:abac-policy permissive
                              :abac-attributes {:subject {:clearance :internal}
                                                :resource {:classification :restricted}}
                              :classification-required? true})]
      (is (= :kotobase/abac-denied
             (denied-type #(call :get {:coll "c" :key "k"})))
          "no-read-up is abac's judgment; this layer only ensures it was reachable")))
  (testing "and that denial is on a READ, which is the whole point of Step 3"
    (let [call (wrap (calls) {:abac-policy permissive
                              :abac-attributes {:subject {:clearance :internal}
                                                :resource {:classification :restricted}}})]
      (is (= :kotobase/abac-denied
             (denied-type #(call :list {:coll "c"})))))))

(deftest undeclared-is-reported-before-abac-is-blamed
  (testing "a missing label must not surface as a policy violation it never reached"
    (let [call (wrap (calls) {:abac-policy {:subject/ids #{"nobody"}}
                              :abac-attributes {:subject {:id "alice"}}
                              :classification-required? true})]
      (is (= :kotobase/classification-undeclared
             (denied-type #(call :get {:coll "c" :key "k"})))
          "both would deny; the undeclared reason is the actionable one"))))
