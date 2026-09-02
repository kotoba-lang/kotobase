(ns run-tests
  "The suite under ClojureScript.

  kotobase is the datom plane itself, compiled into both live Workers.

  This repo had no ClojureScript entry, so the murakumo fleet could only
  gate its JVM half. Counts were measured to match before this was added --
  that measurement, not the `.cljc` extension, is what earns a second gate.
  Measured 2026-08-17 on datom-source: a portable suite can be green on the
  JVM and red under nbb for reasons production does not have (SCI deftype
  behaviour), so `.cljc` alone is not grounds.


  ⚠ **This does not run on a fleet node yet, and the reason is not this repo's
  code.** Its transitive git pins reach several libraries at two different
  shas, and `gates/nbb-cross-runtime.cljs` puts every (lib, sha) it walks on
  the classpath -- tools.deps resolves such a diamond to one version, that walk
  cannot. Measured 2026-08-18: the suite dies at `Cannot read properties of
  undefined (reading 'lastIndexOf')` inside a CID decode, with nothing wrong
  here. The entry is committed so the work is done when the pins are aligned.

      npx nbb --classpath src:test run-tests.cljs"
  (:require [cljs.test :as t]
            [kotobase.authorized-query-test]
            [kotobase.classification-agreement-test]
            [kotobase.classification-test]
            [kotobase.code-graph-test]
            [kotobase.effect-contract-test]
            [kotobase.erasure-test]
            [kotobase.evidence-test]
            [kotobase.execution-contract-test]
            [kotobase.execution-keys-test]
            [kotobase.governed-execution-test]
            [kotobase.guarded-test]
            [kotobase.sealed-store-test]
            [kotobase.store-test]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (js/process.exit 1)))

;; A pattern, not a second list of namespaces to run: a runner that repeats
;; the list can fall behind the suite and report a subset as a pass.
(t/run-all-tests #"^kotobase\..*-test$")
