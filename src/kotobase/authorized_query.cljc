(ns kotobase.authorized-query
  "Bounded, authorization-aware query gateway.

  A guest supplies data, never a direct database connection or an evaluator.
  The injected policy compiler determines which projection and scope may reach
  the injected Datalog evaluator at one immutable basis."
  (:require [clojure.set :as set]))

(def query-keys #{:find :where :scope :limit})
(def scope-keys #{:tenant :resources :purpose :basis})
(def max-clauses 32)
(def max-limit 1000)

(defn- reject! [reason data]
  (throw (ex-info "authorized query rejected" (assoc data :kotobase.query/reason reason))))

(defn valid-query? [query]
  (and (map? query)
       (= query-keys (set (keys query)))
       (vector? (:find query)) (seq (:find query)) (<= (count (:find query)) 64)
       (vector? (:where query)) (<= (count (:where query)) max-clauses)
       (every? #(and (vector? %) (<= 1 (count %) 4)) (:where query))
       (map? (:scope query)) (= scope-keys (set (keys (:scope query))))
       (string? (get-in query [:scope :tenant])) (seq (get-in query [:scope :tenant]))
       (set? (get-in query [:scope :resources])) (seq (get-in query [:scope :resources]))
       (keyword? (get-in query [:scope :purpose]))
       (string? (get-in query [:scope :basis])) (seq (get-in query [:scope :basis]))
       (pos-int? (:limit query)) (<= (:limit query) max-limit)))

(defn compile!
  "Ask AUTHORIZE! to compile the guest AST at its declared immutable basis.
  It must return an exact policy result with `:allowed?` and a set-valued
  `:projection`; an allowed result may only narrow the requested projection."
  [authorize! query]
  (when-not (valid-query? query) (reject! :invalid-query {}))
  (when-not (ifn? authorize!) (reject! :missing-authorizer {}))
  (let [decision (authorize! query)
        expected #{:allowed? :projection :basis :policy-cid}
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

(defn execute!
  "Execute only a compiled query through EVALUATE!. The returned provenance is
  ready to bind into an execution identity; raw query execution is unavailable
  from this namespace."
  [evaluate! compiled]
  (when-not (and (map? compiled) (= #{:query :decision :provenance} (set (keys compiled))))
    (reject! :invalid-compiled-query {}))
  (when-not (ifn? evaluate!) (reject! :missing-evaluator {}))
  (let [rows (evaluate! (:query compiled) (:decision compiled))]
    (when-not (vector? rows) (reject! :invalid-result {}))
    {:rows rows :provenance (:provenance compiled)}))

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
