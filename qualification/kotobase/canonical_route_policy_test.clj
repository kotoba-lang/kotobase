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
