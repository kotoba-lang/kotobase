(ns kotobase.semantic.chunk
  "The three block-granularity strategies, applied to the *same* canonical IR.

  Identity is not a variable here. Every strategy chunks the normalized,
  alpha-converted, name-free IR that `kotoba.codebase.semantic-code` actually
  produces — de Bruijn locals, intrinsics by stable id, dependencies as tag-42
  IPLD links. What differs is only where the block boundaries fall, which is
  the question the design note leaves open:

  - `:per-node`         every IR node is its own block (proposal A)
  - `:per-definition`   one block per definition (proposal B — what the
                        shipped `semantic-code` does today)
  - `:semantic-chunk`   root + dependency vector + constant pool + body
                        regions above a size threshold (proposal C)

  Because the store deduplicates by CID, structural sharing is measured rather
  than asserted — `:per-node` really does collapse every `{op: local, index:
  0}` in the corpus into one block, and the numbers say what that is worth."
  (:require [cbor.core :as cbor]
            [multiformats.core :as mf]
            [ipld.link :as link]
            [kotoba.codebase.semantic-code :as sc]
            [kotobase.capability.blockstore :as bs]))

(def strategies [:per-node :per-definition :semantic-chunk])

(defn put-block!
  "Encode canonically, address it, store it. Returns the CID."
  [store node]
  (let [bytes (cbor/encode node)
        cid (mf/cidv1-dag-cbor bytes)]
    (bs/put! store cid bytes)
    cid))

(defn- expr? [x] (and (map? x) (contains? x "op")))

(defn- encoded-size [x] #?(:cljs (.-length (cbor/encode x))
                           :clj (alength ^bytes (cbor/encode x))))

;; ── A: every node ──────────────────────────────────────────────────────────

(defn- split-every-node
  "Replace each expression node with a link to its own block, bottom-up."
  [store x]
  (cond
    (expr? x) (let [inner (into {} (map (fn [[k v]] [k (split-every-node store v)])) x)]
                (sc/cid-link (put-block! store inner)))
    (map? x) (into {} (map (fn [[k v]] [k (split-every-node store v)])) x)
    (vector? x) (mapv #(split-every-node store %) x)
    :else x))

;; ── C: semantic chunks ─────────────────────────────────────────────────────

(defn- hoist-constants
  "Pull every literal into a pool. Returns [ir pool]. Literals are the part of
  a definition most likely to be shared across definitions *and* the part most
  likely to be the thing an edit changes, so giving them their own block is
  the cheapest structural sharing available."
  [x]
  (let [pool (atom [])
        index (atom {})
        walk (fn walk [x]
               (cond
                 (and (expr? x) (= "literal" (get x "op")))
                 (let [v (get x "value")
                       i (or (get @index v)
                             (let [i (count @pool)]
                               (swap! pool conj v)
                               (swap! index assoc v i)
                               i))]
                   {"op" "pool" "index" i})

                 (map? x) (into {} (map (fn [[k v]] [k (walk v)])) x)
                 (vector? x) (mapv walk x)
                 :else x))
        ir (walk x)]
    [ir @pool]))

(defn- split-regions
  "Emit any subtree at or above `threshold` encoded bytes as its own region
  block, and stop descending into it. Small definitions stay a single block;
  large ones split along their own structure."
  [store x threshold]
  (letfn [(walk [x top?]
            (cond
              (expr? x)
              (if (and (not top?) (>= (encoded-size x) threshold))
                (sc/cid-link (put-block! store (walk-children x)))
                (walk-children x))
              (map? x) (into {} (map (fn [[k v]] [k (walk v false)])) x)
              (vector? x) (mapv #(walk % false) x)
              :else x))
          (walk-children [x]
            (into {} (map (fn [[k v]] [k (walk v false)])) x))]
    (walk x true)))

;; ── write ──────────────────────────────────────────────────────────────────

(defn write-definition!
  "Store one compiled definition under `strategy`; returns the root CID.

  The type block is stored by every strategy, so the comparison isolates IR
  chunking rather than accidentally measuring whether types are separate (in
  the real lowering they always are, and they deduplicate heavily across
  definitions with the same signature)."
  [store strategy {:keys [block type-block]} {:keys [region-threshold] :or {region-threshold 256}}]
  (when type-block (put-block! store type-block))
  (case strategy
    :per-definition
    (put-block! store block)

    :per-node
    (put-block! store (assoc block "ir" (split-every-node store (get block "ir"))))

    :semantic-chunk
    (let [[ir pool] (hoist-constants (get block "ir"))
          pool-cid (put-block! store {"schema" "kotoba.constant-pool.v1"
                                      "constants" pool})
          deps-cid (put-block! store {"schema" "kotoba.dependency-vector.v1"
                                      "dependencies" (get block "dependencies")})
          ir' (split-regions store ir region-threshold)]
      (put-block! store (assoc block
                               "ir" ir'
                               "constants" (sc/cid-link pool-cid)
                               "dependencies" (sc/cid-link deps-cid))))))

(defn write-corpus!
  "Store every compiled definition and return per-strategy accounting."
  [store strategy compiled opts]
  (let [before (bs/stats store)
        blocks-before (bs/block-count store)
        roots (mapv (fn [d] (write-definition! store strategy d opts))
                    compiled)
        after (bs/stats store)]
    {:strategy strategy
     :roots roots
     :definitions (count compiled)
     :blocks-added (- (bs/block-count store) blocks-before)
     :counters (bs/delta before after)}))

;; ── read ───────────────────────────────────────────────────────────────────

(defn- link? [x] (and (cbor/tagged? x) (= 42 (cbor/tag-number x))))

(defn- link->cid [x] (link/link-cid (link/tag->link x)))

(defn hydrate
  "Reconstruct a definition's full IR from its root CID, following whatever
  links the strategy introduced. Returns [ir blocks-fetched].

  `dependency` links are NOT followed: hydrating a definition for execution
  needs its own body, not its transitive closure. Following them would
  measure closure transfer, which is a different question."
  [store root-cid]
  (let [fetched (atom 0)
        get-block (fn [cid]
                    (swap! fetched inc)
                    (cbor/decode (bs/get store cid)))
        ;; Follow only the links a chunking strategy introduced inside the
        ;; definition's own body. A `reference` node's "cid" is a dependency
        ;; on ANOTHER definition — following it would hydrate the transitive
        ;; closure and measure a different question. "type", "profile",
        ;; "hashContract" and "dependencies" are likewise left as links by
        ;; every strategy, so no strategy is charged for them.
        expand (fn expand [x follow?]
                 (cond
                   (link? x) (if follow? (expand (get-block (link->cid x)) true) x)
                   (map? x) (into {} (map (fn [[k v]]
                                            [k (expand v (cond
                                                           (= k "cid") false
                                                           (#{"ir" "constants"} k) true
                                                           :else follow?))]))
                                  x)
                   (vector? x) (mapv #(expand % follow?) x)
                   :else x))
        root (get-block root-cid)]
    [(expand root false) @fetched]))
