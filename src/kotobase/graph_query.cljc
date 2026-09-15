(ns kotobase.graph-query
  "Portable graph-query boundary over a Datomic-shaped datom head.

  Datoms are the canonical storage/query model.  Datalog is therefore passed
  through, while SPARQL, Cypher and Git are explicit adapters into the same
  entity/attribute/value representation.  The adapters intentionally accept a
  small structured algebra instead of pretending to parse complete query
  languages; hosts may put real parsers in front of this namespace."
  (:require [clojure.string :as str]))

(def supported-languages #{:datalog :sparql :cypher :git})

(defn- variable? [x]
  (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- require! [pred value problem data]
  (when-not (pred value)
    (throw (ex-info (name problem) (assoc data :problem problem))))
  value)

(defn triple-pattern
  "Normalize one graph pattern to Datomic `[?e :attr ?v]` form."
  [[e a v :as pattern]]
  (require! #(= 3 (count %)) pattern :graph/invalid-pattern {:pattern pattern})
  (require! #(or (keyword? %) (variable? %)) a :graph/invalid-attribute
            {:pattern pattern})
  [e a v])

(defn compile-query
  "Compile a deliberately small common graph algebra to a canonical query.

  Input examples:
  Datalog uses `:find` + `:where`, SPARQL uses `:select` + `:where`,
  and Cypher uses `:return` + `:match`.

  The returned map is stable and can be sent to a Datomic/DataScript/kotoba
  host. Full textual SPARQL/Cypher parsers are host adapters, not hidden here."
  [{:keys [language find select return where match] :as request}]
  (require! supported-languages language :graph/unsupported-language
            {:language language})
  (let [[projection clauses]
        (case language
          :datalog [find where]
          :sparql  [select where]
          :cypher  [return match]
          :git     [find where])]
    (require! sequential? projection :graph/projection-required {:request request})
    (require! sequential? clauses :graph/where-required {:request request})
    {:query/find (vec projection)
     :query/where (mapv triple-pattern clauses)
     :query/source-language language}))

(defn- bind-term [bindings term value]
  (cond
    (variable? term)
    (if (contains? bindings term)
      (when (= (get bindings term) value) bindings)
      (assoc bindings term value))

    (= term value) bindings
    :else nil))

(defn history
  "Return canonical five-tuples ordered by transaction. Three-tuples are
  treated as asserted at t=0 for adapter/conformance use."
  [datoms]
  (->> datoms
       (map (fn [datom]
              (let [[e a v tx added?] datom]
                [e a v (or tx 0) (if (nil? added?) true added?)])))
       (sort-by #(nth % 3))
       vec))

(defn as-of
  "Datomic-style immutable database value at inclusive BASIS-T.

  The latest assertion/retraction for each exact EAV wins. Cardinality and
  uniqueness constraints belong to schema/transact validation; this function
  deliberately preserves all asserted values for a cardinality-many attr."
  [datoms basis-t]
  (->> (history datoms)
       (filter #(<= (nth % 3) basis-t))
       (reduce (fn [state [e a v _ added? :as datom]]
                 (assoc state [e a v] (when added? datom)))
               {})
       vals
       (remove nil?)
       (sort-by (juxt #(nth % 0) #(str (nth % 1)) #(pr-str (nth % 2))))
       vec))

(defn since
  "History strictly newer than BASIS-T, including retractions."
  [datoms basis-t]
  (->> (history datoms) (filter #(> (nth % 3) basis-t)) vec))

(defn current
  "Current immutable database value from the complete history."
  [datoms]
  (as-of datoms (reduce max 0 (map #(nth % 3) (history datoms)))))

(defn execute
  "Reference executor for the common triple-pattern subset.

  DATOMS may be `[e a v]` or Datomic `[e a v tx added?]` tuples. Retractions
  (`added? = false`) are excluded. Production hosts should execute the
  compiled query in their indexed engine; this implementation is the portable
  conformance oracle and benchmark baseline."
  [datoms request]
  (let [{:query/keys [find where]} (compile-query request)
        active (current datoms)
        rows (reduce
              (fn [bindings pattern]
                (vec
                 (for [b bindings
                       datom active
                       :let [[e a v] datom
                             b1 (bind-term b (nth pattern 0) e)
                             b2 (when b1 (bind-term b1 (nth pattern 1) a))
                             b3 (when b2 (bind-term b2 (nth pattern 2) v))]
                       :when b3]
                   b3)))
              [{}]
              where)]
    (->> rows
         (mapv (fn [row] (mapv #(get row %) find)))
         distinct
         vec)))

(defn git-object-datoms
  "Project one immutable Git object/ref observation into canonical datoms.
  Content remains addressed by OID; refs are observations and may advance."
  [{:keys [oid type bytes parents ref target]}]
  (if oid
    (vec
     (concat
      [[oid :git.object/type type]
       [oid :git.object/bytes bytes]]
      (map (fn [parent] [oid :git.commit/parent parent]) parents)))
    (do
      (require! string? ref :git/ref-required {:ref ref})
      (require! string? target :git/target-required {:target target})
      [[ref :git.ref/target target]])))
