(ns kotobase.semantic-integration-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.semantic-code :as semantic]
            [kotobase.code-graph :as code]
            [kotobase.local :as local]))

(defn verify [cid block]
  (:ok? (semantic/verify-block cid block)))

(deftest semantic-code-round-trips-through-kotobase-and-causal-namespace
  (let [compiled
        (semantic/compile-definitions
         '[(defn helper [x] (+ x 1))
           (defn ^{:effects #{:graph-read}} main [x] (helper x))])
        helper (get-in compiled [:definitions 'helper])
        main (get-in compiled [:definitions 'main])
        s (local/local-store)]
    (doseq [definition [helper main]]
      (code/put-type! s verify {:cid (:type-cid definition)
                                :block (:type-block definition)}))
    (code/put-definition! s verify helper)
    (code/put-definition! s verify main)
    (is (= #{(:cid helper) (:cid main)}
           (code/dependency-closure s (:cid main))))
    (is (= #{"graph-read"} (code/transitive-effects s (:cid main))))
    (let [namespace
          (semantic/namespace-commit
           {:parents [] :bindings {"example/helper" (:cid helper)
                                   "example/main" (:cid main)}})]
      (code/put-namespace-commit! s verify namespace)
      (is (= (:cid main)
             (code/resolve-name s (:cid namespace) "example/main"))))
    (let [artifact-cid (semantic/source-cid "wasm-artifact")
          id (fn [label] (semantic/source-cid label))]
      (code/put-artifact!
       s #(= artifact-cid (:artifact-cid %))
       {:artifact-cid artifact-cid :code-root-cid (:cid main)
          :compiler-contract-cid (:hash-contract-cid compiled)
          :bytes [0 97 115 109]})
      (let [closure-cid
            (semantic/closure-cid (code/dependency-closure s (:cid main)))
            receipt
            (semantic/execution-receipt
             {:code-root-cid (:cid main) :code-closure-cid closure-cid
              :artifact-cid artifact-cid
              :compiler-contract-cid (:hash-contract-cid compiled)
              :input-root-cids [(id "input")] :output-root-cids [(id "output")]
              :package-lock-cid (id "lock") :policy-cid (id "policy")
              :grant-cids [(id "grant")] :host-receipt-cids [(id "host")]
              :granted-effects ["graph-read"] :outcome :success})]
      (code/put-execution-receipt!
       s verify
       receipt)
      (is (= (:cid main)
             (:code-root-cid (code/execution-receipt s (:cid receipt)))))))))
