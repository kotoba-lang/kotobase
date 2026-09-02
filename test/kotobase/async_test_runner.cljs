(ns kotobase.async-test-runner
  (:require [cljs.test :as t]
            [kotobase.canonical-causal-commit-async-test]
            [kotobase.evidence-test]
            [kotobase.governed-execution-async-test]
            ;; the portable governed suite runs here too, under the real
            ;; ClojureScript compiler. `kotobase.execution-identity` writes
            ;; down a CID measured on the JVM; asserting it from a second
            ;; runtime is the cross-runtime claim, and asserting it anywhere
            ;; else is only self-consistency
            [kotobase.governed-execution-test]))

(defmethod t/report [::t/default :end-run-tests] [summary]
  (when-not (t/successful? summary)
    (js/process.exit 1)))

(defn -main []
  (t/run-tests 'kotobase.canonical-causal-commit-async-test
               'kotobase.evidence-test
               'kotobase.governed-execution-async-test
               'kotobase.governed-execution-test))

(set! *main-cli-fn* -main)
