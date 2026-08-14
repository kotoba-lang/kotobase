#!/usr/bin/env nbb
;; Cross-backend agreement check.
;;
;;   nbb --classpath "$(nbb setup.cljs --print-classpath)" verify.cljs
;;
;; A performance comparison between backends is worthless if they do not
;; return the same answers, so this runs the same small workload through all
;; of them and asserts that every supported operation agrees. Exits non-zero
;; on the first disagreement.

(ns verify
  (:require [clojure.string :as str]
            [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.workload :as w]
            [kotobase.capability.backend :as be]
            [kotobase.capability.backend.kotobase-prolly :as prolly]
            [kotobase.capability.backend.orbit :as orbit]
            [kotobase.capability.backend.ceramic :as ceramic]
            [kotobase.capability.backend.actordb :as actordb]
            [kotobase.capability.backend.holochain :as holochain]))

(def failures (atom 0))

(defn check! [what expected actual]
  (if (= expected actual)
    (println "  ok  " what)
    (do (swap! failures inc)
        (println "  FAIL" what)
        (println "        expected:" (pr-str expected))
        (println "        actual:  " (pr-str actual)))))

(defn build []
  (for [f [prolly/make orbit/make ceramic/make actordb/make holochain/make]]
    (let [store (bs/make)]
      [(f {:store store :shards 4}) store])))

(defn -main []
  (let [wl (w/make {:entities 200 :batch 50 :updates 10})
        backends (vec (build))]
    (doseq [[b _] backends]
      (doseq [txn (concat (:load-txns wl) (:update-txns wl))]
        (be/transact! b txn)))
    (let [[ref-b _] (first backends)
          probes (take 15 (get-in wl [:queries :point]))
          value-probes (take 5 (get-in wl [:queries :by-value]))
          range-probes (take 5 (get-in wl [:queries :range]))]
      (println "reference backend:" (name (:id ref-b)))
      (doseq [[b _] (rest backends)]
        (println (str "\nchecking " (name (:id b)) " against " (name (:id ref-b))))
        (doseq [e probes]
          (check! (str "read-entity " (w/entity-key e))
                  (:value (be/read-entity ref-b e))
                  (:value (be/read-entity b e))))
        (doseq [[a v] value-probes]
          (check! (str "find-by-value " a "=" v)
                  (set (:value (be/find-by-value ref-b a v)))
                  (set (:value (be/find-by-value b a v)))))
        (doseq [[a lo hi] range-probes]
          (check! (str "range-scan " a " " lo ".." hi)
                  (set (:value (be/range-scan ref-b a lo hi)))
                  (set (:value (be/range-scan b a lo hi)))))
        (let [t (- (get-in wl [:meta :total-t]) 3)
              e (first probes)
              expected (be/snapshot-read ref-b t e)
              actual (be/snapshot-read b t e)]
          (if (cap/unsupported? actual)
            (println "  n/a  snapshot-read — not supported:"
                     (name (:capability actual)))
            (check! (str "snapshot-read t=" t) (:value expected) (:value actual))))))
    (println)
    (if (zero? @failures)
      (println "all backends agree")
      (do (println @failures "disagreement(s)")
          (set! (.-exitCode js/process) 1)))))

(-main)
