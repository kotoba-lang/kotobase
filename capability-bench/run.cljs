#!/usr/bin/env nbb
;; Capability benchmark runner.
;;
;;   nbb --classpath "$(nbb setup.cljs --print-classpath)" run.cljs [opts]
;;
;; Options (all optional):
;;   --entities N     entities in the workload           (default 4000)
;;   --updates N      steady-state transactions          (default 200)
;;   --shards N       shard actors for the actordb shape (default 8)
;;   --backends a,b   subset of kotobase-prolly,orbit,ceramic,actordb,holochain
;;   --fvm            also run every shape inside an FVM host/guest boundary
;;   --out FILE       write the full result EDN here     (default results/latest.edn)

(ns run
  (:require [clojure.string :as str]
            [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.workload :as w]
            [kotobase.capability.bench :as bench]
            [kotobase.capability.backend.kotobase-prolly :as prolly]
            [kotobase.capability.backend.orbit :as orbit]
            [kotobase.capability.backend.ceramic :as ceramic]
            [kotobase.capability.backend.actordb :as actordb]
            [kotobase.capability.backend.holochain :as holochain]
            ["fs" :as fs]
            ["path" :as path]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))

(defn arg
  ([k] (arg k nil))
  ([k default]
   (loop [xs argv]
     (cond
       (empty? xs) default
       (= (first xs) (str "--" k)) (or (second xs) default)
       :else (recur (rest xs))))))

(defn flag? [k] (boolean (some #(= % (str "--" k)) argv)))

(defn pad [x n]
  (let [s (str x)]
    (str s (str/join (repeat (max 1 (- n (count s))) " ")))))

(def builders
  {"kotobase-prolly" prolly/make
   "orbit" orbit/make
   "ceramic" ceramic/make
   "actordb" actordb/make
   "holochain" holochain/make})

(def order ["kotobase-prolly" "orbit" "ceramic" "actordb" "holochain"])

(defn- num-arg [k d] (js/parseInt (arg k (str d)) 10))

(defn -main []
  (let [entities (num-arg "entities" 4000)
        updates (num-arg "updates" 200)
        shards (num-arg "shards" 8)
        chosen (if-let [s (arg "backends")] (str/split s #",") order)
        fvm? (flag? "fvm")
        out (arg "out" "results/latest.edn")
        wl (w/make {:entities entities :updates updates})
        _ (println "workload:" (pr-str (:meta wl)))
        variants (concat (for [b chosen] [b false])
                         (when fvm? (for [b chosen] [b true])))
        results
        (vec
         (for [[b with-fvm?] variants]
           (let [store (bs/make)
                 backend ((get builders b) {:store store :shards shards :fvm? with-fvm?})]
             (println "running" (name (:id backend)) "…")
             (let [r (bench/run backend store wl)]
               (println "  " (get-in r [:phases :steady-state-writes :per-transaction :puts])
                        "puts/txn," (get-in r [:storage :blocks]) "blocks stored")
               r))))
        backends (mapv :backend results)]
    (println)
    (println "=== capability matrix ===")
    (println (cap/render-matrix
              (filterv #(not (str/includes? (name (:id %)) "+fvm")) backends)))
    (println)
    (println "=== write cost per steady-state transaction ===")
    (println (str (pad "backend" 22) (pad "puts" 9) (pad "put-bytes" 12)
                  (pad "gets" 9) (pad "get-bytes" 12) (pad "msgs" 7) "ms"))
    (doseq [r results]
      (let [p (get-in r [:phases :steady-state-writes :per-transaction])]
        (println (str (pad (name (get-in r [:backend :id])) 22)
                      (pad (:puts p 0) 9) (pad (:put-bytes p 0) 12)
                      (pad (:gets p 0) 9) (pad (:get-bytes p 0) 12)
                      (pad (:messages p 0) 7) (:ms p 0)))))
    (println)
    (println "=== query cost per op ===")
    (doseq [phase [:point-read :find-by-value :range-scan :snapshot-read]]
      (println (str "-- " (name phase)))
      (doseq [r results]
        (let [ph (get-in r [:phases phase])
              p (:per-op ph)]
          (println (str (pad (name (get-in r [:backend :id])) 22)
                        (pad (:gets p 0) 10) (pad (:get-bytes p 0) 13)
                        (pad (:ms p 0) 10)
                        (pad (str/join "," (map name (:via ph))) 24)
                        (if-let [u (:unsupported ph)]
                          (str "UNSUPPORTED(" (name (:capability u)) ")")
                          (str/join " " (for [[k v] (:annotations ph)]
                                          (str "Σ" (name k) "=" v)))))))))
    (println)
    (println "=== replica sync ===")
    (doseq [phase [:replica-sync-since-load :replica-sync-from-empty
                   :replica-sync-interest-scoped]]
      (println (str "-- " (name phase)))
      (doseq [r results]
        (let [ph (get-in r [:phases phase])]
          (println (str (pad (name (get-in r [:backend :id])) 22)
                        (pad (str "blocks-read=" (or (get-in ph [:report :blocks-read])
                                                     (:gets ph 0))) 22)
                        (pad (str "entries=" (or (get-in ph [:report :entries-transferred]) "-")) 18)
                        (pad (str "ms=" (:ms ph 0)) 16)
                        (pad (name (or (get-in ph [:report :via]) :none)) 30)
                        (if-let [cp (get-in ph [:report :critical-path-blocks])]
                          (str "critical-path=" cp)
                          ""))))))
    (println)
    (println "=== warrant gossip (interest-scoped neighbourhood) ===")
    (println "UNSUPPORTED is the honest answer where the architecture has no warrants;")
    (println "do not read a missing phase as zero cost.")
    (doseq [r results]
      (let [ph (get-in r [:phases :warrant-gossip])
            rep (:report ph)]
        (println (str (pad (name (get-in r [:backend :id])) 22)
                      (if (cap/unsupported? rep)
                        (str "UNSUPPORTED(" (name (:capability rep)) ")")
                        (str (pad (str "warrants=" (or (:warrants rep) "-")) 18)
                             (pad (str "fanout=" (or (:fanout rep) "-")) 14)
                             (pad (str "msgs=" (or (:messages rep)
                                                   (get ph :messages 0))) 14)
                             (pad (str "blocks-read=" (or (:blocks-read rep)
                                                          (:gets ph 0))) 22)
                             (str "ms=" (:ms ph 0))))))))
    (when fvm?
      (println)
      (println "=== FVM host/guest boundary ===")
      (println "crossings and copied bytes are what a deterministic executor adds;")
      (println "multiply crossings by your own per-syscall cost, not by the ms above.")
      (println (str (pad "backend" 22) (pad "cross/txn" 13) (pad "copied-bytes/txn" 20)
                    (pad "cross/point-read" 20) "cross/full-sync"))
      (doseq [r results
              :when (str/includes? (name (get-in r [:backend :id])) "+fvm")]
        (println (str (pad (name (get-in r [:backend :id])) 22)
                      (pad (get-in r [:phases :steady-state-writes :per-transaction
                                      :boundary-crossings] 0) 13)
                      (pad (get-in r [:phases :steady-state-writes :per-transaction
                                      :boundary-copied-bytes] 0) 20)
                      (pad (get-in r [:phases :point-read :per-op :boundary-crossings] 0) 20)
                      (get-in r [:phases :replica-sync-from-empty :boundary-crossings] 0)))))
    (println)
    (println "=== stored state ===")
    (doseq [r results]
      (println (str (pad (name (get-in r [:backend :id])) 22)
                    (pad (str "blocks=" (get-in r [:storage :blocks])) 18)
                    (str "bytes=" (get-in r [:storage :bytes])))))
    (let [payload {:generated-by "kotobase capability-bench"
                   :workload (:meta wl)
                   :shards shards
                   :results results}]
      (fs/mkdirSync (path/dirname out) #js {:recursive true})
      (fs/writeFileSync out (with-out-str (prn payload)))
      (println)
      (println "wrote" out))))

(-main)
