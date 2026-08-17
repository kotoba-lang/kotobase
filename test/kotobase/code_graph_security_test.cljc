(ns kotobase.code-graph-security-test
  "Adversarial contract tests for KOT-SEC-006.

  These tests verify that the code graph layer rejects invalid CID/block pairs
  even when the host-injected verify function is malicious or buggy.

  The internal verification (SHA-256) must always be the final authority.
  Tests use legacy single-arity calls (malicious verify-host) so that
  verify-internal defaults to verify-internal-default (SHA-256)."
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.code-graph :as code]
            [kotobase.local :as local]
            [kotobase.store :as store]))

;; --- Malicious verify implementations (host-injected) ---

(defn verify-constantly-true [_ _] true)
(defn verify-constantly-false [_ _] false)
(defn verify-exception-swallowing [_ _] (try (throw (ex-info "verify failed" {})) (catch Throwable _ true)))
(defn verify-cid-mismatch [cid block] (not= cid (:cid block))) ;; Returns true when they DON'T match

;; Artifact-specific malicious verify (takes record)
(defn verify-artifact-constantly-true [_] true)
(defn verify-artifact-constantly-false [_] false)
(defn verify-artifact-exception-swallowing [_] (try (throw (ex-info "verify failed" {})) (catch Throwable _ true)))

;; Test verify that matches fake CIDs (for valid record tests using dual arity)
(defn verify-test [cid block] (= cid (:cid block)))
(defn verify-artifact-test [record] (= (:artifact-cid record) "valid-artifact"))

;; --- Test fixtures ---

(defn record [cid deps effects]
  {:cid cid :block {:cid cid} :dependency-cids deps :effects effects})

;; Record with CID != block[:cid] for testing internal verification
(defn record-cid-mismatch [claimed-cid actual-cid deps effects]
  {:cid claimed-cid :block {:cid actual-cid} :dependency-cids deps :effects effects})

(defn type-record [cid kind]
  {:cid cid :block {:cid cid "kind" kind}})

(defn type-record-cid-mismatch [claimed-cid actual-cid kind]
  {:cid claimed-cid :block {:cid actual-cid "kind" kind}})

(defn artifact-record [artifact-cid code-root-cid compiler-contract-cid bytes]
  {:artifact-cid artifact-cid :code-root-cid code-root-cid
   :compiler-contract-cid compiler-contract-cid :bytes bytes})

(defn ns-commit-record [cid parents bindings]
  {:cid cid :block {:cid cid} :parents parents :bindings bindings})

(defn migration-record [cid from-cid to-cid from-contract-cid to-contract-cid authority-cid]
  {:cid cid :block {:cid cid} :from-cid from-cid :to-cid to-cid
   :from-contract-cid from-contract-cid :to-contract-cid to-contract-cid
   :authority-cid authority-cid})

(defn execution-receipt-record [cid code-root-cid artifact-cid compiler-contract-cid]
  {:cid cid :block {:cid cid}
   :code-root-cid code-root-cid :artifact-cid artifact-cid
   :compiler-contract-cid compiler-contract-cid
   :input-root-cids [] :output-root-cids []
   :package-lock-cid "lock" :policy-cid "policy"
   :grant-cids [] :host-receipt-cids []
   :granted-effects [] :outcome :success})

(defn execution-identity-record [cid identity]
  {:cid cid :block {:cid cid} :identity identity})

(defn query-receipt-record [cid execution-identity-cid]
  {:cid cid :block {:cid cid}
   :execution-identity-cid execution-identity-cid
   :query-cid "query" :result-cid "result"
   :basis "basis" :policy-cid "policy"
   :tenant "acme" :purpose :payment-review :resource-cids []})

(def portable-identity
  {:format :kotoba.execution-identity/v1
   :plan-cid "plan" :code-closure-cid "closure"
   :artifact-cid "artifact" :compiler-contract "compiler"
   :component-cid "component" :wit-world-cid "world"
   :package-lock-cid "lock" :policy-cid "policy"
   :policy-decision-cid "decision" :db-basis "basis"
   :grant-cids ["grant"] :approval-cids ["approval"]
   :runtime-identity "runtime" :input-cid "input"
   :outcome-cid "outcome" :host-receipt-cids ["hostreceipt"]})

;; --- Test: put-type! ---

(deftest put-type-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        type-rec (type-record-cid-mismatch "claimed-cid" "actual-cid" "function")]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject CID mismatch"
      (is (= :code/type-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-type! s verify-constantly-true type-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest put-type-rejects-cid-mismatch-with-exception-swallowing
  (let [s (local/local-store)
        type-rec (type-record-cid-mismatch "claimed-cid" "actual-cid" "function")]
    (testing "verify-host swallows exception and returns true, internal (SHA-256) should reject"
      (is (= :code/type-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-type! s verify-exception-swallowing type-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest put-type-rejects-cid-mismatch-with-cid-mismatch-verify
  (let [s (local/local-store)
        type-rec (type-record-cid-mismatch "claimed-cid" "actual-cid" "function")]
    (testing "verify-host returns true when CID != block[:cid], internal (SHA-256) should reject"
      (is (= :code/type-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-type! s verify-cid-mismatch type-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: put-definition! ---

(deftest put-definition-rejects-cid-mismatch-even-with-trusted-verify
  (let [s (local/local-store)
        def-rec (record-cid-mismatch "claimed-cid" "actual-cid" [] [])]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject CID mismatch"
      (is (= :code/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-definition! s verify-constantly-true def-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest put-definition-rejects-with-constantly-false-at-host
  (let [s (local/local-store)
        def-rec (record-cid-mismatch "claimed-cid" "actual-cid" [] [])]
    (testing "verify-host = constantly false should fail at host verification (fail-closed)"
      (is (= :code/cid-mismatch
             (:problem (ex-data
                        (try (code/put-definition! s verify-constantly-false def-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest put-definition-rejects-cid-mismatch-with-exception-swallowing
  (let [s (local/local-store)
        def-rec (record-cid-mismatch "claimed-cid" "actual-cid" [] [])]
    (testing "verify-host swallows exception, internal (SHA-256) should reject"
      (is (= :code/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-definition! s verify-exception-swallowing def-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest put-definition-rejects-when-verify-returns-true-for-mismatch
  (let [s (local/local-store)
        def-rec (record-cid-mismatch "claimed-cid" "actual-cid" [] [])]
    (testing "verify-host returns true when CID != block[:cid], internal (SHA-256) should reject"
      (is (= :code/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-definition! s verify-cid-mismatch def-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: put-artifact! ---

(deftest put-artifact-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        art-rec (artifact-record "claimed-artifact" "code-root" "compiler" [1 2 3])]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256 of bytes) should reject artifact CID mismatch"
      (is (= :code/artifact-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-artifact! s verify-artifact-constantly-true art-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest put-artifact-rejects-cid-mismatch-with-exception-swallowing
  (let [s (local/local-store)
        art-rec (artifact-record "claimed-artifact" "code-root" "compiler" [1 2 3])]
    (testing "verify-host swallows exception, internal (SHA-256) should reject"
      (is (= :code/artifact-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-artifact! s verify-artifact-exception-swallowing art-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: cache-put! ---

(deftest cache-put-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        ;; Create required dependencies: code-root and input
        _ (code/put-definition! s verify-test verify-test (record "root" [] []))
        _ (code/put-definition! s verify-test verify-test (record "input" [] []))
        cache-rec {:cid "claimed-cache" :block {:cid "actual-cache"}
                   :code-root-cid "root" :analyzer-contract-cid "analyzer"
                   :environment-cid "env" :input-cids ["input"]
                   :result {:safe? true}}]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject CID mismatch"
      (is (= :cache/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/cache-put! s verify-constantly-true cache-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: put-namespace-commit! ---

(deftest put-namespace-commit-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        ns-rec (ns-commit-record "claimed-ns" [] {"app/main" "cid-1"})]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject CID mismatch"
      (is (= :namespace/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-namespace-commit! s verify-constantly-true ns-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: put-identity-migration! ---

(deftest put-identity-migration-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        ;; Create from/to definitions first
        _ (code/put-definition! s verify-test verify-test (record "from" [] []))
        _ (code/put-definition! s verify-test verify-test (record "to" [] []))
        mig-rec (migration-record "claimed-mig" "from" "to" "fc" "tc" "auth")]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject CID mismatch"
      (is (= :migration/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-identity-migration! s verify-constantly-true (constantly true) mig-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: put-execution-receipt! ---

(deftest put-execution-receipt-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        rec-rec (execution-receipt-record "claimed-receipt" "root" "artifact" "compiler")]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject CID mismatch"
      (is (= :execution/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-execution-receipt! s verify-constantly-true rec-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: put-execution-identity! ---

(deftest put-execution-identity-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        id-rec (execution-identity-record "claimed-identity" portable-identity)]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject CID mismatch"
      (is (= :execution-identity/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-execution-identity! s verify-constantly-true id-rec)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: import-closure! / import-code-graph! / sync-code-root! ---

(deftest import-closure-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        records [(record-cid-mismatch "claimed-1" "actual-1" [] [])
                 (record-cid-mismatch "claimed-2" "actual-2" ["claimed-1"] [])]]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject on first mismatch"
      (is (= :code/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/import-closure! s verify-constantly-true records)
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest import-code-graph-rejects-cid-mismatch-with-constantly-true
  (let [s (local/local-store)
        types [(type-record-cid-mismatch "claimed-type" "actual-type" "function")]
        defs [(record-cid-mismatch "claimed-def" "actual-def" [] [])]]
    (testing "verify-host = constantly true (legacy arity), internal (SHA-256) should reject type CID mismatch"
      (is (= :code/type-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/import-code-graph! s verify-constantly-true {:types types :definitions defs})
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e)))))))))

;; --- Test: Valid records should still pass with dual verification ---

(deftest valid-records-pass-with-dual-verification
  (let [s (local/local-store)
        type-rec (type-record "valid-type" "function")
        def-rec (record "valid-def" [] [])]
    (testing "Valid records pass both host and internal verification (using test verify for both)"
      (is (= "valid-type" (:cid (code/put-type! s verify-test verify-test type-rec))))
      (is (= "valid-def" (:code.definition/cid (code/put-definition! s verify-test verify-test def-rec)))))))

;; --- Test: Internal verification uses SHA-256 (integration with semantic-code) ---

(deftest internal-verify-matches-semantic-code-verify-block
  (let [s (local/local-store)
        block {:cid "test-cid" :data "test-data"}
        computed-cid (code/compute-cid block)]
    (testing "compute-cid produces deterministic SHA-256"
      (is (string? computed-cid))
      (is (= 64 (count computed-cid)))
      (is (= computed-cid (code/compute-cid block))))))