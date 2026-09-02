(ns kotobase.authorized-query
  "Bounded, authorization-aware query gateway.

  A guest supplies data, never a direct database connection or an evaluator.
  The injected policy compiler determines which projection and scope may reach
  the injected Datalog evaluator at one immutable basis."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def query-keys #{:find :where :scope :limit})
(def scope-keys #{:tenant :resources :purpose :basis})
(def max-clauses 32)
(def max-limit 1000)
(def max-join-fanout 100)
(def max-variable-count 20)

(defn- reject! [reason data]
  (throw (ex-info "authorized query rejected" (assoc data :kotobase.query/reason reason))))

(defn- extract-vars
  "Extract all logic variables (symbols starting with ?) from a pattern."
  [pattern]
  (filterv #(and (symbol? %) (str/starts-with? (name %) "?")) pattern))

(defn- build-var-dep-graph
  "Build a variable dependency graph from where clauses.
   Returns a map of var -> set of vars it depends on (shares a clause with)."
  [where-clauses]
  (let [var-clauses (group-by first
                              (mapcat (fn [clause]
                                        (let [vs (extract-vars clause)]
                                          (for [v vs] [v clause])))
                                      where-clauses))
        deps (reduce-kv (fn [acc var clauses]
                          (let [clause-vars (set (mapcat extract-vars (map second clauses)))
                                related-vars (set/union clause-vars #{var})]
                            (assoc acc var (disj related-vars var))))
                        {} var-clauses)]
    deps))

(defn- has-cycle?
  "Detect cycles in a variable dependency graph using DFS."
  [graph]
  (let [visited (atom #{})
        rec-stack (atom #{})
        cycle-found (atom false)]
    (doseq [node (keys graph)
            :while (not @cycle-found)]
      (when-not (contains? @visited node)
        (letfn [(dfs [n]
                  (when-not @cycle-found
                    (swap! visited conj n)
                    (swap! rec-stack conj n)
                    (doseq [neighbor (get graph n #{})
                            :while (not @cycle-found)]
                      (cond
                        (contains? @rec-stack neighbor)
                        (reset! cycle-found true)
                        (not (contains? @visited neighbor))
                        (dfs neighbor)))
                    (swap! rec-stack disj n)))]
          (dfs node))))
    @cycle-found))

(defn- estimate-join-fanout
  "Estimate the maximum intermediate join size from where clauses.
   Heuristic: count unique variables that appear in multiple clauses (join edges),
   and estimate fanout based on clause counts sharing variables."
  [where-clauses]
  (let [var-clauses (group-by identity
                              (mapcat (fn [clause]
                                        (let [vs (extract-vars clause)]
                                          (for [v vs] [v clause])))
                                      where-clauses))
        join-vars (filter (fn [[_ clauses]] (> (count clauses) 1)) var-clauses)
        max-clauses-per-var (if (seq join-vars)
                              (apply max (map (comp count val) join-vars))
                              1)]
    (* (count join-vars) max-clauses-per-var)))

(defn- count-variables
  "Count unique variables across all where clauses."
  [where-clauses]
  (count (set (mapcat extract-vars where-clauses))))

(defn valid-query? [query]
  (and (map? query)
       (= query-keys (set (keys query)))
       (vector? (:find query)) (seq (:find query)) (<= (count (:find query)) 64)
       (vector? (:where query)) (<= (count (:where query)) max-clauses)
       (every? #(and (vector? %) (<= 1 (count %) 4)) (:where query))
       ;; Static analysis: recursion detection
       (not (has-cycle? (build-var-dep-graph (:where query))))
       ;; Static analysis: variable count limit
       (<= (count-variables (:where query)) max-variable-count)
       ;; Static analysis: join fanout estimation
       (<= (estimate-join-fanout (:where query)) max-join-fanout)
       (map? (:scope query)) (= scope-keys (set (keys (:scope query))))
       (string? (get-in query [:scope :tenant])) (seq (get-in query [:scope :tenant]))
       (set? (get-in query [:scope :resources])) (seq (get-in query [:scope :resources]))
       (keyword? (get-in query [:scope :purpose]))
       (string? (get-in query [:scope :basis])) (seq (get-in query [:scope :basis]))
       (pos-int? (:limit query)) (<= (:limit query) max-limit)))

(defn- validate-decision! [query decision]
  (let [expected #{:allowed? :projection :basis :policy-cid}
        requested (set (:find query))]
    (when-not (and (map? decision) (= expected (set (keys decision)))
                   (boolean? (:allowed? decision)) (set? (:projection decision))
                   (string? (:basis decision)) (string? (:policy-cid decision)))
      (reject! :invalid-decision {}))
    (when-not (= (get-in query [:scope :basis]) (:basis decision))
      (reject! :basis-mismatch {:query-basis (get-in query [:scope :basis])
                                :decision-basis (:basis decision)}))
    (when-not (:allowed? decision) (reject! :denied {:decision decision}))
    (when-not (set/subset? requested (:projection decision))
      (reject! :projection-denied {:requested requested :allowed (:projection decision)}))
    {:query query :decision decision
     :provenance {:basis (:basis decision) :policy-cid (:policy-cid decision)
                  :tenant (get-in query [:scope :tenant])
                  :purpose (get-in query [:scope :purpose])}}))

(defn compile!
  "Ask AUTHORIZE! to compile the guest AST at its declared immutable basis.
  It must return an exact policy result with `:allowed?` and a set-valued
  `:projection`; an allowed result may only narrow the requested projection."
  [authorize! query]
  (when-not (valid-query? query) (reject! :invalid-query {}))
  (when-not (ifn? authorize!) (reject! :missing-authorizer {}))
  (validate-decision! query (authorize! query)))

#?(:cljs
   (defn compile-async!
     "Promise-aware policy compilation for Worker/JavaScript authorizers.

     The authorizer may be a local function or a third-party LLM/model/agent.
     Its Promise is awaited and the same exact-basis, projection, and denial
     checks as `compile!` run before any evaluator is called."
     [authorize! query]
     (try
       (when-not (valid-query? query) (reject! :invalid-query {}))
       (when-not (ifn? authorize!) (reject! :missing-authorizer {}))
       (-> (js/Promise.resolve (authorize! query))
           (.then #(validate-decision! query %)))
       (catch :default error
         (js/Promise.reject error)))))

(defn- durable-result [compiled rows ack]
  (when-not (vector? rows) (reject! :invalid-result {}))
  (when-not (and (map? ack) (true? (:receipt/durable? ack))
                 (string? (:receipt/cid ack)) (seq (:receipt/cid ack))
                 (or (nil? (:receipt/commit-cid ack))
                     (and (string? (:receipt/commit-cid ack))
                          (seq (:receipt/commit-cid ack)))))
    (reject! :receipt-not-durable {:ack ack}))
  {:rows rows
   :provenance (cond-> (assoc (:provenance compiled)
                              :receipt-cid (:receipt/cid ack)
                              :row-count (count rows))
                 (:receipt/commit-cid ack)
                 (assoc :receipt-commit-cid
                        (:receipt/commit-cid ack)))})

(defn execute!
  "Execute only a compiled query through EVALUATE!, and record that it ran.

  RECEIPT! is required, not optional. `kotobase.admission` already refuses to
  run an effect until its `audit!` returns `:audit/durable? true`, so `who did
  this` is answerable; without the same requirement here, `who read this` is
  not — a query could be admitted, evaluated, and leave nothing behind. The
  gate cannot know the query and result CIDs itself (the host owns the
  canonical codec, see `receipt-projection`), so it asks the host to write the
  receipt and refuses the rows unless the host says it is durable.

  RECEIPT! is handed the rows as well as their count. A receipt that binds
  only a row count is not evidence about the result: `kotobase.execution-
  contract`'s `:result/root` has to be computed by the host's canonical codec
  over what was actually served, and the sink is the only place that runs
  before the rows are released. Sinks persist the root, not the rows.

  The returned provenance is ready to bind into an execution identity; raw
  query execution is unavailable from this namespace."
  [evaluate! receipt! compiled]
  (when-not (and (map? compiled) (= #{:query :decision :provenance} (set (keys compiled))))
    (reject! :invalid-compiled-query {}))
  (when-not (ifn? evaluate!) (reject! :missing-evaluator {}))
  (when-not (ifn? receipt!) (reject! :missing-receipt-sink {}))
  (let [rows (evaluate! (:query compiled) (:decision compiled))]
    (when-not (vector? rows) (reject! :invalid-result {}))
    (durable-result compiled rows
                    (receipt! {:compiled compiled :rows rows
                               :row-count (count rows)}))))

#?(:cljs
   (defn execute-async!
     "Promise-aware protected execution for Worker/JavaScript hosts.

     Rows are withheld until both the evaluator and the durable receipt sink
     resolve successfully."
     [evaluate! receipt! compiled]
     (try
       (when-not (and (map? compiled)
                      (= #{:query :decision :provenance} (set (keys compiled))))
         (reject! :invalid-compiled-query {}))
       (when-not (ifn? evaluate!) (reject! :missing-evaluator {}))
       (when-not (ifn? receipt!) (reject! :missing-receipt-sink {}))
       (-> (js/Promise.resolve
            (evaluate! (:query compiled) (:decision compiled)))
           (.then
            (fn [rows]
              (when-not (vector? rows) (reject! :invalid-result {}))
              (-> (js/Promise.resolve
                   (receipt! {:compiled compiled :rows rows
                              :row-count (count rows)}))
                  (.then #(durable-result compiled rows %))))))
       (catch :default error
         (js/Promise.reject error)))))

(defn receipt-projection
  "Return the non-sensitive, immutable-fact projection a host must bind into
  a content-addressed query receipt.  The query and result CIDs are supplied
  by the host's canonical codec; this gateway never hashes an ad-hoc printed
  representation."
  [compiled query-cid result-cid]
  (when-not (and (map? compiled) (= #{:query :decision :provenance} (set (keys compiled))))
    (reject! :invalid-compiled-query {}))
  (when-not (and (string? query-cid) (seq query-cid)
                 (string? result-cid) (seq result-cid))
    (reject! :invalid-receipt-identity {}))
  (let [scope (get-in compiled [:query :scope])]
    {:query-cid query-cid
     :result-cid result-cid
     :basis (:basis (:provenance compiled))
     :policy-cid (:policy-cid (:provenance compiled))
     :tenant (:tenant scope)
     :purpose (:purpose scope)
     :resource-cids (->> (:resources scope) sort vec)}))