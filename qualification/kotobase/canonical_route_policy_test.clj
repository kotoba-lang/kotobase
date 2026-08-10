(ns kotobase.canonical-route-policy-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(deftest official-qualification-ci-does-not-install-a-rust-toolchain
  (let [workflow (slurp (io/file ".github/workflows/ci.yml"))]
    (testing "the formal Kotoba qualification remains independent of Rust"
      (doseq [forbidden [#"dtolnay/rust-toolchain"
                         #"\bcargo\b"
                         #"\brustc\b"
                         #"wasm32-unknown-unknown"]]
        (is (not (re-find forbidden workflow))
            (str "forbidden canonical CI toolchain pattern: " forbidden))))))

(deftest external-traversal-has-no-positional-provider-inventory
  (let [source (slurp (io/file "kotoba/cid_external_dag_traversal.kotoba"))]
    (testing "the provider supplies immutable CID blocks, not node ordinals"
      (is (not (re-find #"node[0-9]" source)))
      (is (not (.contains source "index:")))
      (is (.contains source "string-index-new"))
      (is (.contains source "string-index-assoc")))
    (testing "the public surface exposes data-driven results"
      (is (.contains source "external-closure-count"))
      (is (.contains source "external-root-height"))
      (is (not (.contains source "external-closure-7"))))))

(deftest external-transaction-replay-keeps-provider-semantics-outside
  (let [source (slurp (io/file "kotoba/cid_external_transaction_replay.kotoba"))]
    (testing "the guest accepts only immutable object reads and hashing"
      (is (.contains source ":hash/sha256"))
      (is (.contains source ":object/get-stream"))
      (is (not (.contains source ":state/transact")))
      (is (not (.contains source ":storage/transact"))))
    (testing "the page carries IPLD links and the guest owns atom admission"
      (is (.contains source "d82a58250001711220"))
      (is (.contains source "transaction-atom-count"))
      (is (.contains source "check-transaction-cid-at"))
      (is (.contains source "check-replay-step-at"))
      (is (.contains source "check-replay-root")))
    (testing "the public API requires bounded per-index scheduling"
      (is (not (.contains source "check-transaction-cids")))
      (is (not (.contains source "atom-count-loop")))
      (is (.contains source "(defn main [] :i64 (check-page-cid))")))
    (testing "canonical fixture transaction bytes are not embedded"
      (is (not (.contains source
                          "a165717561647382a3616f65416c6963656170653a6e616d656173626531"))))))
