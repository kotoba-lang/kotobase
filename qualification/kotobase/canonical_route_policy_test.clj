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
