(ns kotobase.identity.schemes
  "Three ways to give a definition a CID, and what each one is stable under.

  1. `:source-text`      CIDv1-raw over the source bytes. What `source-cid`
                         already is in `semantic-code`, and correct for what it
                         is for — exact-bytes provenance.
  2. `:sexpr-canonical`  DAG-CBOR over the S-expression as data: structure
                         canonical, names intact, no macro expansion, no
                         binder normalisation. This is the naive reading of
                         'S-expressions are the database'.
  3. `:checked-kir`      what `kotoba.codebase.semantic-code` actually
                         produces: macro-expanded, symbol-resolved, binders as
                         de Bruijn indices, dependencies as CID links, the
                         definition's own name absent.

  They are not three encodings of one idea — they answer different questions,
  and the measurable difference is **what leaves the identity unchanged**. A
  scheme that changes CID when you reindent has no build cache. A scheme that
  changes CID when you rename a local has a build cache that misses on
  cosmetic edits, and — worse — propagates that miss to every dependent."
  (:require [clojure.string :as str]
            [cbor.core :as cbor]
            [multiformats.core :as mf]
            [kotoba.codebase.semantic-code :as sc]))

(defn source-text
  "Render a form as source. `:pretty?` inserts extra whitespace and newlines —
  a formatter run, changing no meaning."
  [form pretty?]
  (let [s (pr-str form)]
    (if pretty?
      (-> s (str/replace "(" "(\n  ") (str/replace ")" "\n)") (str/replace " " "  "))
      s)))

(defn source-cid [form pretty?]
  (mf/cidv1-raw #?(:cljs (.encode (js/TextEncoder.) (source-text form pretty?))
                   :clj (.getBytes ^String (source-text form pretty?) "UTF-8"))))

(defn- ->canonical
  "S-expression as canonical data. Structure is normalised (deterministic
  encoding, sorted map keys); names are not."
  [form]
  (cond
    (symbol? form) {"sym" (str form)}
    (keyword? form) {"kw" (subs (str form) 1)}
    (seq? form) {"list" (mapv ->canonical form)}
    (vector? form) {"vec" (mapv ->canonical form)}
    (map? form) {"map" (into (sorted-map)
                             (map (fn [[k v]] [(pr-str k) (->canonical v)]))
                             form)}
    (string? form) {"str" form}
    :else form))

(defn sexpr-cid [form]
  (mf/cidv1-dag-cbor (cbor/encode (->canonical form))))

(defn checked-kir-cids
  "The real lowering, for a whole corpus at once (it resolves references
  between definitions, so it cannot be done one form at a time).
  -> {definition-name cid}"
  [forms]
  (let [out (sc/compile-definitions forms)]
    (into {} (map (fn [[nm d]] [(str nm) (:cid d)])) (:definitions out))))

(defn scheme-cids
  "-> {scheme {definition-name cid}} for one corpus."
  [forms]
  (let [named (fn [f] (str (second f)))]
    {:source-text (into {} (map (fn [f] [(named f) (source-cid f false)])) forms)
     :sexpr-canonical (into {} (map (fn [f] [(named f) (sexpr-cid f)])) forms)
     :checked-kir (checked-kir-cids forms)}))

(defn changed
  "How many definitions changed identity between two runs of a scheme."
  [before after]
  (count (for [[n c] before :when (not= c (clojure.core/get after n))] n)))

;; ── perturbations that change no meaning (except the last) ─────────────────

(defn reformat
  "Whitespace only. Applies to the source-text scheme alone, because the other
  two never see the text."
  [forms]
  (into {} (map (fn [f] [(str (second f)) (source-cid f true)])) forms))

(defn rename-locals
  "Rename every `let` binding. Same program, different local names."
  [forms]
  (mapv (fn [form]
          (let [walk (fn walk [x]
                       (cond
                         (and (seq? x) (= 'let (first x)))
                         (let [[_ bindings & body] x
                               pairs (partition 2 bindings)
                               ren (into {} (map (fn [[s _]]
                                                   [s (symbol (str "renamed_" s))]))
                                         pairs)
                               sub (fn sub [y]
                                     (cond (symbol? y) (clojure.core/get ren y y)
                                           (seq? y) (map sub y)
                                           (vector? y) (mapv sub y)
                                           :else y))]
                           (list* 'let
                                  (vec (mapcat (fn [[s v]] [(clojure.core/get ren s s)
                                                            (sub (walk v))])
                                               pairs))
                                  (map (comp sub walk) body)))
                         (seq? x) (map walk x)
                         (vector? x) (mapv walk x)
                         :else x))]
            (walk form)))
        forms))

(defn rename-definitions
  "Rename the definitions themselves, and every call site with them. Same
  program, different public names. This is where the schemes disagree most:
  a name-free identity leaves both the definition AND its dependents
  untouched, while a name-bearing one invalidates the dependents too."
  [forms]
  (let [names (into #{} (map second) forms)
        ren (into {} (map (fn [n] [n (symbol (str "r_" n))])) names)
        sub (fn sub [y]
              (cond (symbol? y) (clojure.core/get ren y y)
                    (seq? y) (map sub y)
                    (vector? y) (mapv sub y)
                    :else y))]
    (mapv (fn [[d nm params body]]
            ;; carry the metadata across: declared effects are part of the
            ;; checked type, and silently dropping them would make a rename
            ;; look like a semantic change
            (let [nm' (clojure.core/get ren nm nm)]
              (list d (with-meta nm' (meta nm)) params (sub body))))
          forms)))

(defn changed-set
  "How many CIDs from `before` are absent from `after` as a SET. Used where the
  names deliberately changed, so a per-name comparison would be meaningless."
  [before after]
  (let [a (set (vals after))]
    (count (remove a (vals before)))))

(defn semantic-change-leaf
  "Change the LAST `n` definitions — the ones nothing depends on. Separates a
  scheme's sensitivity from the dependency graph's invalidation."
  [forms n]
  (let [total (count forms)]
    (mapv (fn [i form]
            (if (>= i (- total n))
              (let [[d nm params body] form] (list d nm params (list '+ body 7)))
              form))
          (range) forms)))

(defn semantic-change
  "A real change to `n` definitions — the control. Every scheme must move."
  [forms n]
  (mapv (fn [i form]
          (if (< i n)
            (let [[d nm params body] form] (list d nm params (list '+ body 7)))
            form))
        (range) forms))
