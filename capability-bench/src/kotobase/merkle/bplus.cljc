(ns kotobase.merkle.bplus
  "Content-addressed B+ tree over the same IPLD node shape as `prolly-tree`.

  Leaves and internals are DAG-CBOR with tag-42 child links, so `prolly-tree`
  lookup, scan-prefix, and structural diff work unchanged. The only difference
  is how a node is cut:

    Prolly — chunk boundary when sha256(key-or-child-cid) is 0 mod 256
    B+     — occupancy: a node holds at most `max-occupancy` entries, split
             at the midpoint (or packed left-to-right on bulk load)

  That is the CID-world claim this exists to measure. An insert that fills a
  leaf re-packs that leaf into two ~half-full siblings, so keys that did not
  change can still move to a new block CID. Prolly's content-defined cut
  keeps an unchanged key in a block whose CID is unchanged."
  (:require [ipld.core :as ipld]))

(def max-occupancy
  "Match prolly-tree's average chunk (~1/256). Same node size, different cut."
  256)

(def min-occupancy
  "Classic B+ underflow threshold. A leaf below this merges with its
  successor so occupancy split is visible on delete, not only on insert."
  128)

(defn- put-node! [put! node] (ipld/put-node! put! node))

(defn- verified-node [expected-cid bytes]
  (let [actual (ipld/cid bytes)]
    (when-not (= expected-cid actual)
      (throw (ex-info "merkle-bplus: block CID mismatch"
                      {:type :ipld/cid-mismatch :expected-cid expected-cid
                       :actual-cid actual})))
    (ipld/decode bytes)))

(defn- get-node [get-fn cid]
  (verified-node cid (get-fn cid)))

(defn- child-cid [[_ link]] (ipld/link-cid link))

(defn- node-entries [node] (mapv vec (get node "entries")))

(defn- node-children [node]
  (mapv (fn [e] [(first e) (child-cid e)]) (get node "children")))

(defn- leaf-node? [node] (= "leaf" (get node "kind")))

(defn- upsert-sorted [entries k v]
  (let [i (count (take-while #(neg? (compare (first %) k)) entries))
        hit? (and (< i (count entries)) (= k (first (nth entries i))))]
    (if hit?
      (assoc entries i [k v])
      (vec (concat (subvec entries 0 i) [[k v]] (subvec entries i))))))

(defn- child-index [children k]
  (or (first (keep-indexed (fn [i [max-key _]]
                             (when (<= (compare k max-key) 0) i))
                           children))
      (dec (count children))))

(defn- splice [v i n replacement]
  (vec (concat (subvec v 0 i) replacement (subvec v (min (count v) (+ i n))))))

(defn- pack
  "Left-packed occupancy chunks, last chunk may be short. Bulk load only."
  [items]
  (vec (partition-all max-occupancy items)))

(defn- split-occupancy
  "Midpoint split when a node overflows. `partition-all` would turn 257
  entries into [256][1] and fragment every prefix that used to fit in one
  leaf; a B+ overflow is two ~half-full siblings."
  [items]
  (let [items (vec items)
        n (count items)]
    (cond
      (zero? n) []
      (<= n max-occupancy) [items]
      :else
      (let [mid (quot n 2)]
        (vec (concat (split-occupancy (subvec items 0 mid))
                     (split-occupancy (subvec items mid))))))))

(defn- put-leaf [put! entries]
  (let [entries (vec entries)
        cid (put-node! put! {"kind" "leaf" "entries" (mapv vec entries)})]
    [(first (last entries)) cid]))

(defn- put-internal [put! children]
  (let [children (vec children)
        cid (put-node! put!
                       {"kind" "internal"
                        "children" (mapv (fn [[mk c]] [mk (ipld/link c)])
                                         children)})]
    [(first (last children)) cid]))

(defn- pack-leaves [put! entries]
  (if (empty? entries)
    []
    (mapv #(put-leaf put! %) (pack entries))))

(defn- split-leaves [put! entries]
  (if (empty? entries)
    []
    (mapv #(put-leaf put! %) (split-occupancy entries))))

(defn- pack-internal-level [put! children]
  (mapv #(put-internal put! %) (pack children)))

(defn build-tree
  "Bulk-load sorted `[k v]` with left-packed occupancy. Empty → nil."
  [put! sorted-entries]
  (when (seq sorted-entries)
    (loop [level (pack-leaves put! (vec sorted-entries))]
      (if (= 1 (count level))
        (second (first level))
        (recur (pack-internal-level put! level))))))

(defn- tree-height [get-fn root-cid]
  (loop [cid root-cid height 0]
    (let [node (get-node get-fn cid)]
      (if (leaf-node? node)
        height
        (recur (second (first (node-children node))) (inc height))))))

(defn- leaf-summaries-at-height [get-fn cid height]
  (let [node (get-node get-fn cid)]
    (cond
      (zero? height) [[(first (last (node-entries node))) cid]]
      (= 1 height) (node-children node)
      :else (into [] (mapcat (fn [[_ child]]
                               (leaf-summaries-at-height get-fn child (dec height))))
                  (node-children node)))))

(defn- leaf-summaries [get-fn root-cid]
  (leaf-summaries-at-height get-fn root-cid (tree-height get-fn root-cid)))

(defn- rebalance-level [put! level]
  (cond
    (empty? level) nil
    (= 1 (count level)) (second (first level))
    :else (recur put! (pack-internal-level put! level))))

(defn insert-many
  "Insert/replace `pairs` under `root-cid`. Affected leaves are occupancy-
  packed (not content-hashed). Internal levels packed once."
  [put! get-fn root-cid pairs]
  (let [pairs (vec pairs)]
    (cond
      (empty? pairs) root-cid
      (nil? root-cid) (build-tree put! (->> pairs
                                            (reduce (fn [m [k v]] (assoc m k v)) {})
                                            (sort-by first)
                                            vec))
      :else
      (let [leaves (leaf-summaries get-fn root-cid)
            by-leaf (reduce (fn [acc [k v]]
                              (update acc (child-index leaves k) (fnil conj []) [k v]))
                            {}
                            pairs)
            level (reduce
                   (fn [level [i additions]]
                     (let [leaf (get-node get-fn (second (nth level i)))
                           entries (reduce (fn [es [k v]] (upsert-sorted es k v))
                                           (node-entries leaf)
                                           additions)]
                       (splice level i 1 (split-leaves put! entries))))
                   leaves
                   (sort-by first > by-leaf))]
        (rebalance-level put! level)))))

(defn- mutation-windows
  "Leaves touched by additions or removals. Removals include the successor
  so an underflow can merge, matching B+ sibling merge."
  [leaves additions removals]
  (let [last-i (dec (count leaves))
        add-i (map #(child-index leaves (first %)) additions)
        rem-i (mapcat (fn [k]
                        (let [i (child-index leaves k)]
                          (if (< i last-i) [i (inc i)] [i])))
                      removals)
        affected (sort (set (concat add-i rem-i)))]
    (reduce (fn [ranges i]
              (if (and (seq ranges) (= i (inc (second (peek ranges)))))
                (conj (pop ranges) [(first (peek ranges)) i])
                (conj ranges [i i])))
            []
            affected)))

(defn mutate-many
  "Apply `additions` and `removals` in one occupancy-packed leaf pass."
  [put! get-fn root-cid additions removals]
  (let [additions (vec additions)
        removals (vec (distinct removals))]
    (cond
      (nil? root-cid) (insert-many put! get-fn nil additions)
      (and (empty? additions) (empty? removals)) root-cid
      :else
      (let [leaves (leaf-summaries get-fn root-cid)
            windows (mutation-windows leaves additions removals)
            rem-set (set removals)
            add-by-i (reduce (fn [acc [k v :as pair]]
                               (update acc (child-index leaves k) (fnil conj []) pair))
                             {}
                             additions)
            level (reduce
                   (fn [level [start end]]
                     (let [retained (->> (subvec level start (inc end))
                                         (mapcat (fn [[_ cid]]
                                                   (node-entries (get-node get-fn cid))))
                                         (remove (fn [[k _]] (contains? rem-set k)))
                                         vec)
                           extra (mapcat #(get add-by-i % []) (range start (inc end)))
                           entries (reduce (fn [es [k v]] (upsert-sorted es k v))
                                           retained extra)]
                       (splice level start (inc (- end start))
                               (split-leaves put! entries))))
                   leaves
                   (sort-by first > windows))]
        (rebalance-level put! level)))))

(defn height
  "Internal levels below the root. Leaf-only tree → 0."
  [get-fn root-cid]
  (if root-cid (tree-height get-fn root-cid) 0))
