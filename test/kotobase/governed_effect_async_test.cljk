(ns kotobase.governed-effect-async-test
  "The Worker half of the effect path.

  In a Worker the audit is a storage write, the effect is whatever it is, and
  the signature and its verification are `crypto.subtle` calls. An unawaited
  Promise is truthy, so a rule that accepts one has accepted every audit that
  had not landed yet — which is the failure this suite exists to catch, since
  the whole point of `kotobase.admission` is that the audit is durable
  *before* the effect runs."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [kotobase.evidence :as evidence]
            [kotobase.governed-effect :as governed]
            [kotobase.governed-effect-test :as fixture]))

(defn- later [value] (js/Promise.resolve value))

(defn- options [journal & {:as overrides}]
  (merge
   (fixture/options journal
                    :verify (fn [request] (later (fixture/verify* request)))
                    :sign (fn [unsigned] (later (fixture/proof unsigned)))
                    :cost (fn [] (later {:dependent-hops 1 :requests 2
                                         :bytes 512 :cache-profile :cold}))
                    :audit! (fn [decision]
                              (swap! journal conj [:audit decision])
                              (later {:audit/durable? true
                                      :audit/receipt-id "bafy-audit"}))
                    :effect! (fn [_]
                               (swap! journal conj [:effect])
                               (later {:roots ["bafy-output"] :result :done}))
                    :commit! (fn [receipt]
                               (swap! journal conj [:commit receipt])
                               (later {:receipt/durable? true
                                       :receipt/cid "bafy-effect-receipt"})))
   overrides))

(deftest worker-effect-awaits-every-host-answer
  (async done
    (let [journal (atom [])]
      (-> (governed/execute-async! (options journal))
          (.then
           (fn [outcome]
             (swap! journal conj [:returned])
             (is (= :done (:result outcome)))
             (testing "the audit landed before the effect ran"
               (is (= [:audit :effect :commit :returned]
                      (mapv first @journal))))
             (let [receipt (:effect/receipt outcome)]
               (is (= :allow (:authority/decision receipt)))
               (is (= ["bafy-output"] (:outcome/roots receipt)))
               (is (= #{} (evidence/missing :governed-effect receipt))))
             (done)))
          (.catch (fn [error]
                    (is false (str "unexpected rejection: " error))
                    (done)))))))

(deftest worker-audit-is-awaited-not-assumed
  (async done
    ;; a Promise resolving to a non-durable acknowledgement. Unawaited, the
    ;; Promise object itself is truthy and the effect would have run
    (let [journal (atom [])]
      (-> (governed/execute-async!
           (options journal
                    :audit! (fn [_] (later {:audit/durable? false}))))
          (.then (fn [_] (is false "an unaudited effect ran") (done)))
          (.catch
           (fn [error]
             (is (= :kotobase/audit-denied (:type (ex-data error))))
             (testing "and nothing ran or was written"
               (is (= [] (mapv first @journal))))
             (done)))))))

(deftest worker-refusal-is-committed-before-it-is-raised
  (async done
    (let [journal (atom [])]
      (-> (governed/execute-async! (options journal
                                            :delegated-effects #{}
                                            :local-policy-effects #{}))
          (.then (fn [_] (is false "a denied effect returned a result") (done)))
          (.catch
           (fn [error]
             (is (= :admission-denied
                    (:kotobase.governed-effect/reason (ex-data error))))
             (is (= [:audit :commit] (mapv first @journal)))
             (let [receipt (second (last @journal))]
               (is (= :deny (:authority/decision receipt)))
               (is (= [] (:outcome/roots receipt))))
             (done)))))))
