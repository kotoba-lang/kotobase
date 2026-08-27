(ns kotobase.async-test-runner
  (:require [cljs.test :as t]
            [kotobase.canonical-causal-commit-async-test]))

(defmethod t/report [::t/default :end-run-tests] [summary]
  (when-not (t/successful? summary)
    (js/process.exit 1)))

(defn -main []
  (t/run-tests 'kotobase.canonical-causal-commit-async-test))

(set! *main-cli-fn* -main)
