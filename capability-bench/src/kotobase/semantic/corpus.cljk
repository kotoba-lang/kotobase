(ns kotobase.semantic.corpus
  "A deterministic corpus of Kotoba definitions, shaped like real ones.

  The shape parameters are not invented. Parsing every `.kotoba` file in this
  workspace (72 top-level definitions across `kotoba-lang/crdt` and
  `kotoba-lang/hash`) gives:

      definitions 72   nodes/def  mean 49.5  median 40  p90 103
                       depth      mean 7.2   max 15

  so the generator targets a median around 40 nodes and a depth around 7, and
  the benchmark reports what it actually produced rather than what it aimed
  for. Dependency fan-out is a parameter (default 8) taken from the design
  note being tested, not measured — it is labelled as an assumption wherever
  it affects a result.

  Definitions only reference definitions defined before them: the real
  `semantic-code` lowering fails closed on recursion
  (`:semantic/recursive-group-required`), and pretending otherwise would be
  benchmarking a code path that does not exist."
  (:require [kotobase.capability.workload :as w]))

(def measured-shape
  {:source "all *.kotoba in kotoba-lang/crdt + kotoba-lang/hash, 2026-08-06"
   :definitions 72
   :nodes {:mean 49.5 :median 40 :p90 103 :total 3566}
   :depth {:mean 7.2 :max 15}})

(def ^:private binops ['+ '- '* '< '> '=])
(def ^:private unops ['inc 'dec 'count])

(defn- pick [r coll] (nth coll (w/next-int r (count coll))))

(defn- gen-expr
  "Build an expression of roughly `budget` nodes at `depth` remaining.
  `locals` are in-scope symbols, `callable` are earlier definition names."
  [r {:keys [budget depth locals callable]}]
  (cond
    (or (<= budget 1) (<= depth 0))
    (if (and (seq locals) (even? (w/next-int r 10)))
      (pick r locals)
      (w/next-int r 100))

    ;; a call into an earlier definition — this is what creates the dependency
    ;; edges the whole comparison is about
    (and (seq callable) (< (w/next-int r 10) 3))
    (let [f (pick r callable)]
      (list f
            (gen-expr r {:budget (quot budget 2) :depth (dec depth)
                         :locals locals :callable callable})))

    (< (w/next-int r 10) 2)
    (list 'if
          (list (pick r ['< '> '=])
                (gen-expr r {:budget (quot budget 4) :depth (dec depth)
                             :locals locals :callable callable})
                (w/next-int r 50))
          (gen-expr r {:budget (quot budget 3) :depth (dec depth)
                       :locals locals :callable callable})
          (gen-expr r {:budget (quot budget 3) :depth (dec depth)
                       :locals locals :callable callable}))

    (< (w/next-int r 10) 2)
    (let [sym (symbol (str "t" (w/next-int r 1000)))]
      (list 'let [sym (gen-expr r {:budget (quot budget 3) :depth (dec depth)
                                   :locals locals :callable callable})]
            (gen-expr r {:budget (quot budget 2) :depth (dec depth)
                         :locals (conj locals sym) :callable callable})))

    (< (w/next-int r 10) 2)
    (list (pick r unops)
          (gen-expr r {:budget (dec budget) :depth (dec depth)
                       :locals locals :callable callable}))

    :else
    (list (pick r binops)
          (gen-expr r {:budget (quot budget 2) :depth (dec depth)
                       :locals locals :callable callable})
          (gen-expr r {:budget (quot budget 2) :depth (dec depth)
                       :locals locals :callable callable}))))

(defn node-count [f] (if (coll? f) (inc (reduce + 0 (map node-count (seq f)))) 1))
(defn depth-of [f] (if (coll? f) (inc (reduce max 0 (map depth-of (seq f)))) 1))

(defn- def-name [i] (symbol (str "d" (w/pad i 6))))

(defn make
  "-> {:forms [...] :effectful #{name} :stats {...}}

  `effect-ratio` of the definitions declare an effect in their metadata. The
  real lowering hashes declared effects into the type block, and the
  evaluation plane refuses to memoise them — which is the distinction the
  design note calls out and the one thing here that must not be blurred."
  [{:keys [seed definitions nodes fan-out depth effect-ratio dep-shape]
    :or {seed 20260806 definitions 3000 nodes 40 fan-out 8 depth 7
         effect-ratio 0.08 dep-shape :library}}]
  (let [r (w/rng seed)
        ;; The dependency shape is the single most consequential parameter in
        ;; this whole benchmark, because in a Merkle code graph an edit changes
        ;; the CID of every *transitive dependent*. A chain and a library are
        ;; the same edit and a completely different amount of invalidation.
        library-size (max 4 (quot definitions 20))
        forms
        (loop [i 0 out [] names []]
          (if (= i definitions)
            out
            (let [callable (case dep-shape
                             ;; most definitions call a small early utility
                             ;; layer — what real code looks like
                             :library (vec (take fan-out (take library-size names)))
                             ;; each definition calls the ones just before it
                             :chain (vec (take-last fan-out names))
                             ;; uniformly among everything defined earlier
                             :uniform (vec (repeatedly
                                            (min fan-out (count names))
                                            #(nth names (w/next-int r (max 1 (count names))))))
                             (vec (take fan-out names)))
                  params ['a 'b]
                  body (gen-expr r {:budget nodes :depth depth
                                    :locals params :callable callable})
                  nm (def-name i)
                  effectful? (< (w/next-int r 1000) (* 1000 effect-ratio))
                  nm (if effectful?
                       (with-meta nm {:effects #{:http/fetch}})
                       nm)]
              (recur (inc i)
                     (conj out (list 'defn nm params body))
                     (conj names (def-name i))))))
        measured (map (fn [f] {:n (node-count f) :d (depth-of f)}) forms)
        sorted (vec (sort (map :n measured)))]
    {:forms (vec forms)
     :effectful (into #{} (comp (map second)
                                (filter #(seq (:effects (meta %) #{})))
                                (map #(symbol (name %))))
                      forms)
     :stats {:definitions (count forms)
             :nodes-total (reduce + (map :n measured))
             :nodes-mean (/ (reduce + (map :n measured)) (max 1 (count measured)))
             :nodes-median (nth sorted (quot (count sorted) 2))
             :nodes-p90 (nth sorted (min (dec (count sorted))
                                         (int (* 0.9 (count sorted)))))
             :depth-mean (/ (reduce + (map :d measured)) (max 1 (count measured)))
             :depth-max (reduce max (map :d measured))
             :fan-out-parameter fan-out
             :dep-shape dep-shape
             :library-size (when (= dep-shape :library) library-size)
             :effect-ratio effect-ratio}}))

(defn edit
  "Return `forms` with `n` definitions changed by one literal each — the
  smallest real edit, so write amplification is measured at its floor rather
  than at a convenient maximum.

  `target` decides WHERE the edit lands, which matters more than how big it
  is. Dependencies only point backwards, so the last definitions have no
  dependents at all (`:leaf`) and the first ones have the most (`:hub`). In a
  Merkle code graph those two are not variations of the same edit: one
  invalidates itself, the other invalidates everything downstream of it."
  ([forms n seed] (edit forms n seed :random))
  ([forms n seed target]
   (let [r (w/rng seed)
         total (count forms)
         targets (case target
                   :leaf (into #{} (range (max 0 (- total n)) total))
                   :hub (into #{} (range (min n total)))
                   (into #{} (repeatedly n #(w/next-int r total))))]
     {:forms (vec (map-indexed
                   (fn [i form]
                     (if (contains? targets i)
                       (let [[d nm params body] form]
                         (list d nm params (list '+ body 1)))
                       form))
                   forms))
      :target target
      :edited targets})))
