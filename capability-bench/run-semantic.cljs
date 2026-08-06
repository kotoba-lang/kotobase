#!/usr/bin/env nbb
;; Semantic Merkle Lisp Database benchmark.
;;
;;   nbb --classpath "$(nbb setup.cljs --print-classpath)" run-semantic.cljs [opts]
;;
;;   --definitions N   corpus size                    (default 3000)
;;   --nodes N         target nodes per definition    (default 40)
;;   --fan-out N       callable earlier definitions   (default 8)
;;   --edit-pct N      percent of definitions edited  (default 10)
;;   --threshold N     semantic-chunk region bytes    (default 256)
;;   --eval-slice N    definitions actually evaluated (default 200)
;;   --out FILE        result EDN                     (default results/semantic.edn)

(ns run-semantic
  (:require [clojure.string :as str]
            [kotoba.codebase.semantic-code :as sc]
            [kotobase.capability.blockstore :as bs]
            [kotobase.semantic.corpus :as corpus]
            [kotobase.semantic.chunk :as chunk]
            [kotobase.semantic.plane :as plane]
            ["fs" :as fs]
            ["path" :as path]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))

(defn arg [k default]
  (loop [xs argv]
    (cond (empty? xs) default
          (= (first xs) (str "--" k)) (or (second xs) default)
          :else (recur (rest xs)))))

(defn num-arg [k d] (js/parseInt (arg k (str d)) 10))
(defn pad [x n] (let [s (str x)] (str s (str/join (repeat (max 1 (- n (count s))) " ")))))
(defn now [] (js/performance.now))
(defn round [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn compile-corpus
  "Real lowering: alpha-normalised, name-free, DAG-CBOR, CIDv1. The compiler
  already hands back the type block, the dependency CIDs and the declared
  effects, so nothing here re-derives them."
  [forms]
  (let [out (sc/compile-definitions forms)]
    (vec (for [[nm d] (sort-by (comp str first) (:definitions out))]
           (assoc d :name (str nm) :kind "term")))))

(defn -main []
  (let [definitions (num-arg "definitions" 3000)
        nodes (num-arg "nodes" 40)
        fan-out (num-arg "fan-out" 8)
        edit-pct (num-arg "edit-pct" 10)
        threshold (num-arg "threshold" 256)
        eval-slice (num-arg "eval-slice" 200)
        out-file (arg "out" "results/semantic.edn")
        dep-shape (keyword (arg "dep-shape" "library"))
        shape-n (num-arg "shape-definitions" 150)
        {:keys [forms stats]} (corpus/make {:definitions definitions :nodes nodes
                                            :fan-out fan-out :dep-shape dep-shape})
        _ (println "corpus (generated):" (pr-str (into {} (map (fn [[k v]]
                                                                 [k (if (number? v) (round v) v)])
                                                               stats))))
        _ (println "corpus (real .kotoba, for comparison):" (pr-str corpus/measured-shape))
        t0 (now)
        compiled (compile-corpus forms)
        t1 (now)
        _ (println "lowered" (count compiled) "definitions in" (int (- t1 t0)) "ms"
                   "via kotoba.codebase.semantic-code")
        n-edit (max 1 (quot (* definitions edit-pct) 100))
        {edited-forms :forms edited :edited} (corpus/edit forms n-edit 424242)
        compiled-v2 (compile-corpus edited-forms)
        by-name (into {} (map (juxt :name :cid) compiled))
        by-name-v2 (into {} (map (juxt :name :cid) compiled-v2))
        changed (into #{} (for [[n c] by-name :when (not= c (get by-name-v2 n))] n))

        ;; ── granularity ────────────────────────────────────────────────────
        granularity
        (vec (for [strategy chunk/strategies]
               (let [store (bs/make)
                     ta (now)
                     v1 (chunk/write-corpus! store strategy compiled
                                             {:region-threshold threshold})
                     tb (now)
                     blocks-v1 (bs/block-count store)
                     bytes-v1 (bs/stored-bytes store)
                     ;; hydrate a sample for execution
                     _ (bs/reset-stats! store)
                     tc (now)
                     fetches (mapv (fn [cid] (second (chunk/hydrate store cid)))
                                   (take 100 (:roots v1)))
                     td (now)
                     hydrate-stats (bs/stats store)
                     ;; incremental update into the SAME store: dedup makes
                     ;; structural sharing measurable rather than asserted
                     te (now)
                     v2 (chunk/write-corpus! store strategy compiled-v2
                                             {:region-threshold threshold})
                     tf (now)]
                 {:strategy strategy
                  :blocks blocks-v1
                  :bytes bytes-v1
                  :blocks-per-definition (round (/ blocks-v1 (count compiled)))
                  :bytes-per-definition (round (/ bytes-v1 (count compiled)))
                  :puts-v1 (:puts (:counters v1))
                  :dup-puts-v1 (:dup-puts (:counters v1) 0)
                  :write-ms (round (- tb ta))
                  :hydrate-blocks-mean (round (/ (reduce + fetches) (count fetches)))
                  :hydrate-blocks-max (reduce max fetches)
                  :hydrate-ms-per-def (round (/ (- td tc) (count fetches)))
                  :hydrate-get-bytes-per-def (round (/ (:get-bytes hydrate-stats)
                                                       (count fetches)))
                  :update-blocks-added (:blocks-added v2)
                  :update-puts (:puts (:counters v2))
                  :update-dup-puts (:dup-puts (:counters v2) 0)
                  :update-bytes-added (- (bs/stored-bytes store) bytes-v1)
                  :update-ms (round (- tf te))})))

        ;; ── query plane ────────────────────────────────────────────────────
        qstore (bs/make)
        _ (chunk/write-corpus! qstore :per-definition compiled {:region-threshold threshold})
        index (plane/build-index! qstore compiled)
        idx-q (plane/query-by-effect qstore (:root index) "http/fetch")
        scan-q (plane/scan-by-effect qstore compiled)
        ;; Seed from the definitions everything else calls. Seeding from
        ;; whatever an edit happened to touch measures the edit, not the graph.
        hub-cids (mapv :cid (take 20 compiled))
        leaf-cids (mapv :cid (take-last 20 compiled))
        prop-hub (plane/propagate qstore (:root index) hub-cids)
        prop-leaf (plane/propagate qstore (:root index) leaf-cids)

        ;; ── namespace plane ────────────────────────────────────────────────
        nstore (bs/make)
        bindings-v1 (into {} (map (juxt :name :cid) compiled))
        bindings-v2 (into {} (map (juxt :name :cid) compiled-v2))
        flat-before (bs/stored-bytes nstore)
        _ (plane/flat-namespace-commit! nstore bindings-v1 [])
        flat-1 (bs/stored-bytes nstore)
        _ (plane/flat-namespace-commit! nstore bindings-v2 [])
        flat-2 (bs/stored-bytes nstore)
        tstore (bs/make)
        troot (plane/tree-namespace-commit! tstore bindings-v1 nil)
        tree-1 (bs/stored-bytes tstore)
        _ (plane/tree-namespace-commit! tstore bindings-v2 troot)
        tree-2 (bs/stored-bytes tstore)
        ;; a one-definition leaf edit is what an incremental commit really is
        {small-forms :forms} (corpus/edit forms 1 424242 :leaf)
        compiled-small (compile-corpus small-forms)
        bindings-small (into {} (map (juxt :name :cid) compiled-small))
        nstore2 (bs/make)
        _ (plane/flat-namespace-commit! nstore2 bindings-v1 [])
        flat-s1 (bs/stored-bytes nstore2)
        _ (plane/flat-namespace-commit! nstore2 bindings-small [])
        flat-s2 (bs/stored-bytes nstore2)
        tstore2 (bs/make)
        troot2 (plane/tree-namespace-commit! tstore2 bindings-v1 nil)
        tree-s1 (bs/stored-bytes tstore2)
        _ (plane/tree-namespace-commit! tstore2 bindings-small troot2)
        tree-s2 (bs/stored-bytes tstore2)

        ;; ── evaluation plane ───────────────────────────────────────────────
        estore (bs/make)
        eval-defs (vec (take eval-slice compiled))
        _ (chunk/write-corpus! estore :per-definition compiled {:region-threshold threshold})
        st (plane/eval-state 2000000)
        tg (now)
        _ (doseq [d eval-defs] (plane/evaluate estore st (:cid d) [3 5]))
        th (now)
        cold @st
        ;; Rebuild after an edit. Unchanged CIDs hit the cache; changed ones
        ;; and every effectful definition do not. Done twice, because a leaf
        ;; edit and a hub edit are different regimes, not different sizes.
        rebuild
        (fn [target]
          (let [{ef :forms} (corpus/edit forms n-edit 424242 target)
                cv (compile-corpus ef)]
            (chunk/write-corpus! estore :per-definition cv {:region-threshold threshold})
            (swap! st assoc :hits 0 :misses 0 :effectful 0 :receipts [])
            (let [t0 (now)
                  _ (doseq [d (take eval-slice cv)]
                      (plane/evaluate estore st (:cid d) [3 5]))
                  t1 (now)
                  s @st]
              {:target target :misses (:misses s) :hits (:hits s)
               :effectful (:effectful s) :receipts (count (:receipts s))
               :ms (round (- t1 t0))})))
        warm-leaf (rebuild :leaf)
        warm-hub (rebuild :hub)

        ;; ── how much a CID cache is worth depends on the dependency shape ──
        ;; An edit invalidates every transitive dependent, so the reuse ratio
        ;; is a property of the call graph, not of the edit size. Measured at a
        ;; smaller corpus because the :chain shape makes the real lowering's
        ;; dependency fixed point quadratic.
        shape-sensitivity
        (vec (for [shape [:library :uniform :chain]
                   :let [{sf :forms} (corpus/make {:definitions shape-n :nodes nodes
                                                   :fan-out fan-out :dep-shape shape})
                         c1 (compile-corpus sf)
                         m1 (into {} (map (juxt :name :cid) c1))]
                   n-edited [1 (max 2 (quot shape-n 50)) (max 3 (quot shape-n 10))]
                   target [:leaf :random :hub]]
               (let [{sf2 :forms} (corpus/edit sf n-edited 424242 target)
                     c2 (compile-corpus sf2)
                     m2 (into {} (map (juxt :name :cid) c2))
                     ch (count (for [[n c] m1 :when (not= c (get m2 n))] n))]
                 {:dep-shape shape
                  :edit-target target
                  :definitions shape-n
                  :definitions-edited n-edited
                  :cids-changed ch
                  :invalidation-factor (round (/ ch n-edited))
                  :cid-reuse-ratio (round (- 1 (/ ch (count c1))))})))
        result
        {:corpus (assoc stats :real-kotoba-shape corpus/measured-shape
                        :lowering-ms (round (- t1 t0)))
         :edit {:requested-pct edit-pct
                :definitions-edited (count edited)
                :cids-changed (count changed)
                :cid-reuse-ratio (round (- 1 (/ (count changed) (count compiled))))}
         :dep-shape dep-shape
         :shape-sensitivity shape-sensitivity
         :granularity granularity
         :query {:index (assoc idx-q :index-entries (:entries index))
                 :object-scan scan-q}
         :propagation {:from-hubs prop-hub :from-leaves prop-leaf :seeds 20}
         :namespace {:names (count bindings-v1)
                     :big-edit {:definitions-changed (count changed)
                                :flat-first (- flat-1 flat-before)
                                :flat-second (- flat-2 flat-1)
                                :tree-first tree-1
                                :tree-second (- tree-2 tree-1)}
                     :one-definition-leaf-edit
                     {:flat-first (- flat-s1 0)
                      :flat-second (- flat-s2 flat-s1)
                      :tree-first tree-s1
                      :tree-second (- tree-s2 tree-s1)}}
         :evaluation {:slice eval-slice
                      :cold {:misses (:misses cold) :hits (:hits cold)
                             :effectful (:effectful cold)
                             :ms (round (- th tg))}
                      :warm-after-leaf-edit warm-leaf
                      :warm-after-hub-edit warm-hub}}]

    (println)
    (println "=== block granularity ===")
    (println (str (pad "strategy" 18) (pad "blocks" 10) (pad "blk/def" 10)
                  (pad "bytes" 12) (pad "B/def" 10) (pad "hydrate-blk" 13)
                  (pad "edit-blocks" 13) "edit-bytes"))
    (doseq [g granularity]
      (println (str (pad (name (:strategy g)) 18)
                    (pad (:blocks g) 10) (pad (:blocks-per-definition g) 10)
                    (pad (:bytes g) 12) (pad (:bytes-per-definition g) 10)
                    (pad (:hydrate-blocks-mean g) 13)
                    (pad (:update-blocks-added g) 13)
                    (:update-bytes-added g))))
    (println)
    (println "=== query plane: 'which definitions declare an effect' ===")
    (println "index      " (pr-str idx-q))
    (println "object-scan" (pr-str scan-q))
    (println "propagation from 20 hub definitions  " (pr-str prop-hub))
    (println "propagation from 20 leaf definitions " (pr-str prop-leaf))
    (println)
    (println "=== namespace commit ===")
    (println (str "names=" (count bindings-v1)))
    (println (str "  after " (count changed) " definitions changed: "
                  "flat second commit=" (- flat-2 flat-1) "B"
                  "  prolly-tree second commit=" (- tree-2 tree-1) "B"))
    (println (str "  after 1 leaf definition changed:  "
                  "flat second commit=" (- flat-s2 flat-s1) "B"
                  "  prolly-tree second commit=" (- tree-s2 tree-s1) "B"))
    (println)
    (println "=== evaluation plane ===")
    (println "cold                 " (pr-str (:cold (:evaluation result))))
    (println "warm after leaf edit " (pr-str warm-leaf))
    (println "warm after hub edit  " (pr-str warm-hub))
    (println)
    (println "=== CID cache reuse vs dependency shape ===")
    (println (str (pad "dep-shape" 11) (pad "edited" 9) (pad "lands on" 10)
                  (pad "cids changed" 15) (pad "invalidation" 15) "cache reuse"))
    (doseq [s shape-sensitivity]
      (println (str (pad (name (:dep-shape s)) 11)
                    (pad (:definitions-edited s) 9)
                    (pad (name (:edit-target s)) 10)
                    (pad (str (:cids-changed s) "/" (:definitions s)) 15)
                    (pad (str (:invalidation-factor s) "x") 15)
                    (:cid-reuse-ratio s))))
    (println)
    (println (str "main corpus (" (name dep-shape) ") cid reuse after editing " edit-pct "%: "
                  (:cid-reuse-ratio (:edit result))
                  "  (" (count changed) " of " (count compiled) " definitions changed identity)"))
    (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
    (fs/writeFileSync out-file (with-out-str (prn result)))
    (println)
    (println "wrote" out-file)))

(-main)
