(ns test-disclosure
  (:require [cljs.test :as t]
            [kotobase.disclosure-grant-test]
            [kotobase.disclosure-worker-test]))
(defmethod t/report [::t/default :end-run-tests] [m]
  (when-not (t/successful? m) (js/process.exit 1)))
(t/run-tests 'kotobase.disclosure-grant-test 'kotobase.disclosure-worker-test)
