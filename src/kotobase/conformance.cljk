(ns kotobase.conformance
  "Whether two frontends executing one request agreed about the answer.

  `docs/ADR-execution-contract.md` says version 1 exists so that Datalog, SQL,
  SPARQL, Cypher, GraphQL and Gremlin can keep their own semantics while
  producing comparable evidence, and that plan digests may differ because the
  same request has more than one valid plan. Nothing compared anything. A
  purpose stated in a document and never executed is a purpose nobody can
  check.

  This compares. Given the receipts and rows from two or more frontends that
  were handed the same request, it refuses unless they agree about:

  - the **request** — same envelope, same digest, or they did not answer the
    same question;
  - the **answer** — the same rows, as a multiset.

  and permits them to differ about the **plan digest**, the **cost**, the
  **implementation build** and the **signature**, which is exactly what the
  contract said peers may differ about.

  ## The order problem, which this found

  `:result/root` in a receipt is the address of the rows *as served*, and an
  address is order sensitive. The query grammar
  (`kotobase.authorized-query/query-keys`) has no ordering clause, so the
  answer to any query this repository can express is a **multiset**: two
  conformant frontends may serve the same rows in different orders and produce
  **different result roots**.

  That is not a defect in either of them, and it is not something a caller can
  ignore. A result root identifies *what was served to whom*, which is what an
  audit needs; it is not the identity of the answer across frontends. So this
  namespace computes a separate **canonical result address** over the sorted
  multiset, compares that, and *reports* order divergence rather than hiding
  it — a caller comparing receipts across frontends by `:result/root` is
  asking the wrong question and should be told so rather than get a mismatch.

  If an ordering clause is ever added to the query grammar, the answer to such
  a query stops being a multiset and this canonicalisation becomes wrong for
  it. `ordered-query?` is where that decision has to be taken; it is a
  predicate rather than an assumption for that reason.

  ## What is not here

  A second *protocol*. This repository has one query surface; the frontends a
  test drives through here are two implementations of it, which is enough to
  show the harness discriminates and is not enough to claim cross-protocol
  agreement. A protocol frontend plugs in the same way, and the day one does,
  this is what it has to pass."
  (:require [clojure.set :as set]
            [kotobase.authorized-query :as query]
            [kotobase.execution-contract :as contract]))

(def comparable-keys
  "Receipt fields two conformant frontends must agree about."
  #{:receipt/version :request/digest :execution/manifest :authority/decision})

(def divergent-keys
  "Receipt fields the contract says peers may differ about."
  #{:query/plan-digest :cost :implementation/build :signature :result/root})

(defn- reject! [reason data]
  (throw (ex-info "conformance rejected"
                  (assoc data :kotobase.conformance/reason reason))))

(defn ordered-query?
  "Does QUERY ask for its rows in a particular order?

  Today, never: `kotobase.authorized-query` admits `:find`, `:where`, `:scope`
  and `:limit` and nothing else, so the answer is a multiset and sorting it is
  lossless. This is a predicate rather than a constant because the day an
  ordering clause is admitted, the canonicalisation below stops being correct
  for the queries that use it, and this is where that has to be noticed."
  [q]
  (when-not (map? q) (reject! :invalid-query {}))
  (boolean (some #{:order-by :order :sort-by} (keys q))))

(defn canonical-result
  "The address of ROWS as an answer rather than as a transmission.

  Sorted by each row's own address, so two frontends that served the same rows
  in different orders reach the same value. Duplicates are kept: a multiset is
  not a set, and a query that returns a row twice has said something."
  [value-cid q rows]
  (when-not (ifn? value-cid) (reject! :missing-address-function {}))
  (when-not (vector? rows) (reject! :invalid-rows {}))
  (when (ordered-query? q)
    ;; the rows are the answer in that order; sorting them would erase what
    ;; the query asked for
    (reject! :ordered-query-cannot-be-canonicalised {}))
  (value-cid (vec (sort-by value-cid rows))))

(defn- checked-execution [value-cid q {:keys [frontend receipt rows] :as execution}]
  (when-not (and (map? execution)
                 (= #{:frontend :receipt :rows} (set (keys execution))))
    (reject! :invalid-execution {:frontend frontend}))
  (when-not (or (keyword? frontend) (string? frontend))
    (reject! :invalid-frontend-name {}))
  (contract/validate-receipt! receipt)
  {:frontend frontend
   :receipt receipt
   :comparable (select-keys receipt comparable-keys)
   :canonical (canonical-result value-cid q rows)
   :served (:result/root receipt)})

(defn agree!
  "Refuse unless every frontend answered the same question with the same answer.

  `:executions` is `[{:frontend … :receipt … :rows …} …]`, at least two of
  them, all handed the same `:query`. Returns a report naming the shared
  request digest, the canonical answer, each frontend's plan digest, and
  whether any of them served the same answer in a different order."
  [{:keys [value-cid query executions] :as options}]
  (when-not (and (map? options)
                 (= #{:value-cid :query :executions} (set (keys options))))
    (reject! :invalid-conformance-options {}))
  (when-not (query/valid-query? query)
    (reject! :invalid-query {}))
  (when-not (and (vector? executions) (<= 2 (count executions)))
    ;; one frontend agreeing with itself is not conformance
    (reject! :too-few-frontends {:count (count executions)}))
  (let [checked (mapv #(checked-execution value-cid query %) executions)
        names (mapv :frontend checked)]
    (when-not (= (count names) (count (set names)))
      (reject! :duplicate-frontend {:frontends names}))
    (let [comparable (set (map :comparable checked))]
      (when-not (= 1 (count comparable))
        (reject! :request-divergence
                 {:frontends names
                  :differ (into #{}
                                (remove (fn [k]
                                          (apply = (map #(get-in % [:comparable k])
                                                        checked))))
                                comparable-keys)
                  :seen comparable})))
    (let [answers (set (map :canonical checked))]
      (when-not (= 1 (count answers))
        ;; the frontends did not agree about the answer. Which plans they used
        ;; and what they cost is beside the point now
        (reject! :result-divergence
                 {:frontends (into {} (map (juxt :frontend :canonical)) checked)})))
    {:request/digest (:request/digest (:receipt (first checked)))
     :result/canonical (:canonical (first checked))
     :frontends (into (sorted-map)
                      (map (juxt :frontend #(get-in % [:receipt :query/plan-digest])))
                      checked)
     :order-divergence (let [served (set (map :served checked))]
                         (when (< 1 (count served))
                           (into (sorted-map)
                                 (map (juxt :frontend :served))
                                 checked)))
     :divergent-fields (set/intersection
                        divergent-keys
                        (into #{}
                              (remove (fn [k]
                                        (apply = (map #(get-in % [:receipt k])
                                                      checked))))
                              divergent-keys))}))
