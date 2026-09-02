(ns kotobase.metering-test
  "What the meter counts, against a provider that is also counting.

  A meter is the easiest thing in this repository to write as theatre: it can
  return plausible numbers forever and nothing disagrees with it. So every
  assertion here is either against a second, independent count taken inside
  the provider, or against a control where the work changes and the number has
  to move with it."
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.core :as core]
            [kotobase.governed-read :as governed-read]
            [kotobase.governed-read-test :as fixture]
            [kotobase.metering :as metering]
            [kotobase.storage.core :as storage]
            [kotobase.storage.memory :as memory]))

(defn- observed-backend
  "A provider that counts its own calls, so the meter can be checked against
  something other than itself."
  [delegate]
  (let [seen (atom {:calls 0 :bytes 0})]
    {:seen seen
     :backend
     (reify
       storage/IBlockStore
       (-put-blocks! [_ blocks] (storage/-put-blocks! delegate blocks))
       (-get-blocks [_ cids]
         (let [result (storage/-get-blocks delegate cids)]
           (swap! seen (fn [s]
                         (-> s
                             (update :calls inc)
                             (update :bytes +
                                     (reduce + 0 (map #(alength ^bytes %)
                                                      (vals result)))))))
           result))

       storage/IRefStore
       (-read-ref [_ n] (storage/-read-ref delegate n))
       (-compare-and-set-ref! [_ n e x]
         (storage/-compare-and-set-ref! delegate n e x))

       storage/IBackendCapabilities
       (-capabilities [_] (storage/-capabilities delegate)))}))

(defn- database [backend]
  (core/open {:storage backend
              :encrypt-fn identity
              :decrypt-fn identity
              :blind-fn pr-str
              :visible? (constantly true)}))

(deftest the-meter-agrees-with-the-provider-it-wraps
  (let [{:keys [seen backend]} (observed-backend (memory/memory-store))
        metered (metering/meter backend)
        db (database (:backend metered))
        basis (core/commit-at! db nil [["INV-42" "invoice/id" "INV-42"]])
        ;; whatever the commit itself read, taken before the window opens
        opened ((:read metered))
        cost (metering/span metered :unmeasured)]
    ;; a read the meter is watching
    (core/q (core/at-cid db basis) ["INV-42" nil nil])
    (let [measured (cost)
          provider @seen]
      (testing "the counts are the provider's own, not the meter's opinion"
        (is (pos? (:requests measured)))
        (is (= (:calls provider) (+ (:requests opened) (:requests measured))))
        (is (= (:bytes provider) (+ (:bytes opened) (:bytes measured)))))
      (testing "and a serial read waits once per request"
        ;; every call here is issued after the previous one returned, so each
        ;; is its own hop. A batch would collapse them, which is the point
        (is (= (:requests measured) (:dependent-hops measured))))
      (testing "and the profile is the caller's word, marked as such"
        (is (= :unmeasured (:cache-profile measured)))))))

(deftest a-span-measures-one-execution-not-the-database
  (let [metered (metering/meter (memory/memory-store))
        db (database (:backend metered))
        basis (core/commit-at! db nil [["INV-42" "invoice/id" "INV-42"]])]
    (core/q (core/at-cid db basis) ["INV-42" nil nil])
    (let [cost (metering/span metered :unmeasured)
          before (cost)]
      (testing "a window opened after the work sees none of it"
        (is (= {:requests 0 :dependent-hops 0 :bytes 0
                :cache-profile :unmeasured}
               before)))
      (core/q (core/at-cid db basis) ["INV-42" nil nil])
      (testing "and sees the work done inside it"
        (is (pos? (:requests (cost))))))))

(deftest the-cost-in-a-receipt-moves-with-the-work
  ;; the property a declared cost could never have. Two reads of different
  ;; sizes through the same path must produce different numbers in the
  ;; receipts, and neither caller said anything about cost
  (let [receipt-for
        (fn [facts]
          (let [metered (metering/meter (memory/memory-store))
                db (database (:backend metered))
                evaluated (atom false)
                basis (core/commit-at! db nil facts)
                cost (metering/span metered :unmeasured)]
            (:execution/receipt
             (governed-read/read!
              (fixture/request-at db basis evaluated
                                  :cost cost
                                  :evaluate!
                                  (fn [_ _]
                                    (reset! evaluated true)
                                    (core/q (core/at-cid db basis)
                                            ["INV-42" nil nil])
                                    fixture/served))))))
        small (:cost (receipt-for [["INV-42" "invoice/id" "INV-42"]]))
        large (:cost (receipt-for (into [["INV-42" "invoice/id" "INV-42"]]
                                        (map (fn [n]
                                               [(str "INV-" n) "invoice/id"
                                                (apply str (repeat 200 \x))]))
                                        (range 40))))]
    (is (pos? (:requests small)))
    (is (pos? (:bytes small)))
    (is (> (:bytes large) (:bytes small))
        "more data read means more bytes in the receipt")
    (testing "and every field is a non-negative integer the contract accepts"
      (is (every? nat-int? ((juxt :requests :dependent-hops :bytes) large))))))

(deftest a-hop-is-a-wait-not-a-request
  ;; the assertion that gives `:dependent-hops` its meaning. Serial reads make
  ;; the two numbers equal, so a meter that simply copied `:requests` would
  ;; pass every other test in this file. Here two reads are genuinely in
  ;; flight at once and the caller waited exactly once
  (let [entered (java.util.concurrent.CountDownLatch. 2)
        release (java.util.concurrent.CountDownLatch. 1)
        delegate (reify
                   storage/IBlockStore
                   (-put-blocks! [_ _] nil)
                   (-get-blocks [_ _]
                     (.countDown entered)
                     (.await release)
                     {})

                   storage/IRefStore
                   (-read-ref [_ _] nil)
                   (-compare-and-set-ref! [_ _ _ _] {:published? false})

                   storage/IBackendCapabilities
                   (-capabilities [_] #{}))
        {:keys [backend read]} (metering/meter delegate)
        overlapping [(future (storage/-get-blocks backend ["a"]))
                     (future (storage/-get-blocks backend ["b"]))]]
    ;; both are inside the provider before either can return
    (.await entered)
    (.countDown release)
    (run! deref overlapping)
    (is (= 2 (:requests (read))))
    (is (= 1 (:dependent-hops (read))))
    (testing "and a read issued after those returned is a second wait"
      (storage/-get-blocks backend ["c"])
      (is (= 3 (:requests (read))))
      (is (= 2 (:dependent-hops (read)))))))

(defn- memoizing
  "A cache with the only property the profile depends on: it answers a second
  request for the same CIDs without asking the provider."
  [delegate]
  (let [seen (atom {})]
    (reify
      storage/IBlockStore
      (-put-blocks! [_ blocks] (storage/-put-blocks! delegate blocks))
      (-get-blocks [_ cids]
        (let [held (select-keys @seen cids)]
          (if (= (count held) (count cids))
            held
            (let [fetched (storage/-get-blocks delegate cids)]
              (swap! seen merge fetched)
              fetched))))

      storage/IRefStore
      (-read-ref [_ n] (storage/-read-ref delegate n))
      (-compare-and-set-ref! [_ n e x]
        (storage/-compare-and-set-ref! delegate n e x))

      storage/IBackendCapabilities
      (-capabilities [_] (storage/-capabilities delegate)))))

(deftest a-cache-profile-takes-two-meters
  ;; one meter cannot measure this: a cache in front of it is invisible and a
  ;; cache behind it is the provider's business. Two can, and the same cache
  ;; is cold on the read that fills it and hot on the next one — which is why
  ;; the field is worth measuring per execution rather than configuring
  (let [below (metering/meter (memory/memory-store))
        above (metering/meter (memoizing (:backend below)))
        db (database (:backend above))
        basis (core/commit-at! db nil [["INV-42" "invoice/id" "INV-42"]])
        meters {:above above :below below}
        first-cost (metering/tiered-span meters)]
    (core/q (core/at-cid db basis) ["INV-42" nil nil])
    (let [cold (first-cost)]
      (testing "the read that fills the cache paid for all of it"
        (is (= :cold (:cache-profile cold)))
        (is (pos? (:requests cold))))
      (testing "and the counts in the receipt are the provider's, not the cache's"
        ;; what the execution actually cost is what reached the provider
        (is (= (:requests cold) (:requests ((:read below))))))
      (let [second-cost (metering/tiered-span meters)]
        (core/q (core/at-cid db basis) ["INV-42" nil nil])
        (let [hot (second-cost)]
          (testing "the next one paid for none"
            (is (= :hot (:cache-profile hot)))
            (is (zero? (:requests hot)))
            (is (zero? (:bytes hot)))))))
    (testing "a window in which nothing was read says so"
      (let [quiet (metering/tiered-span meters)]
        (is (= :no-reads (:cache-profile (quiet))))))))

(deftest a-profile-derived-from-meters-that-disagree-is-not-a-measurement
  ;; below cannot exceed above unless the two are not on the same path, and a
  ;; number derived from that is a guess wearing a measurement's clothes
  (is (thrown? clojure.lang.ExceptionInfo
               (metering/cache-profile {:requests 1} {:requests 2})))
  (is (thrown? clojure.lang.ExceptionInfo
               (metering/cache-profile {:requests nil} {:requests 0})))
  (is (thrown? clojure.lang.ExceptionInfo (metering/tiered-span {:above nil})))
  (testing "a partial hit is neither cold nor hot"
    (is (= :warm (metering/cache-profile {:requests 4} {:requests 1})))))

(deftest a-meter-refuses-what-it-cannot-measure
  (is (thrown? clojure.lang.ExceptionInfo (metering/meter {})))
  (is (thrown? clojure.lang.ExceptionInfo
               (metering/span {:read (constantly {})} "cold")))
  (is (thrown? clojure.lang.ExceptionInfo (metering/span {} :unmeasured))))
