(ns kotobase.capability.workload
  "One deterministic datom workload, replayed identically into every backend.

  Same seed -> same entities, same attribute values, same update sequence,
  same query keys. Without that, a comparison between architectures is a
  comparison between random number generators."
  (:require [clojure.string :as str]))

;; ── deterministic PRNG (xorshift32) ────────────────────────────────────────

(defn rng [seed] (atom (bit-or seed 1)))

(defn next-int
  "Uniform in [0, n)."
  [r n]
  (let [x (long @r)
        x (bit-and 0xffffffff (bit-xor x (bit-shift-left x 13)))
        x (bit-xor x (unsigned-bit-shift-right x 17))
        x (bit-and 0xffffffff (bit-xor x (bit-shift-left x 5)))]
    (reset! r x)
    (mod x n)))

;; ── key encoding: content-addressed indexes are ordered by string key ──────

(defn pad
  [n width]
  (let [s (str n)]
    (str (str/join (repeat (max 0 (- width (count s))) "0")) s)))

(def attrs [:person/name :person/age :person/city :person/team :person/score])

(def cities ["tokyo" "osaka" "kyoto" "sapporo" "fukuoka" "nagoya" "sendai" "naha"])
(def teams ["ao" "aka" "kiiro" "midori" "murasaki" "kuro"])

(defn entity-key [e] (str "e" (pad e 7)))

(defn attr-str
  "`:person/age` -> \"person/age\". NOT `name`: `name` drops the namespace, and
  an index key that drops it collapses `:person/age` and `:pet/age` into one
  key — which then makes retraction miss, because the prior value is looked up
  under a different keyword than the one it was stored under. The
  cross-backend agreement check in `verify.cljs` is what caught that."
  [a]
  (if (keyword? a) (subs (str a) 1) (str a)))

(defn ->attr [s] (keyword s))

(defn ->v-key
  "Values must sort as strings for AVET range scans to mean anything."
  [v]
  (cond
    (number? v) (str "n" (pad v 10))
    :else (str "s" v)))

(defn eavt-key [e a t] (str (entity-key e) "|" (attr-str a) "|" (pad t 7)))
(defn aevt-key [a e t] (str (attr-str a) "|" (entity-key e) "|" (pad t 7)))
(defn avet-key [a v e] (str (attr-str a) "|" (->v-key v) "|" (entity-key e)))

(defn eavt-entity-prefix [e] (str (entity-key e) "|"))
(defn avet-value-prefix [a v] (str (attr-str a) "|" (->v-key v) "|"))
(defn avet-attr-prefix [a] (str (attr-str a) "|"))

;; ── generation ─────────────────────────────────────────────────────────────

(defn entity-datoms
  "The five facts about one entity at basis `t`."
  [r e t]
  [[e :person/name (str "person-" (pad e 7))     t]
   [e :person/age (+ 18 (next-int r 60))         t]
   [e :person/city (nth cities (next-int r (count cities))) t]
   [e :person/team (nth teams (next-int r (count teams)))   t]
   [e :person/score (next-int r 1000)            t]])

(defn make
  "-> {:load-txns [...] :update-txns [...] :queries {...} :meta {...}}

  `load-txns` are batched (a bulk import), `update-txns` are one entity each
  (steady state). Both are ordinary transactions to every backend; only the
  size differs, which is exactly the difference a write-amplification number
  is supposed to show."
  [{:keys [seed entities batch updates point-reads value-queries range-queries
           snapshot-reads]
    :or {seed 20260806 entities 4000 batch 250 updates 200
         point-reads 100 value-queries 50 range-queries 20 snapshot-reads 20}}]
  (let [r (rng seed)
        all (vec (mapcat (fn [e] (entity-datoms r e 0)) (range entities)))
        load-txns (vec (map-indexed
                        (fn [i chunk] {:t (inc i) :datoms (vec chunk) :kind :load})
                        (partition-all (* batch 5) all)))
        t0 (count load-txns)
        update-txns
        (vec (for [i (range updates)
                   :let [e (next-int r entities)
                         t (+ t0 1 i)]]
               {:t t
                :kind :update
                :entity e
                :datoms [[e :person/score (next-int r 1000) t]
                         [e :person/city (nth cities (next-int r (count cities))) t]]}))
        qr (rng (+ seed 7))]
    {:load-txns load-txns
     :update-txns update-txns
     :queries {:point (vec (repeatedly point-reads #(next-int qr entities)))
               :by-value (vec (repeatedly value-queries
                                          #(vector :person/city
                                                   (nth cities (next-int qr (count cities))))))
               :range (vec (repeatedly range-queries
                                       #(let [lo (next-int qr 900)]
                                          [:person/score lo (+ lo 50)])))
               :snapshot (vec (repeatedly snapshot-reads #(next-int qr entities)))}
     :meta {:seed seed :entities entities :datoms (count all)
            :load-txns (count load-txns) :update-txns updates
            :total-t (+ t0 updates)}}))

(defn datom-e [d] (nth d 0))
(defn datom-a [d] (nth d 1))
(defn datom-v [d] (nth d 2))
(defn datom-t [d] (nth d 3))

(defn txn-entities [txn] (into (sorted-set) (map datom-e (:datoms txn))))
