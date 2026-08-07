#!/usr/bin/env nbb
;; Lake chunk sizing sweep: what does rows-per-chunk actually buy on an
;; object-store profile, and where does the padding ladder cut it off?
;;
;;   nbb --classpath "$(nbb setup.cljs --print-classpath)" run-lake-chunk.cljs [opts]
;;
;;   --entities N   datom workload size  (default 4000)
;;   --out FILE     result EDN           (default results/lake-chunk.edn)
;;
;; ## The question
;;
;; kotobase's block size ladder (`kotobase-peer.block-sizing`, 16-128 KiB) was
;; chosen for **Merkle run sync**: small blocks mean fine-grained structural
;; diff, so a replica refetches less. The columnar lake wants the opposite —
;; analytics engines (Parquet, ORC) use row groups measured in **hundreds of
;; MB**, because on an object store the cost that dominates is the *request*,
;; not the bytes, and a zone-map skip only pays if the chunk it skips is big.
;;
;; Those are different objects with different optimal sizes. This measures the
;; curve instead of assuming either side is right.
;;
;; ## What is measured, and what is not
;;
;; Measured: requests, bytes read, chunks read vs skipped, rows scanned, and
;; the actual encoded chunk size, over the real DAG-CBOR block set.
;;
;; NOT measured: wall-clock against a real object store. There is no network.
;; Request counts and byte counts are architecture; latency is someone's
;; deployment. Multiply requests by your own RTT.
;;
;; The `hops` for a columnar read is 2 at every chunk size (footer, then the
;; surviving chunks in parallel) — that is the point of the layout, and it is
;; why the interesting variable is bytes-per-request, not round trips.

(ns run-lake-chunk
  (:require [clojure.string :as str]
            [cbor.core :as cbor]
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

;; pqh's ISO/IEC 7816-4 bucket ladder (kotoba.lang.pqh.crypto/PAD-BUCKETS) and
;; kotobase-peer's Merkle block classes, quoted so the cutoff is visible in the
;; output rather than argued about.
(def pad-buckets [1024 4096 16384 65536])
(def merkle-classes [16384 32768 65536 131072])

(defn pick-bucket [n]
  (let [need (+ n 1 16)] (first (filter #(<= need %) pad-buckets))))

(defn -main []
  (let [entities (num-arg "entities" 4000)
        out (arg "out" "results/lake-chunk.edn")
        wl (w/make {:entities entities :updates 0})
        datoms (vec (mapcat :datoms (:load-txns wl)))
        ;; pick the attribute with the most rows — the one a lake query hurts on
        [attr attr-rows] (->> datoms (group-by w/datom-a)
                              (sort-by (comp - count second)) first
                              ((juxt first (comp count second))))
        ;; RAW values, ordered by their v-key. `range-aggregate` applies
        ;; `->v-key` itself, so passing v-keys here would encode twice and
        ;; match nothing (measured: chunks-read 0 at every size).
        vals-sorted (vec (sort-by w/->v-key
                                  (map w/datom-v
                                       (filter #(= attr (w/datom-a %)) datoms))))
        ;; a 10% selectivity range on that attribute
        lo (nth vals-sorted (quot (count vals-sorted) 2))
        hi (nth vals-sorted (min (dec (count vals-sorted))
                                 (+ (quot (count vals-sorted) 2)
                                    (quot (count vals-sorted) 10))))
        sizes [64 256 512 1024 2048 4096 8192 16384 32768]
        rows
        (for [rpc sizes]
          (let [p (rp/make {})
                lk (lake/build! (ports p) datoms {:rows-per-chunk rpc})
                build (rp/stats p)
                _ (rp/reset-stats! p)
                q (lake/range-aggregate (ports p) lk attr lo hi)
                qs (rp/stats p)
                ;; actual encoded size of one chunk of this column
                footer (cbor/decode ((:get (ports p)) (get-in lk [:columns attr :footer])))
                chunk-cids (map #(get % "cid") (get footer "chunks"))
                chunk-bytes (map #(.-length (cbor/encode (cbor/decode ((:get (ports p)) %))))
                                 (take 5 chunk-cids))
                mean-chunk (if (seq chunk-bytes)
                             (/ (reduce + chunk-bytes) (count chunk-bytes)) 0)]
            {:rows-per-chunk rpc
             :chunks-total (:chunks-total q)
             :chunks-read (:chunks-read q)
             :chunks-skipped (:chunks-skipped q)
             :rows-scanned (:rows-scanned q)
             :matches (:matches q)
             :requests-get (:requests-get qs)
             :bytes-get (:bytes-get qs)
             :mean-chunk-bytes (Math/round mean-chunk)
             :pad-bucket (pick-bucket mean-chunk)
             :build-puts (:requests-put build)
             :build-bytes (:bytes-put build)}))]
    (println "=== lake chunk sizing sweep ===")
    (println (str "workload: " entities " entities / " (count datoms) " datoms; "
                  "widest attribute " attr " with " attr-rows " rows"))
    (println (str "query: 10% selectivity range aggregate. hops = 2 at every size "
                  "(footer, then surviving chunks in parallel)."))
    (println "No network: multiply requests by your own RTT.")
    (println)
    (println (str (pad "rows/chunk" 12) (lpad "chunks" 8) (lpad "read" 6) (lpad "skip" 6)
                  (lpad "rows-scan" 11) (lpad "match" 7) (lpad "GET" 6) (lpad "bytes-get" 11)
                  (lpad "chunk B" 10) (lpad "pad→" 9)))
    (doseq [r rows]
      (println (str (pad (:rows-per-chunk r) 12)
                    (lpad (:chunks-total r) 8) (lpad (:chunks-read r) 6)
                    (lpad (:chunks-skipped r) 6)
                    (lpad (:rows-scanned r) 11) (lpad (:matches r) 7) (lpad (:requests-get r) 6)
                    (lpad (:bytes-get r) 11)
                    (lpad (:mean-chunk-bytes r) 10)
                    (lpad (or (:pad-bucket r) "BLOB") 9))))
    (println)
    (println (str "pqh PAD-BUCKETS = " pad-buckets
                  "  (largest " (last pad-buckets) " B; above it → ciphertextBlob)"))
    (println (str "kotobase-peer size-classes = " merkle-classes
                  "  (Merkle sync granularity, NOT lake granularity)"))
    (println)
    (println "Parquet/ORC row groups are conventionally 128 MB-1 GB. Every row")
    (println "here is orders of magnitude below that: the ceiling is not the lake")
    (println "code, it is that a padded envelope must fit an inline bucket.")
    (.writeFileSync fs out (pr-str {:bench :lake-chunk
                                    :workload {:entities entities :datoms (count datoms)
                                               :attribute (str attr) :attr-rows attr-rows}
                                    :pad-buckets pad-buckets
                                    :merkle-classes merkle-classes
                                    :note (str "requests/bytes are architecture; latency is "
                                               "deployment. No network. hops=2 at every size.")
                                    :rows (vec rows)}))
    (println)
    (println (str "wrote " out))))

(-main)
