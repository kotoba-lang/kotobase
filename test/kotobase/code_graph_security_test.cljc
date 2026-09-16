(ns kotobase.code-graph-security-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.code-graph :as code]
            [kotobase.local :as local]
            [kotobase.store :as store]))

;; Test fixtures for malicious verify functions

(defn record [cid deps effects]
  {:cid cid :block {:cid cid} :dependency-cids deps :effects effects})

;; Simple verify for test setup - accepts test CIDs
(defn setup-verify [cid block] (= cid (:cid block)))

(defn verify-constantly-true-artifact [record] true)

(defn verify-constantly-true [cid block] true)

(defn verify-constantly-false [cid block] false)

(defn verify-swallow-exception [cid block]
  (try true (catch Throwable _ false)))

(defn verify-cid-mismatch [cid block]
  (= cid "bafy2bzaceawxyz"))

(defn valid-block [cid]
  {:cid cid :kind "test" :data "hello"})

;; Helper to compute real SHA-256 CID
(defn- compute-cid [block]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (pr-str block) "UTF-8")
        digest (.digest md bytes)
        bi (java.math.BigInteger. 1 digest)]
    (format "%064x" bi)))

;; Real blocks with correct SHA-256 CIDs
(def real-block-1
  (let [block (valid-block "placeholder")]
    (assoc block :cid (compute-cid block))))

(def real-block-2
  (let [block (assoc (valid-block "placeholder") :data "world")]
    (assoc block :cid (compute-cid block))))

;; Tests for malicious verify-host with dual verification

(deftest constantly-true-verify-host-cannot-bypass-internal
  "A malicious verify-host that always returns true cannot store arbitrary blocks."
  (let [s (local/local-store)]
    (testing "put-definition! rejects mismatched CID even with always-true verify-host"
      (is (= :code/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-definition! s verify-constantly-true code/verify-internal-default
                                                   (record "bafyfake" [] []))
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))
    (testing "put-type! rejects mismatched CID even with always-true verify-host"
      (is (= :code/type-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-type! s verify-constantly-true code/verify-internal-default
                                             {:cid "bafyfake" :block {:cid "different"}})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))
    (testing "put-namespace-commit! rejects mismatched CID even with always-true verify-host"
      (is (= :namespace/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-namespace-commit!
                              s verify-constantly-true code/verify-internal-default
                              {:cid "bafyfake" :block {:cid "different"} :parents [] :bindings {}})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))
    (testing "put-identity-migration! rejects mismatched CID even with always-true verify-host"
      (code/put-definition! s setup-verify setup-verify (record "cid-v1" [] []))
      (code/put-definition! s setup-verify setup-verify (record "cid-v2" [] []))
      (is (= :migration/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-identity-migration!
                              s verify-constantly-true code/verify-internal-default (constantly true)
                              {:cid "bafyfake" :block {:cid "different"} :from-cid "cid-v1" :to-cid "cid-v2"
                               :from-contract-cid "c1" :to-contract-cid "c2" :authority-cid "did:key:test"})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))
    (testing "put-execution-receipt! rejects mismatched CID even with always-true verify-host"
      (code/put-definition! s setup-verify setup-verify (record "cid-main-1" [] ["graph-write"]))
      (code/put-artifact! s (constantly true) (constantly true)
                          {:artifact-cid "cid-wasm" :code-root-cid "cid-main-1"
                           :compiler-contract-cid "cid-compiler" :bytes [0]})
      (is (= :execution/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-execution-receipt!
                              s verify-constantly-true code/verify-internal-default
                              {:cid "bafyfake" :block {:cid "different"} :code-root-cid "cid-main-1"
                               :artifact-cid "cid-wasm" :compiler-contract-cid "cid-compiler"
                               :input-root-cids [] :output-root-cids [] :package-lock-cid "cid-lock"
                               :policy-cid "cid-policy" :grant-cids [] :host-receipt-cids []
                               :granted-effects ["graph-write"] :outcome :success})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))
    (testing "put-execution-identity! rejects mismatched CID even with always-true verify-host"
      (is (= :execution-identity/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-execution-identity!
                              s verify-constantly-true code/verify-internal-default
                              {:cid "bafyfake" :block {:cid "different"} :identity {}})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))
    (testing "put-query-receipt! rejects mismatched CID even with always-true verify-host"
      (is (= :query-receipt/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-query-receipt!
                              s verify-constantly-true code/verify-internal-default
                              {:cid "bafyfake" :block {:cid "different"} :execution-identity-cid "cid-eid"
                               :query-cid "cid-q" :result-cid "cid-r" :basis "cid-b" :policy-cid "cid-p"
                               :tenant "t" :purpose :p :resource-cids ["r"]})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))
    (testing "cache-put! rejects mismatched CID even with always-true verify-host"
      (code/put-definition! s setup-verify setup-verify (record "cid-main-cache" [] []))
      (is (= :cache/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/cache-put!
                              s verify-constantly-true code/verify-internal-default
                              {:cid "bafyfake" :block {:cid "different"} :code-root-cid "cid-main-cache"
                               :analyzer-contract-cid "cid-ana" :environment-cid "cid-env" :input-cids []})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest constantly-false-verify-host-rejected-by-host
  "A malicious verify-host that always returns false is rejected by host verify."
  (let [s (local/local-store)]
    (testing "put-definition! fails on verify-host when constantly-false"
      (is (= :code/cid-mismatch
             (:problem (ex-data
                        (try (code/put-definition! s verify-constantly-false code/verify-internal-default
                                                   (record "cid-real" [] []))
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest exception-swallowing-verify-host-cannot-bypass-internal
  "A verify-host that swallows exceptions cannot bypass internal verification."
  (let [s (local/local-store)]
    (testing "put-definition! rejects mismatched CID despite exception-swallowing verify-host"
      (is (= :code/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-definition! s verify-swallow-exception code/verify-internal-default
                                                   (record "bafyfake" [] []))
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest cid-mismatch-verify-host-cannot-bypass-internal
  "A verify-host that checks wrong CID cannot bypass internal verification."
  (let [s (local/local-store)]
    (testing "put-definition! rejects when verify-host checks wrong CID"
      (is (= :code/cid-mismatch
             (:problem (ex-data
                        (try (code/put-definition! s verify-cid-mismatch code/verify-internal-default
                                                   (record "bafyfake" [] []))
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))))

;; Tests for artifact dual verification
(deftest artifact-verify-host-cannot-bypass-internal
  "A malicious artifact verify-host cannot bypass internal verification."
  (let [s (local/local-store)
        artifact {:artifact-cid "bafyfake" :code-root-cid "cid-main-artifact"
                  :compiler-contract-cid "cid-compiler" :bytes [1 2 3]}]
    (code/put-definition! s setup-verify setup-verify (record "cid-main-artifact" [] []))
    (testing "put-artifact! rejects mismatched artifact CID even with always-true verify-host"
      (is (= :code/artifact-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-artifact!
                              s verify-constantly-true-artifact code/verify-artifact-internal-default artifact)
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))))

;; Tests for dual-arity backward compatibility
(deftest legacy-single-verify-arity-still-works
  "Legacy single-verify-arity calls default internal verifier correctly."
  (testing "put-definition! with single verify uses default internal"
    (let [s (local/local-store)
          block {:cid "test-cid" :data "test"}]
      (is (= :code/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-definition! s (constantly true)
                                                   {:cid "wrong" :block block})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e))))))))
  (testing "put-type! with single verify uses default internal"
    (let [s (local/local-store)
          block {:cid "test-cid" :data "test"}]
      (is (= :code/type-cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-type! s (constantly true)
                                             {:cid "wrong" :block block})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e))))))))
  (testing "put-namespace-commit! with single verify uses default internal"
    (let [s (local/local-store)
          block {:cid "test-cid" :data "test"}]
      (is (= :namespace/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-namespace-commit!
                              s (constantly true)
                              {:cid "wrong" :block block :parents [] :bindings {}})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e))))))))
  (testing "put-identity-migration! with single verify uses default internal"
    (let [s (local/local-store)]
      (code/put-definition! s setup-verify setup-verify (record "cid-v1-legacy" [] []))
      (code/put-definition! s setup-verify setup-verify (record "cid-v2-legacy" [] []))
      (is (= :migration/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-identity-migration!
                              s (constantly true) (constantly true)
                              {:cid "wrong" :block {:cid "test-cid" :data "test"} :from-cid "cid-v1-legacy" :to-cid "cid-v2-legacy"
                               :from-contract-cid "c1" :to-contract-cid "c2" :authority-cid "did:key:test"})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e))))))))
  (testing "put-execution-receipt! with single verify uses default internal"
    (let [s (local/local-store)]
      (code/put-definition! s setup-verify setup-verify (record "cid-main-legacy" [] ["graph-write"]))
      (code/put-artifact! s (constantly true) (constantly true)
                          {:artifact-cid "cid-wasm-legacy" :code-root-cid "cid-main-legacy"
                           :compiler-contract-cid "cid-compiler-legacy" :bytes [0]})
      (is (= :execution/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-execution-receipt!
                              s (constantly true)
                              {:cid "wrong" :block {:cid "test-cid" :data "test"} :code-root-cid "cid-main-legacy"
                               :artifact-cid "cid-wasm-legacy" :compiler-contract-cid "cid-compiler-legacy"
                               :input-root-cids [] :output-root-cids [] :package-lock-cid "cid-lock-legacy"
                               :policy-cid "cid-policy-legacy" :grant-cids [] :host-receipt-cids []
                               :granted-effects ["graph-write"] :outcome :success})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e))))))))
  (testing "put-execution-identity! with single verify uses default internal"
    (let [s (local/local-store)]
      (is (= :execution-identity/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-execution-identity!
                              s (constantly true)
                              {:cid "wrong" :block {:cid "test-cid" :data "test"} :identity {}})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e))))))))
  (testing "put-query-receipt! with single verify uses default internal"
    (let [s (local/local-store)]
      (is (= :query-receipt/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/put-query-receipt!
                              s (constantly true)
                              {:cid "wrong" :block {:cid "test-cid" :data "test"} :execution-identity-cid "cid-eid"
                               :query-cid "cid-q" :result-cid "cid-r" :basis "cid-b" :policy-cid "cid-p"
                               :tenant "t" :purpose :p :resource-cids ["r"]})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e))))))))
  (testing "cache-put! with single verify uses default internal"
    (let [s (local/local-store)]
      (code/put-definition! s setup-verify setup-verify (record "cid-main-cache-legacy" [] []))
      (is (= :cache/cid-mismatch-internal
             (:problem (ex-data
                        (try (code/cache-put!
                              s (constantly true)
                              {:cid "wrong" :block {:cid "test-cid" :data "test"} :code-root-cid "cid-main-cache-legacy"
                               :analyzer-contract-cid "cid-ana-legacy" :environment-cid "cid-env-legacy" :input-cids []})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))))