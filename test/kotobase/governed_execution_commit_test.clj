(ns kotobase.governed-execution-commit-test
  "The governed path against a real database and the canonical commit sink.

  `kotobase.governed-execution-test` proves the composition refuses what it
  should while every host answer is a fixture. That leaves the one claim the
  fixtures cannot make: that the receipt the rows waited for was actually
  written and can be read back from the commit it produced. Here the sink is
  `kotobase.causal-commit/execution-receipt-sink` over an in-memory store, and
  the receipt is re-read at an exact commit CID."
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.causal-commit :as causal]
            [kotobase.core :as core]
            [kotobase.governed-execution :as governed]
            [kotobase.governed-execution-test :as fixture]
            [kotobase.storage.memory :as memory]))

(defn- database []
  (core/open {:storage (memory/memory-store)
              :encrypt-fn identity
              :decrypt-fn identity
              :blind-fn pr-str
              :visible? (constantly true)}))

(defn- counting-cid-fn []
  (let [n (atom 0)]
    (fn [_] (str "bafy-execution-receipt-" (swap! n inc)))))

(defn- recording-sink [database acks]
  (let [sink (causal/execution-receipt-sink
              database {:expected-basis-cid nil
                        :receipt-cid-fn (counting-cid-fn)})]
    (fn [receipt]
      (let [ack (sink receipt)]
        (swap! acks conj ack)
        ack))))

(defn- reason [f]
  (let [data (ex-data (try (f) nil (catch clojure.lang.ExceptionInfo e e)))]
    (or (:kotobase.governed-execution/reason data)
        (:kotobase.execution-contract/reason data)
        (:kotobase.causal-commit/reason data))))

(deftest a-served-execution-is-readable-at-the-commit-it-produced
  (let [db (database)
        acks (atom [])
        result (governed/execute!
                (fixture/options (atom []) :commit! (recording-sink db acks)))
        ack (first @acks)
        proof (causal/receipt-at db (:receipt/commit-cid ack) (:receipt/cid ack))]
    (is (= fixture/served (:rows result)))
    (is (true? (:receipt/durable? ack)))
    (is (= :canonical-cid-dag (:receipt/route ack)))
    (testing "and what comes back out is the receipt the rows waited for"
      ;; not `a receipt exists`: the decoded record is compared to the exact
      ;; map the execution signed
      (is (= [(:execution/receipt result)] (:receipt/records proof))))
    (testing "and no mutable head was published to find it by"
      (is (nil? (core/head db))))))

(deftest a-refusal-is-committed-too-and-names-its-own-receipt
  (let [db (database)
        acks (atom [])
        denied (fn [q] (assoc (fixture/authorize q) :allowed? false))
        error (try (governed/execute!
                    (fixture/options (atom [])
                                     :authorize! denied
                                     :commit! (recording-sink db acks)))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))
        ack (first @acks)
        proof (causal/receipt-at db (:receipt/commit-cid ack) (:receipt/cid ack))
        receipt (first (:receipt/records proof))]
    (is (= :authority-denied
           (:kotobase.governed-execution/reason (ex-data error))))
    (testing "and the refusal points the caller at its own durable record"
      (is (= (:receipt/cid ack)
             (:kotobase.governed-execution/deny-receipt-cid (ex-data error)))))
    (is (= :deny (:authority/decision receipt)))
    (is (nil? (:result/root receipt)))))

(deftest the-sink-checks-the-record-it-is-about-to-make-durable
  (let [db (database)
        sink (causal/execution-receipt-sink
              db {:expected-basis-cid nil :receipt-cid-fn (counting-cid-fn)})
        acks (atom [])
        result (governed/execute!
                (fixture/options (atom []) :commit! (recording-sink db acks)))
        receipt (:execution/receipt result)]
    (testing "a receipt that would not validate is not written"
      ;; the boundary where a record becomes durable is the last place it can
      ;; be checked, and a record only its author ever checked is checked once
      (is (= :invalid-keys (reason #(sink (dissoc receipt :signature)))))
      (is (= :invalid-receipt
             (reason #(sink (assoc receipt :signature "")))))
      (is (= :invalid-receipt
             (reason #(sink (assoc receipt :authority/decision :maybe))))))
    (testing "and the sink will not name one without the host's codec"
      (is (= :invalid-receipt-cid
             (reason #((causal/execution-receipt-sink
                        db {:expected-basis-cid nil
                            :receipt-cid-fn (constantly "")})
                       receipt))))
      (is (= :missing-receipt-cid-function
             (reason #(causal/execution-receipt-sink
                       db {:expected-basis-cid nil
                           :receipt-cid-fn nil})))))))
