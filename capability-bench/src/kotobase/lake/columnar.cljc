(ns kotobase.lake.columnar
  "Does the datom plane hold up as a data lake?

  The lake question is not 'can it store a lot' — content addressing is fine
  at that. It is whether a scan or an aggregate over one column has to walk a
  row-shaped index. So this builds the other shape on the same blocks: one
  column per attribute, sorted, cut into row-group chunks, each chunk carrying
  a min/max zone map in a footer, which is what makes an object store viable
  for analytics (one small footer read, then only the chunks that can match).

  Both shapes are content-addressed and both live in the same block store, so
  the comparison is chunk layout, not storage technology."
  (:require [clojure.string :as str]
            [cbor.core :as cbor]
            [ipld.core :as ipld]
            [ipld.link :as link]
            [multiformats.core :as mf]
            [prolly-tree.core :as pt]
            [kotobase.capability.workload :as w]))

(defn- put! [ports node]
  (let [bytes (cbor/encode node)
        cid (mf/cidv1-dag-cbor bytes)]
    ((:put! ports) cid bytes)
    cid))

(defn build!
  "One column per attribute; `rows-per-chunk` rows per block; a footer holding
  every chunk's link and its [min max] zone map."
  [ports datoms {:keys [rows-per-chunk] :or {rows-per-chunk 4096}}]
  (let [by-attr (group-by w/datom-a datoms)
        columns
        (into {}
              (for [[a ds] by-attr]
                (let [rows (vec (sort-by first
                                         (map (fn [d] [(w/->v-key (w/datom-v d))
                                                       (w/entity-key (w/datom-e d))])
                                              ds)))
                      chunks (vec (partition-all rows-per-chunk rows))
                      chunk-meta
                      (mapv (fn [chunk]
                              (let [cid (put! ports {"schema" "kotobase.column-chunk.v1"
                                                     "values" (mapv first chunk)
                                                     "entities" (mapv second chunk)})]
                                {"cid" cid
                                 "min" (first (first chunk))
                                 "max" (first (last chunk))
                                 "rows" (count chunk)}))
                            chunks)]
                  [a {:footer (put! ports {"schema" "kotobase.column-footer.v1"
                                           "attribute" (w/attr-str a)
                                           "chunks" chunk-meta})
                      :chunks (count chunks)
                      :rows (count rows)}])))]
    {:columns columns
     :root (put! ports {"schema" "kotobase.lake-manifest.v1"
                        "columns" (into (sorted-map)
                                        (map (fn [[a c]] [(w/attr-str a) (:footer c)]))
                                        columns)})}))

(defn range-aggregate
  "Sum/count over `a` where lo <= v <= hi, using the zone maps to skip chunks.
  Returns the answer and what reading it cost, including hops: footer, then
  the surviving chunks in parallel — two dependent round trips, whatever the
  column's size."
  [ports lake a lo hi]
  (let [footer-cid (get-in lake [:columns a :footer])
        footer (cbor/decode ((:get ports) footer-cid))
        lo-k (w/->v-key lo) hi-k (w/->v-key hi)
        candidates (filter (fn [c]
                             (and (<= (compare (get c "min") hi-k) 0)
                                  (>= (compare (get c "max") lo-k) 0)))
                           (get footer "chunks"))
        rows (mapcat (fn [c]
                       (let [chunk (cbor/decode ((:get ports) (get c "cid")))]
                         (map vector (get chunk "values") (get chunk "entities"))))
                     candidates)
        hits (filter (fn [[v _]] (and (>= (compare v lo-k) 0)
                                      (<= (compare v hi-k) 0)))
                     rows)]
    {:via :columnar
     :chunks-total (count (get footer "chunks"))
     :chunks-read (count candidates)
     :chunks-skipped (- (count (get footer "chunks")) (count candidates))
     :rows-scanned (count rows)
     :matches (count hits)
     :requests (inc (count candidates))
     :hops 2}))

(defn index-aggregate
  "The same aggregate off the row-shaped AVET Prolly index: prefix-scan the
  whole attribute and filter. Hops are the tree height, because each level's
  address comes out of the level above."
  [get-fn root a lo hi height]
  (let [lo-k (w/->v-key lo) hi-k (w/->v-key hi)
        rows (pt/scan-prefix get-fn root (w/avet-attr-prefix a))
        hits (filter (fn [[k _]]
                       (let [vk (second (str/split k #"\|"))]
                         (and (>= (compare vk lo-k) 0) (<= (compare vk hi-k) 0))))
                     rows)]
    {:via :avet-index
     :rows-scanned (count rows)
     :matches (count hits)
     :hops height}))

(defn tree-height
  "Walk one path root->leaf. The number of levels IS the hop count of every
  point lookup and every prefix scan on this tree."
  [get-fn root]
  (loop [cid root h 0]
    (let [node (ipld/decode (get-fn cid))]
      (if (= "leaf" (get node "kind"))
        (inc h)
        (recur (link/link-cid (second (first (get node "children"))))
               (inc h))))))
