(ns kotobase.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.store :as st]
            [kotobase.local :as local]
            [kotobase.kotobase :as kb]
            [kotobase.contract :as contract]))

(defn- mock-xrpc
  "An xrpc fn that faithfully routes kotobase XRPC methods to a backing LocalStore —
  stands in for the real kotobase.net so the SAME contract proves KotobaseStore's
  op→XRPC mapping is correct."
  [backend]
  (fn [method params]
    (case method
      :put    (st/-put backend (:coll params) (:key params) (:val params))
      :get    (st/-get backend (:coll params) (:key params))
      :list   (st/-list backend (:coll params))
      :append (st/-append backend (:stream params) (:event params))
      :read   (st/-read backend (:stream params) (:since params))
      :snapshot (st/-snapshot backend params)
      :transact (st/-transact backend params))))

(deftest local-store-satisfies-contract
  (testing "the in-process LocalStore is a correct IStore"
    (contract/verify (local/local-store)
                     (fn [ok? label] (is ok? (str "local: " label))))))

(deftest kotobase-store-equals-local
  (testing "KotobaseStore over a faithful xrpc reproduces LocalStore (≡ oracle)"
    (contract/verify (kb/kotobase-store (mock-xrpc (local/local-store)))
                     (fn [ok? label] (is ok? (str "kotobase: " label))))))

(deftest snapshot-is-a-value
  (testing "a LocalStore snapshots to a plain value for checkpoint/cloud-push"
    (let [s (local/local-store)]
      (st/-put s "c" "k" 1)
      (st/-append s "str" {:x 1})
      (let [snap (local/snapshot s)]
        (is (= 1 (get-in snap [:docs "c" "k"])))
        (is (= 1 (count (get-in snap [:streams "str"]))))
        (is (= 1 (:seq snap)))))))

(deftest xrpc-path-mapping
  (testing "store methods map to kotoba XRPC paths"
    (is (= "net.kotobase.store.append" (kb/xrpc-method->path :append)))))

(deftest transactional-xrpc-is-explicitly-negotiated
  (let [backend (local/local-store)
        legacy (kb/kotobase-store (mock-xrpc backend))
        transactional (kb/kotobase-store (mock-xrpc backend)
                                          {:transactional? true})]
    (is (not (st/transactional-store? legacy)))
    (is (st/transactional-store? transactional))
    (is (= 0 (:revision (st/-snapshot transactional
                                        {:collections [] :streams []}))))))

(deftest remote-store-enforces-shared-abac-before-xrpc
  (let [calls (atom [])
        decisions (atom [])
        remote (kb/kotobase-store
                (fn [method params] (swap! calls conj [method params]) :ok)
                {:abac-attributes
                 {:subject {:id :service/api :tenant "alpha"}
                  :resource {:tenant "alpha" :trust :tenant-data}
                  :environment {:surface :service :network-zone :private}}
                 :abac-policy
                 {:policy/id :kotobase/tenant-read
                  :subject/ids #{:service/api}
                  :resource/ids #{"documents/allowed"}
                  :resource/trust #{:tenant-data}
                  :action/ids #{:kotobase/read}
                  :action/capabilities #{:kotobase/get}
                  :environment/surfaces #{:service}
                  :environment/network-zones #{:private}
                  :tenant/isolation? true}
                 :audit! #(swap! decisions conj %)})]
    (is (= :ok (st/-get remote "documents" "allowed")))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"ABAC policy denies kotobase operation"
         (st/-get remote "documents" "other")))
    (is (= 1 (count @calls)) "denied operation never reaches transport")
    (is (= [true false] (mapv :abac/allowed? @decisions)))))

(deftest write-egress-requires-explicit-declassification
  (let [calls (atom [])
        remote (kb/kotobase-store
                (fn [method params] (swap! calls conj [method params]) :ok)
                {:information-flow-context
                 {:subject :service/export :purpose :replication
                  :now "2026-07-19T12:00:00Z"
                  :input-classifications [:confidential]
                  :output-classification :public}})]
    (is (= :ok (st/-get remote "public" "status"))
        "reads do not disclose caller-provided data")
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"information-flow policy denies kotobase egress"
         (st/-put remote "public" "export" {:secret true})))
    (is (= 1 (count @calls)) "denied write never reaches XRPC")))

(deftest xrpc-requires-complete-mutual-tls-profile
  (let [calls (atom 0)
        base {:protocol :tls-1.3 :mutual-auth? true
              :peer-id "did:web:kotobase.net"
              :expected-peer-id "did:web:kotobase.net"
              :certificate-fingerprint "sha256:current"
              :trusted-fingerprints #{"sha256:current" "sha256:next"}
              :revocation-checked? true :now "2026-07-19T12:00:00Z"
              :certificate-expires-at "2026-08-01T00:00:00Z"
              :require-rotation-overlap? true
              :next-certificate-fingerprint "sha256:next"}
        allowed (kb/kotobase-store (fn [_ _] (swap! calls inc) :ok)
                                    {:transport-profile base})
        denied (kb/kotobase-store (fn [_ _] (swap! calls inc) :bad)
                                   {:transport-profile
                                    (dissoc base :next-certificate-fingerprint)})]
    (is (= :ok (st/-get allowed "docs" "one")))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"transport profile denies kotobase XRPC"
         (st/-get denied "docs" "one")))
    (is (= 1 @calls) "denied transport profile never invokes XRPC")))

(deftest production-xrpc-requires-real-hybrid-pqc-envelope
  (let [calls (atom 0)
        policy {:kotoba.security/crypto-policy-version 1
                :mode :hybrid-required :hybrid-epoch-floor 1}
        envelope {:envelope/provider {:provider/id :kagi
                                      :provider/fips-validated false}
                  :envelope/kem? true :envelope/hybrid? true
                  :envelope/epoch 2
                  :envelope/algorithms [:x25519 :ml-kem-768]}
        make-store (fn [e]
                     (kb/kotobase-store
                      (fn [_ _] (swap! calls inc) :ok)
                      {:crypto-required? true :crypto-policy policy
                       :crypto-envelope e}))]
    (is (= :ok (st/-get (make-store envelope) "docs" "one")))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"hybrid PQC policy denies"
         (st/-get (make-store (assoc envelope :envelope/algorithms [:x25519]))
                  "docs" "one")))
    (is (= 1 @calls) "downgraded envelope never reaches XRPC")))

(deftest transactional-store-is-atomic-idempotent-and-conflict-aware
  (let [s (local/local-store)
        request {:tx-id "tx-1" :expected-revision 0
                 :puts [["docs" "a" 1] ["docs" "b" 2]]
                 :deletes [] :appends [["events" {:op :commit}]]}
        receipt (st/-transact s request)]
    (is (= 1 (:revision receipt)))
    (is (= 1 (get-in (st/-snapshot s {:collections ["docs"]
                                      :streams ["events"]})
                     [:docs "docs" "a"])))
    (is (= receipt (st/-transact s request)) "same tx-id is idempotent")
    (is (= 1 (count (st/-read s "events" 0))) "replay does not append twice")
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) #"revision conflict"
         (st/-transact s {:tx-id "tx-2" :expected-revision 0
                          :puts [["docs" "c" 3]] :deletes [] :appends []})))
    (is (nil? (st/-get s "docs" "c")) "conflict applies nothing")))

#?(:clj
   (deftest one-revision-allows-exactly-one-concurrent-commit
     (let [s (local/local-store)
           attempts (doall
                     (for [n (range 32)]
                       (future
                         (try
                           (st/-transact
                            s {:tx-id (str "concurrent-" n)
                               :expected-revision 0
                               :puts [["winner" "id" n]]
                               :deletes [] :appends [["events" {:n n}]]})
                           (catch clojure.lang.ExceptionInfo error
                             (ex-data error))))))
           results (mapv deref attempts)
           commits (filter :revision results)
           conflicts (filter #(= :kotobase.store/revision-conflict (:type %))
                             results)]
       (is (= 1 (count commits)))
       (is (= 31 (count conflicts)))
       (is (= 1 (count (st/-read s "events" 0))))
       (is (= 1 (:revision (st/-snapshot s {:collections ["winner"]
                                            :streams ["events"]})))))))
