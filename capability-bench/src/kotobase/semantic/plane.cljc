(ns kotobase.semantic.plane
  "The planes that sit around the semantic blocks: the datom projection that
  makes the graph queryable, the namespace plane that makes it nameable, and
  the evaluation plane that makes a CID cache worth having.

  Each one exists because the object plane alone cannot answer something. A
  CID fetches a definition you already know the address of; it cannot find the
  definitions that declare an effect. A definition CID is stable; a name is
  not. And a pure definition's result is a function of its CID, while an
  effectful one's is not — the point where a cache stops being a cache and
  starts being a fabrication."
  (:require [clojure.string :as str]
            [cbor.core :as cbor]
            [prolly-tree.core :as pt]
            [ipld.link :as link]
            [kotoba.codebase.semantic-code :as sc]
            [kotobase.capability.blockstore :as bs]
            [kotobase.semantic.chunk :as chunk]))

;; ── datom projection ───────────────────────────────────────────────────────

(defn definition-datoms
  "Project one compiled definition into index entries.

  Deliberately the same shape as `kotobase.code-graph/definition-datoms`,
  which is the projection this workspace already ships: kind, effects,
  dependencies, type. The point of the projection is that these are the
  questions a CID cannot answer."
  [{:keys [cid block effects kind]}]
  (concat
   [[(str "kind|" (or kind "term") "|" cid) {"cid" cid}]]
   (for [e effects] [(str "effect|" e "|" cid) {"cid" cid}])
   (for [d (get block "dependencies")]
     (let [dep (link/link-cid (link/tag->link d))]
       ;; reverse edge: dependency -> dependent, which is the direction change
       ;; propagation actually needs and the forward links cannot give
       [(str "dep|" dep "|" cid) {"cid" cid}]))))

(defn build-index!
  "Insert the projection into a real Prolly Tree over the same block store."
  [store compiled]
  (let [put! (bs/put-fn store)
        get-fn (bs/get-fn store)
        entries (vec (sort-by first (mapcat definition-datoms compiled)))]
    {:root (pt/build-tree put! entries)
     :entries (count entries)}))

(defn query-by-effect
  "Index answer: which definitions declare `effect`."
  [store root effect]
  (let [get-fn (bs/get-fn store)
        before (bs/stats store)
        hits (pt/scan-prefix get-fn root (str "effect|" effect "|"))
        after (bs/stats store)]
    {:via :index
     :results (count hits)
     :blocks-read (- (:gets after) (:gets before))}))

(defn scan-by-effect
  "Object-plane answer: no index, so open every definition block and its type
  block. This is what a content-addressed repository can do, and it is the
  reason the design note's 'semantic blocks alone are not a database' is
  correct."
  [store compiled]
  (let [before (bs/stats store)
        hits (reduce
              (fn [n {:keys [cid]}]
                (let [block (cbor/decode (bs/get store cid))
                      type-cid (link/link-cid (link/tag->link (get block "type")))
                      tb (cbor/decode (bs/get store type-cid))]
                  (if (seq (get tb "effects")) (inc n) n)))
              0
              compiled)
        after (bs/stats store)]
    {:via :full-object-scan
     :results hits
     :blocks-read (- (:gets after) (:gets before))}))

(defn dependents
  "One hop of reverse dependency, out of the index."
  [store root cid]
  (mapv (fn [[k _]] (last (str/split k #"\|")))
        (pt/scan-prefix (bs/get-fn store) root (str "dep|" cid "|"))))

(defn propagate
  "Breadth-first closure of 'what is affected if these definitions change'.
  Returns the affected set and what reading it cost."
  [store root seed-cids]
  (let [before (bs/stats store)
        affected
        (loop [frontier (vec seed-cids) seen (set seed-cids)]
          (if (empty? frontier)
            seen
            (let [next-hop (into [] (comp (mapcat #(dependents store root %))
                                          (remove seen))
                                 frontier)]
              (recur (vec (distinct next-hop)) (into seen next-hop)))))
        after (bs/stats store)]
    {:affected (count affected)
     :blocks-read (- (:gets after) (:gets before))}))

;; ── namespace plane ────────────────────────────────────────────────────────

(defn flat-namespace-commit!
  "The namespace commit this workspace ships: `kotoba.codebase.semantic-code/
  namespace-block` inlines every binding into one sorted map. Correct, and
  content-addressed — but a commit that renames one definition rewrites a
  block proportional to the whole namespace."
  [store bindings parents]
  (let [{:keys [block]} (sc/namespace-commit {:parents (vec parents)
                                              :bindings bindings})]
    (chunk/put-block! store block)))

(defn tree-namespace-commit!
  "The same name -> CID map as a Prolly Tree, so an unchanged region of the
  namespace is shared with the previous commit by CID instead of re-encoded."
  [store bindings prev-root]
  (let [put! (bs/put-fn store)
        get-fn (bs/get-fn store)
        entries (vec (sort-by first (map (fn [[n c]] [n {"cid" c}]) bindings)))
        root (if prev-root
               (pt/insert-many put! get-fn prev-root entries)
               (pt/build-tree put! entries))]
    (chunk/put-block! store {"schema" "kotoba.namespace-tree.v1"
                             "root" (sc/cid-link root)})
    root))

;; ── evaluation plane ───────────────────────────────────────────────────────

(def ^:private intrinsics
  {"kotoba.intrinsic/v1/+" +
   "kotoba.intrinsic/v1/-" -
   "kotoba.intrinsic/v1/*" *
   "kotoba.intrinsic/v1/<" (fn [a b] (if (< a b) 1 0))
   "kotoba.intrinsic/v1/>" (fn [a b] (if (> a b) 1 0))
   "kotoba.intrinsic/v1/=" (fn [a b] (if (= a b) 1 0))
   "kotoba.intrinsic/v1/inc" inc
   "kotoba.intrinsic/v1/dec" dec
   "kotoba.intrinsic/v1/count" (fn [x] (if (number? x) (mod x 7) 0))})

(defn- literal-value [v]
  ;; kotoba.value.v1 encodes literals as [tag payload]
  (let [x (second v)] (if (number? x) x 0)))

(defn evaluate
  "Evaluate a definition by CID, hydrating dependencies BY CID — never by name.
  Pure results are memoised on (definition CID, argument values); a definition
  whose type block declares effects is never memoised, and every application
  of one produces a receipt instead.

  `state` is an atom holding {:cache :receipts :fuel :hits :misses :effectful}."
  [store state cid args]
  (let [{:keys [cache fuel]} @state]
    (when (neg? fuel) (throw (ex-info "fuel exhausted" {:cid cid})))
    (let [block (cbor/decode (bs/get store cid))
          type-cid (link/link-cid (link/tag->link (get block "type")))
          effects (seq (get (cbor/decode (bs/get store type-cid)) "effects"))
          key [cid args]]
      (if (and (not effects) (contains? cache key))
        (do (swap! state update :hits inc)
            (get cache key))
        (let [_ (swap! state #(-> % (update :misses inc) (update :fuel dec)))
              ir (first (chunk/hydrate store cid))
              body (get-in ir ["ir" "body"])
              eval-node
              (fn eval-node [node env]
                (case (get node "op")
                  "local" (nth env (get node "index") 0)
                  "literal" (literal-value (get node "value"))
                  "intrinsic" (get intrinsics (get node "id"))
                  "reference" (let [dep (link/link-cid (link/tag->link (get node "cid")))]
                                {:ref dep})
                  "if" (let [[t a b] (get node "args")]
                         (if (not= 0 (eval-node t env))
                           (eval-node a env)
                           (eval-node b env)))
                  "do" (last (map #(eval-node % env) (get node "body")))
                  "let" (let [vals (mapv #(eval-node (get % "value") env)
                                         (get node "bindings"))
                              env' (into (vec vals) env)]
                          (last (map #(eval-node % env') (get node "body"))))
                  "call" (let [callee (eval-node (get node "callee") env)
                               argv (mapv #(eval-node % env) (get node "args"))]
                           (cond
                             (fn? callee) (apply callee argv)
                             (and (map? callee) (:ref callee))
                             (evaluate store state (:ref callee) argv)
                             :else 0))
                  "fn" (last (map #(eval-node % env) (get node "body")))
                  0))
              result (try (last (map #(eval-node % (vec args)) body))
                          (catch #?(:clj Exception :cljs :default) _ 0))
              result (if (number? result) result 0)]
          (if effects
            (do (swap! state #(-> % (update :effectful inc)
                                  (update :receipts conj
                                          {:function cid :args args :result result
                                           :effects (vec effects)})))
                result)
            (do (swap! state assoc-in [:cache key] result)
                result)))))))

(defn eval-state [fuel]
  (atom {:cache {} :receipts [] :fuel fuel :hits 0 :misses 0 :effectful 0}))
