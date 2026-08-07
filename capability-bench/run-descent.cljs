#!/usr/bin/env nbb
;; Client-side index descent: hops MEASURED, not declared.
;;
;;   nbb --classpath "$(nbb setup.cljs --print-classpath)" run-descent.cljs [opts]
;;
;;   --entities N    datom workload size   (default 4000)
;;   --reads N       point reads per run   (default 50)
;;   --out FILE      result EDN            (default results/descent.edn)
;;
;; ## Why this benchmark exists
;;
;; `run-storage.cljs` takes `hops` as a PARAMETER — its own docstring says
;; "hops is supplied by the caller from the algorithm's dependency structure".
;; That is honest, but it means every hop figure downstream of it (including
;; superproject ADR-2608070400's tier table) is a declaration, not a
;; measurement. When the server can read the index it descends the tree
;; itself and the client pays one round; when the index is opaque to the
;; server the descent moves to the client and each level it cannot already
;; resolve becomes another dependent round trip.
;;
;; A dependent round is exactly a cache MISS on the descent path: the client
;; cannot know the next node's CID until the current node's bytes arrive. A
;; hit costs no round. So misses-along-the-path IS the hop count, and it is
;; directly countable rather than asserted.
;;
;; ## What this does and does not measure
;;
;; Measured: requests, cache hits/misses, and the per-operation dependent
;; chain depth, against the real `prolly-tree` over real DAG-CBOR/CIDv1
;; blocks.
;;
;; NOT measured: latency. There is no network here. Multiply the measured
;; hop count by your own RTT — that is the whole point of counting hops
;; separately from wall clock.
;;
;; The cache is FIFO, not LRU (`kotobase.remote.profile`), which understates
;; the hit rate a real client would get. Stated because it changes the
;; numbers, not because it invalidates them.

(ns run-descent
  (:require [clojure.string :as str]
            [prolly-tree.core :as pt]
            [kotobase.capability.workload :as w]
            [kotobase.remote.profile :as rp]
            [kotobase.lake.columnar :as lake]
            ["fs" :as fs]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn arg [k d] (loop [xs argv]
                  (cond (empty? xs) d
                        (= (first xs) (str "--" k)) (or (second xs) d)
                        :else (recur (rest xs)))))
(defn num-arg [k d] (js/parseInt (arg k (str d)) 10))
(defn pad [x n] (let [s (str x)] (str s (str/join (repeat (max 1 (- n (count s))) " ")))))
(defn lpad [x n] (let [s (str x)] (str (str/join (repeat (max 1 (- n (count s))) " ")) s)))
(defn r2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn ports [p] {:put! (:put! p) :get (:get p)})

(defn build-eavt! [p datoms]
  (let [{:keys [put!]} (ports p)]
    (pt/build-tree put! (vec (sort-by first
                                      (map (fn [d] [(w/eavt-key (w/datom-e d) (w/datom-a d) (w/datom-t d))
                                                    {"v" (w/datom-v d)}])
                                           datoms))))))

;; ── the instrument ───────────────────────────────────────────────────────
;;
;; Wrap the profile's `get` so each operation's dependent chain is recorded.
;; One entry per operation: how many fetches actually went to the store
;; (= dependent rounds) and how many were served from the client cache.
(defn instrumented-get [p log]
  (let [raw (:get p)]
    (fn [cid]
      (let [before (:cache-hits (rp/stats p))
            b (raw cid)
            after (:cache-hits (rp/stats p))]
        ;; a hit bumps :cache-hits; anything else went to the store
        (if (> after before)
          (swap! log update :hits inc)
          (swap! log update :misses inc))
        b))))

(defn run-one
  "One cache size. Returns measured per-op descent numbers."
  [datoms point-keys cache-size]
  (let [p (rp/make {:cache-size cache-size})
        eavt (build-eavt! p datoms)
        height (lake/tree-height (:get (ports p)) eavt)
        ;; Warm exactly as a real client would: the cache is whatever the
        ;; previous reads left in it. No pre-seeding — pre-seeding would be
        ;; assuming the answer.
        _ (rp/reset-stats! p)
        per-op (atom [])]
    (doseq [e point-keys]
      (let [log (atom {:hits 0 :misses 0})
            g (instrumented-get p log)]
        (pt/scan-prefix g eavt (w/eavt-entity-prefix e))
        (swap! per-op conj @log)))
    (let [ops (count @per-op)
          tot-miss (reduce + (map :misses @per-op))
          tot-hit (reduce + (map :hits @per-op))
          s (rp/stats p)]
      {:cache-size cache-size
       :tree-height height
       :ops ops
       ;; MEASURED dependent rounds per operation
       :hops-per-op (r2 (/ (double tot-miss) ops))
       :cache-hits-per-op (r2 (/ (double tot-hit) ops))
       :hit-rate (r2 (* 100.0 (/ (double tot-hit) (max 1 (+ tot-hit tot-miss)))))
       :requests-get-per-op (r2 (/ (double (:requests-get s)) ops))
       :bytes-get-per-op (r2 (/ (double (:bytes-get s)) ops))})))

(defn -main []
  (let [entities (num-arg "entities" 4000)
        reads (num-arg "reads" 50)
        out (arg "out" "results/descent.edn")
        wl (w/make {:entities entities :updates 0})
        datoms (vec (mapcat :datoms (:load-txns wl)))
        point-keys (take reads (:point (:queries wl)))
        sizes [0 1 10 100 1000 10000]
        rows (mapv #(run-one datoms point-keys %) sizes)
        baseline (first (filter #(zero? (:cache-size %)) rows))]
    (println "=== client-side index descent — hops MEASURED (not declared) ===")
    (println (str "workload: " entities " entities / " (count datoms) " datoms / "
                  reads " point reads, tree height " (:tree-height baseline)))
    (println "dependent round = cache miss on the descent path (next CID unknown")
    (println "until current node's bytes arrive). No network here: multiply hops")
    (println "by your own RTT. Cache is FIFO, not LRU — understates hit rate.")
    (println)
    (println (str (pad "cache" 9) (lpad "hops/op" 9) (lpad "hits/op" 9)
                  (lpad "hit%" 8) (lpad "GET/op" 9) (lpad "bytes/op" 12)
                  (lpad "vs cache=0" 12)))
    (doseq [r rows]
      (println (str (pad (:cache-size r) 9)
                    (lpad (:hops-per-op r) 9)
                    (lpad (:cache-hits-per-op r) 9)
                    (lpad (str (:hit-rate r) "%") 8)
                    (lpad (:requests-get-per-op r) 9)
                    (lpad (:bytes-get-per-op r) 12)
                    (lpad (str (r2 (/ (:hops-per-op r)
                                      (max 0.01 (:hops-per-op baseline)))) "x") 12))))
    (println)
    (println "=== latency floor at your RTT (hops x RTT) ===")
    (println (str (pad "cache" 9) (lpad "1ms" 9) (lpad "10ms" 9) (lpad "50ms" 9) (lpad "100ms" 9)))
    (doseq [r rows]
      (println (str (pad (:cache-size r) 9)
                    (lpad (str (r2 (* 1 (:hops-per-op r))) "ms") 9)
                    (lpad (str (r2 (* 10 (:hops-per-op r))) "ms") 9)
                    (lpad (str (r2 (* 50 (:hops-per-op r))) "ms") 9)
                    (lpad (str (r2 (* 100 (:hops-per-op r))) "ms") 9))))
    (.writeFileSync fs out (pr-str {:bench :descent
                                    :workload {:entities entities :datoms (count datoms)
                                               :reads reads}
                                    :note (str "hops are MEASURED dependent rounds (cache misses "
                                               "on the descent path), not declared. FIFO cache. "
                                               "No network: multiply by RTT.")
                                    :rows rows}))
    (println)
    (println (str "wrote " out))))

(-main)
