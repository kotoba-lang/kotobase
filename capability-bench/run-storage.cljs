#!/usr/bin/env nbb
;; S3-profile / encryption / identity-scheme / data-lake benchmark.
;;
;;   nbb --classpath "$(nbb setup.cljs --print-classpath)" run-storage.cljs [opts]
;;
;;   --entities N    datom workload size        (default 4000)
;;   --txns N        steady-state transactions  (default 50)
;;   --defs N        corpus for identity schemes(default 300)
;;   --cache N       block cache size           (default 1000)
;;   --out FILE      result EDN                 (default results/storage.edn)

(ns run-storage
  (:require [clojure.string :as str]
            [clojure.set]
            [cbor.core :as cbor]
            [multiformats.core :as mf]
            [prolly-tree.core :as pt]
            [prolly-tree.diff :as ptd]
            [kotobase.capability.workload :as w]
            [kotobase.capability.blockstore :as bs]
            [kotobase.remote.profile :as rp]
            [kotobase.remote.encryption :as enc]
            [kotobase.lake.columnar :as lake]
            [kotobase.identity.schemes :as ident]
            [kotobase.semantic.corpus :as corpus]
            [kotoba.codebase.semantic-code :as sc]
            ["fs" :as fs]
            ["path" :as path]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn arg [k d] (loop [xs argv]
                  (cond (empty? xs) d
                        (= (first xs) (str "--" k)) (or (second xs) d)
                        :else (recur (rest xs)))))
(defn num-arg [k d] (js/parseInt (arg k (str d)) 10))
(defn pad [x n] (let [s (str x)] (str s (str/join (repeat (max 1 (- n (count s))) " ")))))
(defn now [] (js/performance.now))
(defn r2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(def rtts [1 10 50 100])

(defn ports [p] {:put! (:put! p) :get (:get p)})

(defn build-indexes!
  "EAVT + AVET over the load datoms, bulk."
  [p datoms]
  (let [{:keys [put! get]} (ports p)
        eavt (pt/build-tree put! (vec (sort-by first
                                               (map (fn [d] [(w/eavt-key (w/datom-e d) (w/datom-a d) (w/datom-t d))
                                                             {"v" (w/datom-v d)}])
                                                    datoms))))
        avet (pt/build-tree put! (vec (sort-by first
                                               (map (fn [d] [(w/avet-key (w/datom-a d) (w/datom-v d) (w/datom-e d))
                                                             {"t" (w/datom-t d)}])
                                                    datoms))))]
    {:eavt eavt :avet avet}))

(defn measure
  "Run `f`, return [result {:requests-get .. :requests-put .. :ms ..}].
  `hops` is supplied by the caller from the algorithm's dependency structure
  (tree height for a descent), because a block store cannot observe which of
  its requests were dependent on each other."
  [p hops f]
  (let [before (rp/stats p)
        t0 (now)
        v (f)
        t1 (now)]
    (let [after (rp/stats p)]
      [v {:requests-get (- (:requests-get after) (:requests-get before))
          :requests-put (- (:requests-put after) (:requests-put before))
          :bytes-get (- (:bytes-get after) (:bytes-get before))
          :bytes-put (- (:bytes-put after) (:bytes-put before))
          :cache-hits (- (:cache-hits after) (:cache-hits before))
          :decrypt-ms (r2 (- (:decrypt-ms after) (:decrypt-ms before)))
          :encrypt-ms (r2 (- (:encrypt-ms after) (:encrypt-ms before)))
          :hops hops
          :ms (r2 (- t1 t0))}])))

(defn per-op
  "Averages the counters but NOT hops: a hop chain is a property of one
  operation, and dividing it by the number of operations would turn a latency
  bound into nonsense."
  [m n]
  (into {} (for [[k v] m]
             [k (cond (= k :hops) v
                      (number? v) (r2 (/ (double v) n))
                      :else v)])))

(defn -main []
  (let [entities (num-arg "entities" 4000)
        n-txns (num-arg "txns" 50)
        n-defs (num-arg "defs" 300)
        cache-size (num-arg "cache" 1000)
        out-file (arg "out" "results/storage.edn")
        wl (w/make {:entities entities :updates n-txns})
        load-datoms (vec (mapcat :datoms (:load-txns wl)))
        _ (println "workload:" (pr-str (:meta wl)))

        ;; ── 1. object-store profile, no encryption ───────────────────────
        p (rp/make {})
        [{:keys [eavt avet]} build-m] (measure p 1 #(build-indexes! p load-datoms))
        height-eavt (lake/tree-height (:get (ports p)) eavt)
        height-avet (lake/tree-height (:get (ports p)) avet)
        _ (println "index heights: eavt" height-eavt " avet" height-avet)

        point-keys (take 50 (:point (:queries wl)))
        _ (rp/reset-stats! p)
        [_ point-m] (measure p height-eavt
                             #(doseq [e point-keys]
                                (pt/scan-prefix (:get (ports p)) eavt (w/eavt-entity-prefix e))))
        value-qs (take 20 (:by-value (:queries wl)))
        _ (rp/reset-stats! p)
        [_ value-m] (measure p height-avet
                             #(doseq [[a v] value-qs]
                                (pt/scan-prefix (:get (ports p)) avet (w/avet-value-prefix a v))))
        range-qs (take 10 (:range (:queries wl)))
        _ (rp/reset-stats! p)
        [_ range-m] (measure p height-avet
                             #(doseq [[a _ _] range-qs]
                                (pt/scan-prefix (:get (ports p)) avet (w/avet-attr-prefix a))))
        ;; steady-state transaction: read index paths, rewrite them, commit, CAS
        _ (rp/reset-stats! p)
        [roots txn-m]
        (measure p 5
                 #(reduce (fn [{:keys [eavt avet]} txn]
                            (let [{:keys [put! get]} (ports p)
                                  e' (pt/insert-many put! get eavt
                                                     (mapv (fn [d] [(w/eavt-key (w/datom-e d) (w/datom-a d) (w/datom-t d))
                                                                    {"v" (w/datom-v d)}])
                                                           (:datoms txn)))
                                  a' (pt/insert-many put! get avet
                                                     (mapv (fn [d] [(w/avet-key (w/datom-a d) (w/datom-v d) (w/datom-e d))
                                                                    {"t" (w/datom-t d)}])
                                                           (:datoms txn)))]
                              {:eavt e' :avet a'}))
                          {:eavt eavt :avet avet}
                          (:update-txns wl)))
        _ (rp/reset-stats! p)
        [sync-r sync-m] (measure p (max height-eavt height-avet)
                                 #(ptd/diff* (:get (ports p)) eavt (:eavt roots)))

        ;; cached profile: same point reads
        pc (rp/make {:cache-size cache-size})
        {ce :eavt} (build-indexes! pc load-datoms)
        _ (rp/reset-stats! pc)
        [_ point-cached-m] (measure pc height-eavt
                                    #(doseq [e point-keys]
                                       (pt/scan-prefix (:get (ports pc)) ce (w/eavt-entity-prefix e))))

        ;; ── 2. client-side encryption ────────────────────────────────────
        pe (rp/make {:encryption :convergent})
        [{ee :eavt ae :avet} enc-build-m] (measure pe 1 #(build-indexes! pe load-datoms))
        _ (rp/reset-stats! pe)
        [_ enc-point-m] (measure pe height-eavt
                                 #(doseq [e point-keys]
                                    (pt/scan-prefix (:get (ports pe)) ee (w/eavt-entity-prefix e))))
        block-set (vec (vals (:blocks @(:store p))))
        enc-analysis (enc/analyse (vec (take 3000 block-set)))
        ;; What convergent encryption actually leaks is cross-dataset equality:
        ;; two tenants storing overlapping data produce identical ciphertexts,
        ;; and the server can see it. Measured by building a second tenant that
        ;; shares half its values and counting shared block addresses.
        pt2 (rp/make {})
        wl2 (w/make {:entities entities :updates 0 :seed 20260807})
        _ (build-indexes! pt2 (vec (mapcat :datoms (:load-txns wl2))))
        tenant-a (set (keys (:blocks @(:store p))))
        tenant-b (set (keys (:blocks @(:store pt2))))
        leakage {:tenant-a-blocks (count tenant-a)
                 :tenant-b-blocks (count tenant-b)
                 :shared-block-addresses (count (clojure.set/intersection tenant-a tenant-b))
                 :shared-pct (r2 (* 100.0 (/ (double (count (clojure.set/intersection tenant-a tenant-b)))
                                             (max 1 (count tenant-a)))))
                 :note "with convergent encryption these are byte-identical objects; the server learns the overlap"}
        ;; an encrypted value index cannot be prefix-scanned by value: the
        ;; client must take the whole attribute range and filter locally
        _ (rp/reset-stats! p)
        [plain-hits plain-m] (measure p height-avet
                                      #(pt/scan-prefix (:get (ports p)) avet
                                                       (w/avet-value-prefix (first (first value-qs))
                                                                            (second (first value-qs)))))
        _ (rp/reset-stats! p)
        [opaque-hits opaque-m] (measure p height-avet
                                        #(pt/scan-prefix (:get (ports p)) avet
                                                         (w/avet-attr-prefix (first (first value-qs)))))

        ;; ── 3. identity schemes ──────────────────────────────────────────
        {ident-forms :forms} (corpus/make {:definitions n-defs :nodes 40 :fan-out 8})
        def-blocks (vec (map (fn [[_ d]] (cbor/encode (:block d)))
                             (:definitions (sc/compile-definitions ident-forms))))
        enc-analysis-defs (enc/analyse def-blocks)
        base (ident/scheme-cids ident-forms)
        reformatted (ident/reformat ident-forms)
        renamed-locals (ident/scheme-cids (ident/rename-locals ident-forms))
        renamed-defs (ident/scheme-cids (ident/rename-definitions ident-forms))
        semantically (ident/scheme-cids (ident/semantic-change ident-forms
                                                              (max 1 (quot n-defs 10))))
        semantic-leaf (ident/scheme-cids (ident/semantic-change-leaf ident-forms 1))
        pct (fn [n] (r2 (* 100.0 (/ (double n) n-defs))))
        identity-table
        [{:perturbation :reformat
          :source-text (pct (ident/changed (:source-text base) reformatted))
          :sexpr-canonical 0.0
          :checked-kir 0.0
          :note "whitespace only; the other two never see the text"}
         {:perturbation :rename-locals
          :source-text (pct (ident/changed (:source-text base) (:source-text renamed-locals)))
          :sexpr-canonical (pct (ident/changed (:sexpr-canonical base) (:sexpr-canonical renamed-locals)))
          :checked-kir (pct (ident/changed (:checked-kir base) (:checked-kir renamed-locals)))}
         {:perturbation :rename-definitions
          :source-text (pct (ident/changed (:source-text base) (:source-text renamed-defs)))
          :sexpr-canonical (pct (ident/changed (:sexpr-canonical base) (:sexpr-canonical renamed-defs)))
          :checked-kir (pct (ident/changed-set (:checked-kir base) (:checked-kir renamed-defs)))
          :note "names changed on purpose, so the CID SET is compared, not the map"}
         {:perturbation :semantic-change-1-leaf
          :source-text (pct (ident/changed (:source-text base) (:source-text semantic-leaf)))
          :sexpr-canonical (pct (ident/changed (:sexpr-canonical base) (:sexpr-canonical semantic-leaf)))
          :checked-kir (pct (ident/changed (:checked-kir base) (:checked-kir semantic-leaf)))}
         {:perturbation :semantic-change-10pct-hubs
          :source-text (pct (ident/changed (:source-text base) (:source-text semantically)))
          :sexpr-canonical (pct (ident/changed (:sexpr-canonical base) (:sexpr-canonical semantically)))
          :checked-kir (pct (ident/changed (:checked-kir base) (:checked-kir semantically)))}]
        ;; rename-definitions under checked-kir: compare the SET of cids, since
        ;; the names changed on purpose
        kir-rename-stable? (= (set (vals (:checked-kir base)))
                              (set (vals (:checked-kir renamed-defs))))
        sexpr-rename-stable? (= (set (vals (:sexpr-canonical base)))
                                (set (vals (:sexpr-canonical renamed-defs))))
        scheme-sizes
        (let [enc-size (fn [f] (count (cbor/encode f)))]
          {:source-text-bytes-per-def
           (r2 (/ (reduce + (map #(count (ident/source-text % false)) ident-forms))
                  (double n-defs)))})

        ;; ── 4. data lake ─────────────────────────────────────────────────
        pl (rp/make {})
        [lk lake-build-m] (measure pl 1 #(lake/build! (ports pl) load-datoms {:rows-per-chunk 512}))
        _ (rp/reset-stats! pl)
        [agg lake-m] (measure pl 2 #(lake/range-aggregate (ports pl) lk :person/score 400 450))
        _ (rp/reset-stats! p)
        [iagg idx-m] (measure p height-avet
                              #(lake/index-aggregate (:get (ports p)) avet :person/score 400 450
                                                     height-avet))

        ;; ── 5. security cost ─────────────────────────────────────────────
        verify-sample (vec (take 2000 block-set))
        t-h0 (now)
        _ (doseq [b verify-sample] (rp/sha256 b))
        t-h1 (now)
        t-v0 (now)
        _ (doseq [b verify-sample] (mf/cidv1-dag-cbor b))
        t-v1 (now)
        hash-ms-per-block (/ (- t-h1 t-h0) (count verify-sample))
        tampered (let [b (first block-set)
                       copy (js/Buffer.from b)]
                   (aset copy 5 (bit-xor 0xff (aget copy 5)))
                   {:original (mf/cidv1-dag-cbor b)
                    :tampered (mf/cidv1-dag-cbor copy)})
        verify-ms-per-block (/ (- t-v1 t-v0) (count verify-sample))

        result
        {:workload (:meta wl)
         :index-height {:eavt height-eavt :avet height-avet}
         :s3-profile
         {:bulk-load build-m
          :point-read (assoc (per-op point-m (count point-keys)) :ops (count point-keys))
          :find-by-value (assoc (per-op value-m (count value-qs)) :ops (count value-qs))
          :range-scan (assoc (per-op range-m (count range-qs)) :ops (count range-qs))
          :transaction (assoc (per-op txn-m n-txns) :ops n-txns)
          :replica-sync (assoc sync-m :entries (+ (count (:added sync-r))
                                                  (count (:changed sync-r))))
          :point-read-cached (assoc (per-op point-cached-m (count point-keys))
                                    :cache-size cache-size)}
         :latency
         (into {} (for [rtt rtts]
                    [rtt {:point-read (rp/latency {:requests (/ (:requests-get point-m)
                                                                (count point-keys))
                                                   :hops height-eavt} rtt)
                          :transaction (rp/latency {:requests (/ (+ (:requests-get txn-m)
                                                                    (:requests-put txn-m))
                                                                 n-txns)
                                                    :hops 5} rtt)
                          :range-scan (rp/latency {:requests (/ (:requests-get range-m)
                                                                (count range-qs))
                                                   :hops height-avet} rtt)}]))
         :encryption
         {:build enc-build-m
          :point-read (per-op enc-point-m (count point-keys))
          :analysis-index-blocks (assoc enc-analysis
                                        :mean-block-bytes
                                        (r2 (/ (:plaintext-bytes enc-analysis)
                                               (double (:blocks enc-analysis)))))
          :analysis-definition-blocks (assoc enc-analysis-defs
                                             :mean-block-bytes
                                             (r2 (/ (:plaintext-bytes enc-analysis-defs)
                                                    (double (:blocks enc-analysis-defs)))))
          :cross-tenant-equality leakage
          :opaque-index-amplification
          {:plaintext-prefix {:requests (:requests-get plain-m)
                              :rows (count plain-hits)}
           :attribute-range {:requests (:requests-get opaque-m)
                             :rows (count opaque-hits)}
           :request-factor (r2 (/ (double (:requests-get opaque-m))
                                  (max 1 (:requests-get plain-m))))
           :row-factor (r2 (/ (double (count opaque-hits))
                              (max 1 (count plain-hits))))}}
         :identity
         {:definitions n-defs
          :table identity-table
          :checked-kir-stable-under-rename kir-rename-stable?
          :sexpr-stable-under-rename sexpr-rename-stable?
          :sizes scheme-sizes}
         :lake
         {:build lake-build-m
          :columns (into {} (map (fn [[a c]] [a (dissoc c :footer)])) (:columns lk))
          :columnar (merge agg lake-m)
          :avet-index (merge iagg idx-m)}
         :security
         {:sha256-ms-per-block (r2 hash-ms-per-block)
          :verify-ms-per-block (r2 verify-ms-per-block)
          :verify-blocks-sampled (count verify-sample)
          :tamper-detected? (not= (:original tampered) (:tampered tampered))}}]

    (println)
    (println "=== 1. object store (S3 profile): requests and hops ===")
    (println (str (pad "operation" 22) (pad "GET req" 10) (pad "PUT req" 10)
                  (pad "bytes-get" 12) (pad "hops" 7) "note"))
    (doseq [[k m note] [[:bulk-load build-m "whole corpus"]
                        [:point-read (per-op point-m (count point-keys)) "per op"]
                        [:find-by-value (per-op value-m (count value-qs)) "per op"]
                        [:range-scan (per-op range-m (count range-qs)) "per op"]
                        [:transaction (per-op txn-m n-txns) "per txn"]
                        [:replica-sync sync-m "whole diff"]
                        [:point-read-cached (per-op point-cached-m (count point-keys))
                         (str "cache=" cache-size)]]]
      (println (str (pad (name k) 22) (pad (:requests-get m) 10) (pad (:requests-put m) 10)
                    (pad (:bytes-get m) 12) (pad (:hops m) 7) note)))
    (println)
    (println "=== 2. modelled latency (serial = requests x RTT, pipelined = hops x RTT) ===")
    (println (str (pad "RTT" 8) (pad "point-read" 26) (pad "transaction" 26) "range-scan"))
    (doseq [rtt rtts]
      (let [l (get (:latency result) rtt)
            fmt (fn [x] (str (r2 (:serial-ms x)) " / " (r2 (:pipelined-ms x)) " ms"))]
        (println (str (pad (str rtt "ms") 8) (pad (fmt (:point-read l)) 26)
                      (pad (fmt (:transaction l)) 26) (fmt (:range-scan l))))))
    (println)
    (println "=== 3. client-side encryption (AES-256-GCM) ===")
    (println (str (pad "block population" 24) (pad "mean bytes" 13) (pad "overhead/blk" 14)
                  (pad "overhead %" 13) (pad "encrypt ms" 13) "decrypt ms"))
    (doseq [[label a] [["index blocks" enc-analysis] ["definition blocks" enc-analysis-defs]]]
      (println (str (pad label 24)
                    (pad (r2 (/ (:plaintext-bytes a) (double (:blocks a)))) 13)
                    (pad (:overhead-per-block a) 14)
                    (pad (r2 (:overhead-pct a)) 13)
                    (pad (r2 (:encrypt-ms-per-block a)) 13)
                    (r2 (:decrypt-ms-per-block a)))))
    (println "throughput MB/s:" (r2 (:encrypt-throughput-mb-s enc-analysis)))
    (println "content addressing:"
             (pr-str (select-keys enc-analysis [:blocks
                                                :convergent-unique-after-two-builds
                                                :random-unique-after-two-builds
                                                :random-duplicate-factor])))
    (println "equality leakage:" (pr-str leakage))
    (println "opaque index amplification:"
             (pr-str (:opaque-index-amplification (:encryption result))))
    (println "point read with encryption:" (pr-str (per-op enc-point-m (count point-keys))))
    (println)
    (println "=== 4. CID identity schemes: % of definitions whose CID changes ===")
    (println (str (pad "perturbation" 24) (pad "source-text" 14)
                  (pad "sexpr-canonical" 18) "checked-KIR"))
    (doseq [row identity-table]
      (println (str (pad (name (:perturbation row)) 24)
                    (pad (:source-text row) 14)
                    (pad (:sexpr-canonical row) 18)
                    (:checked-kir row))))
    (println (str "definition rename, CID SET preserved?  sexpr-canonical="
                  sexpr-rename-stable? "  checked-KIR=" kir-rename-stable?))
    (println)
    (println "=== 5. data lake: range aggregate over one column ===")
    (println "columnar  " (pr-str (select-keys (merge agg lake-m)
                                               [:chunks-total :chunks-read :chunks-skipped
                                                :rows-scanned :matches :requests-get :hops :ms])))
    (println "avet index" (pr-str (select-keys (merge iagg idx-m)
                                               [:rows-scanned :matches :requests-get :hops :ms])))
    (println "lake build" (pr-str (select-keys lake-build-m [:requests-put :bytes-put :ms])))
    (println)
    (println "=== 6. security cost ===")
    (println (str "sha256 " (r2 hash-ms-per-block) " ms/block; full CID assembly "
                  (r2 verify-ms-per-block) " ms/block (" (count verify-sample)
                  " blocks) — the gap is base32 multibase encoding in pure cljs, not hashing"))
    (println (str "single-byte tamper detected: " (:tamper-detected? (:security result))))
    (println (str "cross-tenant equality visible to a convergent-encryption server: "
                  (:shared-pct leakage) "% of tenant A's blocks"))

    (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
    (fs/writeFileSync out-file (with-out-str (prn result)))
    (println)
    (println "wrote" out-file)))

(-main)
