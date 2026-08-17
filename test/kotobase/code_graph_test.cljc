(ns kotobase.code-graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.code-graph :as code]
            [kotobase.kotobase :as remote]
            [kotobase.local :as local]
            [kotobase.store :as store]))

;; --- identities ------------------------------------------------------------
;; These were "bafyplan", "bafyartifact" and so on: readable labels that are
;; not CIDs. `kotoba.abi.contract/cid?` used to be `#"b.+"`, so they passed —
;; and a fixture that cannot be an identity cannot prove the contract accepts
;; one (abi 32ee84b, com-junkawasaki ADR-2608100500).
;;
;; The labels survive because they are what makes the tests readable. Each is
;; now a real CIDv1, derived so the value is reproducible rather than magic:
;;
;;   cidv1-raw(sha2-256("kotobase/" + label))
;;
;; Namespacing by repository is deliberate: kotoba-lang/code-graph carries a
;; near-copy of this suite, and two suites sharing a fixture value would hide
;; a substitution bug where one identity is accepted for another.
(def ^:private cids
  {"approval" "bafkreietc5qkfwrjxmgyi7pkrzrzuqvhioowic3vpt622iaph67kar7454"
   "artifact" "bafkreialj2oft6jayejss73audkgogxtqwlrqsom5qa4ap4bi54qcjgv64"
   "bad" "bafkreihgocxgueriw5zjpngdjc3z66kar3nend26qw2s3fxusd2qvafy5i"
   "basis" "bafkreie4o7bitq7n6ill3wzvj6ekgrhkbvqo4l43bisjpzs5422cjo2ioq"
   "closure" "bafkreic4r422huutsxgtyt5n2fx2yytqg37xdbr43mwdsacsgxof2cmqre"
   "compiler" "bafkreid4s5h3psigzlsmvji3wtm3ulb7fzsrpf463fxkwhjnu3aqehkmiu"
   "component" "bafkreif7drb3ehvksprg3zif5t364sc57ggvqcl3dqibrkg3pliiwbmlse"
   "decision" "bafkreictqtxajflbuv4c7yluoifwb23dpftpgknzmivldgs74mk5kyv6oa"
   "envelope" "bafkreiesixw3zijotwfcpittc3t24i65silo6skb2dmwiajvxse77g7qpm"
   "grant" "bafkreieseatpennyedn4enrpjvhhiobkmforv4ajiwrhg2ixk6wau62lka"
   "hostreceipt" "bafkreibztm6t56wt25mwx3tf7eqtit2ctj4vdf6s37ums5ahtanfn3fjrm"
   "input" "bafkreihvzzzw3oq46hjitnb22rjz7udwo5dfhbr6sfahen7rwt67ego3se"
   "lock" "bafkreihgf7uz7jtjs27j3e2jqyvs57n35oryjccv4yunmwg7xs4pii5ica"
   "other" "bafkreiccnmybeeomsdtuvdrhxesaaahbwmkvzqauolfp4d73xtcujaqfym"
   "otherbasis" "bafkreifbbngaujl5jdr73tv27gnel4zjcqt73kvzio5qsatw6xsxbdzvwu"
   "outcome" "bafkreigrwv5irbfak64ax5uiwlfyk6attivkzcn2kez3wlkoqnkq43c6la"
   "plan" "bafkreiartli4b6ifoukgjuwesaewwttkw3wkv3tsd64elm5xt7mm5gjs7m"
   "policy" "bafkreifmghtedjcmvs4mgni4hggvpyjhcrmsz4qx5wgl5342vki5f52ye4"
   "portableexecutionidentity" "bafkreigiwopvfdxikljiyrdn6toq34fncbum6anagu3dfidla35xkondh4"
   "query" "bafkreifzki5ouf7gnihrcvq6aheftvaaostu2bvvvd5c4nzez62jz4f2x4"
   "result" "bafkreigk55o74tfc5zq5emobmuy2ggj3bnpqiuc4t4es6rlzgjgyqrykbu"
   "runtime" "bafkreidbzlpkqu55a3jkkqvlqm5m5k3kleyyj3hj5w7bbpl2gqqxgmleoi"
   "secret" "bafkreih7re5o4j2nya4rqljc5u4htxx4ptryw5i2stx6zrxc6wm77wlfnu"
   "world" "bafkreib4j636hxqua6domu7kqviodtxluhqnhzabpbquqbqfkqh7mpibby"})

(defn- cid
  "The real CIDv1 fixture for `label`. Unknown labels fail loudly rather than
  returning nil, which `cid?` would then reject with a confusing message."
  [label]
  (or (get cids label)
      (throw (ex-info "no CID fixture for label" {:label label}))))


(defn verify [cid block] (= cid (:cid block)))

(defn xrpc [backend]
  (fn [method params]
    (case method
      :put (store/-put backend (:coll params) (:key params) (:val params))
      :get (store/-get backend (:coll params) (:key params))
      :list (store/-list backend (:coll params))
      :append (store/-append backend (:stream params) (:event params))
      :read (store/-read backend (:stream params) (:since params)))))
(defn record [cid deps effects]
  {:cid cid :block {:cid cid} :dependency-cids deps :effects effects})

(def portable-cid (cid "portableexecutionidentity"))

(defn portable-identity []
  {:format :kotoba.execution-identity/v1
   :plan-cid (cid "plan") :code-closure-cid (cid "closure")
   :artifact-cid (cid "artifact") :compiler-contract (cid "compiler")
   :component-cid (cid "component") :wit-world-cid (cid "world")
   :package-lock-cid (cid "lock") :policy-cid (cid "policy")
   :policy-decision-cid (cid "decision") :db-basis (cid "basis")
   :grant-cids [(cid "grant")] :approval-cids [(cid "approval")]
   :runtime-identity (cid "runtime") :input-cid (cid "input")
   :outcome-cid (cid "outcome") :host-receipt-cids [(cid "hostreceipt")]})

(deftest definitions-are-verified-indexed-and-queryable
  (let [s (local/local-store)]
    (code/put-definition! s verify verify (record "cid-helper" [] []))
    (code/put-definition! s verify verify (record "cid-main" ["cid-helper"] ["graph-write"]))
    (is (= #{"cid-main" "cid-helper"} (code/dependency-closure s "cid-main")))
    (is (= #{"graph-write"} (code/transitive-effects s "cid-main")))
    (is (= ["cid-main"] (code/direct-dependents s "cid-helper")))
    (is (= ["cid-main"] (code/definitions-requiring-effect s "graph-write")))
    (is (seq (store/-read s code/datom-stream 0)))))

(deftest semantic-types-must-be-admitted-before-definitions
  (let [s (local/local-store)
        definition (assoc (record "cid-main" [] [])
                          :type-cid "cid-type")]
    (is (= :code/missing-type
           (:problem (ex-data
                      (try (code/put-definition! s verify verify definition)
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs cljs.core.ExceptionInfo) e e))))))
    (code/put-type! s verify verify {:cid "cid-type" :block {:cid "cid-type" "kind" "function"}})
    (is (= "cid-type"
           (:code.definition/type-cid
            (code/put-definition! s verify verify definition))))))

(deftest admission-fails-closed
  (let [s (local/local-store)]
    (testing "CID mismatch"
      (is (= :code/cid-mismatch
             (:problem (ex-data
                        (try (code/put-definition! s verify verify
                                                   {:cid "claimed" :block {:cid "actual"}})
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))
    (testing "missing dependency"
      (is (= :code/missing-dependency
             (:problem (ex-data
                        (try (code/put-definition! s verify verify
                                                   (record "cid-main" ["absent"] []))
                             (catch #?(:clj clojure.lang.ExceptionInfo
                                       :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest artifact-cache-is-keyed-by-code-and-compiler
  (let [s (local/local-store)]
    (code/put-definition! s verify verify (record "cid-main" [] []))
    (is (= :code/artifact-cid-mismatch
           (:problem
            (ex-data
             (try
               (code/put-artifact!
                s (constantly false) (constantly false)
                {:artifact-cid "tampered" :code-root-cid "cid-main"
                 :compiler-contract-cid "cid-compiler" :bytes [1]})
               (catch #?(:clj clojure.lang.ExceptionInfo
                         :cljs cljs.core.ExceptionInfo) e e))))))
    (code/put-artifact! s (constantly true) (constantly true)
                        {:artifact-cid "cid-wasm" :code-root-cid "cid-main"
                         :compiler-contract-cid "cid-compiler" :bytes [0 97 115 109]})
    (is (= "cid-wasm" (:artifact-cid
                       (code/find-artifact s "cid-main" "cid-compiler"))))
    (is (nil? (code/find-artifact s "cid-main" "other-compiler")))
    (let [analysis {:cid "cid-analysis" :block {:cid "cid-analysis"}
                    :code-root-cid "cid-main"
                    :analyzer-contract-cid "cid-analyzer"
                    :environment-cid "cid-env" :input-cids ["cid-input"]
                    :result {:safe? true}}]
      (code/cache-put! s verify verify analysis)
      (is (= analysis (code/cache-get s "cid-analysis"))))))

(deftest causal-namespace-commits-resolve-and-retain-history
  (let [s (local/local-store)]
    (code/put-definition! s verify verify (record "cid-v1" [] []))
    (code/put-definition! s verify verify (record "cid-v2" [] []))
    (code/put-namespace-commit!
     s verify verify {:cid "ns-1" :block {:cid "ns-1"} :parents []
               :bindings {"app/main" "cid-v1"}})
    (code/put-namespace-commit!
     s verify verify {:cid "ns-2" :block {:cid "ns-2"} :parents ["ns-1"]
               :bindings {"app/main" "cid-v2" "app/old-main" "cid-v1"}})
    (is (= "cid-v2" (code/resolve-name s "ns-2" "app/main")))
    (is (= "cid-v1" (code/resolve-name s "ns-2" "app/old-main")))
    (is (= #{"ns-1" "ns-2"} (code/history s "ns-2")))))

(deftest execution-receipts-bind-code-artifact-authority-and-data
  (let [s (local/local-store)]
    (code/put-definition! s verify verify (record "cid-main" [] ["graph-write"]))
    (code/put-artifact! s (constantly true) (constantly true)
                        {:artifact-cid "cid-wasm" :code-root-cid "cid-main"
                         :compiler-contract-cid "cid-compiler" :bytes [0]})
    (let [receipt
          {:cid "cid-receipt" :block {:cid "cid-receipt"}
           :code-root-cid "cid-main" :artifact-cid "cid-wasm"
           :compiler-contract-cid "cid-compiler"
           :input-root-cids ["cid-input"] :output-root-cids ["cid-output"]
           :package-lock-cid "cid-lock" :policy-cid "cid-policy"
           :grant-cids ["cid-cacao"] :host-receipt-cids ["cid-host"]
           :granted-effects ["graph-write"] :outcome :success}]
      (is (= #{"graph-write"}
             (:required-effects (code/put-execution-receipt! s verify verify receipt))))
      (is (= "cid-main"
             (:code-root-cid (code/execution-receipt s "cid-receipt"))))
      (is (= :execution/capability-missing
             (:problem
              (ex-data
               (try
                 (code/put-execution-receipt!
                  s verify verify (assoc receipt :cid "denied" :block {:cid "denied"}
                                  :granted-effects []))
                 (catch #?(:clj clojure.lang.ExceptionInfo
                           :cljs cljs.core.ExceptionInfo) e e)))))))))

(deftest portable-execution-identities-are-verified-and-datomized
  (let [s (local/local-store)
        identity (portable-identity)
        record {:cid portable-cid :block {:cid portable-cid} :identity identity}]
    (is (= record (code/put-execution-identity! s verify verify record)))
    (is (= record (code/execution-identity s portable-cid)))
    (is (some #(= [:db/add portable-cid :execution-identity/db-basis (cid "basis")]
                  (:datom %))
              (store/-read s code/datom-stream 0)))
    (is (= :execution-identity/invalid-descriptor
           (:problem (ex-data
                      (try (code/put-execution-identity!
                            s verify verify (assoc record :cid (cid "bad") :block {:cid (cid "bad")}
                                            :identity (assoc identity :unknown true)))
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs cljs.core.ExceptionInfo) e e))))))))

(deftest query-receipts-are-bound-to-the-identity-basis-policy-and-host-receipt
  (let [s (local/local-store)
        identity (portable-identity)
        identity-record {:cid portable-cid :block {:cid portable-cid} :identity identity}
        receipt {:cid (cid "hostreceipt") :block {:cid (cid "hostreceipt")}
                 :execution-identity-cid portable-cid
                 :query-cid (cid "query") :result-cid (cid "result")
                 :basis (cid "basis") :policy-cid (cid "policy")
                 :tenant "acme" :purpose :payment-review :resource-cids ["INV-42"]}]
    (code/put-execution-identity! s verify verify identity-record)
    (is (= receipt (code/put-query-receipt! s verify verify receipt)))
    (is (= receipt (code/query-receipt s (cid "hostreceipt"))))
    (is (= :query-receipt/basis-mismatch
           (:problem (ex-data
                      (try (code/put-query-receipt!
                            s verify verify (assoc receipt :cid (cid "other") :block {:cid (cid "other")}
                                            :basis (cid "otherbasis")))
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs cljs.core.ExceptionInfo) e e))))))))

(deftest missing-block-sync-and-artifact-reuse
  (let [source (local/local-store)
        target (local/local-store)]
    (code/put-definition! source verify verify (record "cid-helper" [] []))
    (code/put-definition! source verify verify
                          (record "cid-main" ["cid-helper"] ["graph-read"]))
    (let [bundle (code/export-closure source "cid-main")]
      (is (= ["cid-helper" "cid-main"]
             (mapv :code.definition/cid bundle)))
      (is (= ["cid-helper" "cid-main"]
             (code/missing-cids target ["cid-main" "cid-helper"])))
      (code/import-closure! target verify verify bundle)
      (is (empty? (code/missing-cids target ["cid-main" "cid-helper"]))))
    (let [compile-count (atom 0)
          opts {:code-root-cid "cid-main" :compiler-contract-cid "cid-compiler"
                :input 41 :authorize identity
                :compile (fn [_ _]
                           (swap! compile-count inc)
                           {:artifact-cid "cid-wasm" :code-root-cid "cid-main"
                            :compiler-contract-cid "cid-compiler" :bytes [0]})
                :verify-artifact (constantly true)
                :verify-artifact-internal (constantly true)
                :run (fn [_ input] (inc input))}
          first-run (code/execute-code-root! target opts)
          second-run (code/execute-code-root! target opts)]
      (is (= 42 (:result first-run)))
      (is (= 42 (:result second-run)))
      (is (= 1 @compile-count) "the second execution reuses the artifact"))))

(deftest two-xrpc-nodes-transfer-only-missing-blocks-and-reuse-artifact
  (let [source-backend (local/local-store)
        target-backend (local/local-store)
        source (remote/kotobase-store (xrpc source-backend))
        target (remote/kotobase-store (xrpc target-backend))
        artifact {:artifact-cid "cid-wasm" :code-root-cid "cid-main"
                  :compiler-contract-cid "cid-compiler" :bytes [0 97 115 109]}]
    (code/put-definition! source verify verify (record "cid-helper" [] []))
    (code/put-definition! source verify verify
                          (record "cid-main" ["cid-helper"] ["graph-read"]))
    ;; Prove delta transfer: target already owns the dependency.
    (code/put-definition! target verify verify (record "cid-helper" [] []))
    (code/put-artifact! source #(= [0 97 115 109] (:bytes %)) #(= [0 97 115 109] (:bytes %)) artifact)
    (is (= :code/disclosure-denied
           (:problem
            (ex-data
             (try
               (code/sync-code-root! source target verify verify
                                     {:code-root-cid "cid-main"})
               (catch #?(:clj clojure.lang.ExceptionInfo
                         :cljs cljs.core.ExceptionInfo) e e))))))
    (let [sync (code/sync-code-root!
                source target verify verify
                {:code-root-cid "cid-main"
                 :authorize (constantly true)
                 :compiler-contract-cid "cid-compiler"
                 :verify-artifact #(= [0 97 115 109] (:bytes %))})
          compile-count (atom 0)
          executed (code/execute-code-root!
                    target
                    {:code-root-cid "cid-main"
                     :compiler-contract-cid "cid-compiler"
                     :input 41 :authorize identity
                     :compile (fn [& _] (swap! compile-count inc))
                     :run (fn [_ x] (inc x))})]
      (is (= ["cid-main"] (:definition-cids sync)))
      (is (:artifact-transferred? sync))
      (is (= 42 (:result executed)))
      (is (zero? @compile-count) "verified remote artifact was reused"))))

(deftest garbage-collection-is-retention-aware-and-non-destructive
  (let [s (local/local-store)]
    (code/put-definition! s verify verify (record "cid-dep" [] []))
    (code/put-definition! s verify verify (record "cid-live" ["cid-dep"] []))
    (code/put-definition! s verify verify (record "cid-garbage" [] []))
    (code/pin-root! s {:id "legal-1" :kind :legal-hold :root-cid "cid-live"})
    (is (= #{"cid-live" "cid-dep"} (code/retained-definition-cids s)))
    (is (= #{"cid-garbage"} (:candidates (code/gc-plan s))))
    (is (some? (code/get-definition s "cid-garbage"))
        "planning never performs an unauthorized destructive delete")))

(deftest sealed-views-hash-qualified-names-and-three-way-merges
  (let [s (local/local-store)
        sealed (assoc (record (cid "secret") [] ["graph-read"])
                      :visibility :sealed :sealed-block-cid (cid "envelope"))]
    (code/put-definition! s verify verify sealed)
    (let [hidden (code/definition-view s (cid "secret") (constantly false))]
      (is (= (cid "envelope") (:code.definition/sealed-block-cid hidden)))
      (is (nil? (:code.definition/block hidden)))
      (is (nil? (:code.definition/effects hidden))))
    (is (some? (:code.definition/block
                (code/definition-view s (cid "secret") (constantly true)))))
    (code/put-namespace-commit!
     s verify verify {:cid "cid-ns" :block {:cid "cid-ns"} :parents []
               :bindings {"app/main" (cid "secret")}})
    (is (= (cid "secret")
           (code/resolve-qualified-name
            s "cid-ns" (str "app/main#" (subs (cid "secret") 0 10)))))
    (is (= :namespace/hash-qualifier-mismatch
           (:problem
            (ex-data
             (try (code/resolve-qualified-name s "cid-ns" "app/main#nope")
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core.ExceptionInfo) e e))))))
    (is (= {:bindings {"a" "2" "b" "3"} :conflicts {}}
           (code/merge-bindings {"a" "1"} {"a" "2"} {"a" "1" "b" "3"})))
    (is (contains? (:conflicts
                    (code/merge-bindings {"a" "1"} {"a" "2"} {"a" "3"}))
                   "a"))))

(deftest version-migration-requires-content-and-authority-verification
  (let [s (local/local-store)
        migration {:cid "cid-migration" :block {:cid "cid-migration"}
                   :from-cid "cid-v1" :to-cid "cid-v2"
                   :from-contract-cid "contract-v1"
                   :to-contract-cid "contract-v2"
                   :authority-cid "did:key:publisher"}]
    (code/put-definition! s verify verify (record "cid-v1" [] []))
    (code/put-definition! s verify verify (record "cid-v2" [] []))
    (is (= :migration/authority-denied
           (:problem
            (ex-data
             (try (code/put-identity-migration! s verify verify (constantly false)
                                                migration)
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core.ExceptionInfo) e e))))))
    (code/put-identity-migration! s verify verify (constantly true) migration)
    (is (= ["cid-v2"] (mapv :to-cid (code/migrations-from s "cid-v1"))))))

(deftest revoked-retention-pins-stop-rooting-code-but-remain-auditable
  (let [s (local/local-store)]
    (code/put-definition! s verify verify (record "cid-held" [] []))
    (code/pin-root! s {:id "hold" :kind :legal-hold :root-cid "cid-held"})
    (is (= #{"cid-held"} (code/retention-roots s)))
    (code/revoke-pin! s "hold" "hold released")
    (is (empty? (code/retention-roots s)))
    (is (= [:pin :revoke]
           (mapv :op (store/-read s code/retention-stream 0))))))
