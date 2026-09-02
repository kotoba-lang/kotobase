(ns kotobase.evidence
  "One evidence plane, and an honest account of what does not reach it.

  Four kinds of receipt were written before the execution contract existed:

  - `kotobase.causal-commit` / `kotobase.causal-trust` commit a **causal
    disclosure receipt** for every guarded read;
  - `kotobase.code-graph` persists a **query receipt** bound to an execution
    identity, and an **execution receipt** for one artifact build;
  - `kotobase.admission` requires a durable **audit receipt** before any
    effect thunk runs.

  `kotobase.governed-execution` then added a fifth. Saying the contract is
  the evidence plane while four other planes keep being written is not a
  claim anyone can check, so this namespace makes it checkable: it lifts what
  can be lifted, refuses what cannot, and *names the fields each plane cannot
  answer* rather than filling them in.

  The rule that makes an adapter worth having is the one it cannot break: the
  supplement a caller passes must be **exactly** the fields the source record
  does not carry. A field the source answers may not be supplied — that is
  laundering, and it is refused by name — and a field the source lacks may
  not be omitted. So a lifted receipt is either genuinely derived or it does
  not exist, and the size of the supplement is the measurement of how far a
  plane is from the contract.

  ## What is not liftable, and why

  Two of the four are not query executions at all, and no supplement fixes
  that:

  - the **code-graph execution receipt** records that an artifact was built
    from an admitted code graph under granted effects. It has a code root, a
    compiler contract and output roots; it has no query plan and no served
    result.
  - the **admission audit receipt** records that an effect thunk was allowed
    to run — hydrate, execute or pin — against requested, delegated and local
    effect sets. Same absence.

  A version 1 ExecutionReceipt binds one *query* execution at one immutable
  basis: a plan digest and a result root are required fields, and version 1 is
  deliberately closed. Mapping an effect admission onto it would mean inventing
  both. So the answer to `how many evidence planes are there` is **two
  subjects, not one**: query executions, which lift here, and authorised
  effects, which need their own versioned record or an explicit version 2 —
  see `docs/ADR-execution-contract.md` on what a new field costs."
  (:require [clojure.set :as set]
            [kotobase.execution-contract :as contract]))

(def query-execution-planes
  "Planes whose records describe one query execution."
  #{:causal-disclosure :code-graph-query :governed-execution})

(def effect-planes
  "Planes whose records describe an authorised effect, not a query.

  Each value is why it cannot be a version 1 ExecutionReceipt."
  {:code-graph-execution
   "records that an artifact was built from an admitted code graph; it has a
   code root, a compiler contract and output roots, and no query plan or
   served result"
   :admission
   "records that an effect thunk (hydrate, execute, pin) was admitted against
   requested, delegated and local effect sets; it has no query at all"})

(def adapter-supplied
  "Fields the adapter itself answers, so they are neither carried nor missing."
  #{:receipt/version})

(def answerable
  "The fields a source plane or its supplement has to account for."
  (set/difference contract/receipt-keys adapter-supplied))

(defn- reject! [reason data]
  (throw (ex-info "evidence lift rejected"
                  (assoc data :kotobase.evidence/reason reason))))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- causal-disclosure-carried
  "What a committed causal disclosure receipt answers.

  Only the decision, and — when it is a denial — that there is no result. The
  disclosure binds an evaluated *row count*, which is a fact about how many
  rows there were and not about which rows they were, so it cannot answer
  `:result/root` for a served read."
  [record]
  (when-not (map? record) (reject! :unreadable-source {:plane :causal-disclosure}))
  (let [decision (get-in record [:causal.receipt/decision :decision/status])]
    (when-not (contains? #{:allow :deny} decision)
      (reject! :unreadable-source {:plane :causal-disclosure
                                   :field :causal.receipt/decision}))
    (cond-> {:authority/decision decision}
      (= :deny decision) (assoc :result/root nil))))

(defn- code-graph-query-carried
  "What a code-graph query receipt answers, read with its execution identity.

  The identity is not optional: the receipt has a result CID but the plan CID
  lives on the identity that binds it, and the binding is what makes the pair
  one execution rather than two records that mention the same basis. The write
  path checks this too; it is checked again here because a record only its
  author ever checked is checked once."
  [{:keys [receipt execution-identity] :as source}]
  (when-not (and (map? source)
                 (= #{:receipt :execution-identity} (set (keys source)))
                 (map? receipt) (map? execution-identity))
    (reject! :unreadable-source {:plane :code-graph-query}))
  (when-not (contains? (set (:host-receipt-cids execution-identity))
                       (:cid receipt))
    (reject! :receipt-not-bound-by-identity
             {:receipt-cid (:cid receipt)}))
  (when-not (= (:basis receipt) (:db-basis execution-identity))
    (reject! :basis-mismatch {:receipt (:basis receipt)
                              :identity (:db-basis execution-identity)}))
  (when-not (= (:policy-cid receipt) (:policy-cid execution-identity))
    (reject! :policy-mismatch {:receipt (:policy-cid receipt)
                               :identity (:policy-cid execution-identity)}))
  (when-not (and (non-empty-string? (:result-cid receipt))
                 (non-empty-string? (:plan-cid execution-identity)))
    (reject! :unreadable-source {:plane :code-graph-query}))
  ;; a query receipt exists only for a read that was authorised and served
  {:authority/decision :allow
   :result/root (:result-cid receipt)
   :query/plan-digest (:plan-cid execution-identity)})

(defn- governed-execution-carried
  "A receipt this repository already produced under the contract.

  It answers everything, so its supplement is empty. Present so that `lift`
  covers every plane and the empty supplement is a measurement rather than an
  omission."
  [record]
  (contract/validate-receipt! record)
  (select-keys record answerable))

(def ^:private carriers
  {:causal-disclosure causal-disclosure-carried
   :code-graph-query code-graph-query-carried
   :governed-execution governed-execution-carried})

(defn carried
  "The version 1 fields SOURCE actually answers, on the named PLANE."
  [plane source]
  (when-let [why (get effect-planes plane)]
    (reject! :not-a-query-execution {:plane plane :because why}))
  (if-let [carrier (get carriers plane)]
    (carrier source)
    (reject! :unknown-plane {:plane plane :known (set (keys carriers))})))

(defn missing
  "The version 1 fields SOURCE cannot answer — the supplement `lift` demands.

  Its size is the distance between that plane and the contract, and it is a
  function of the record: a denial carries `:result/root` (there is none) where
  a disclosure of served rows does not."
  [plane source]
  (set/difference answerable (set (keys (carried plane source)))))

(defn lift
  "Return SOURCE as a validated version 1 ExecutionReceipt, or throw.

  SUPPLEMENT must be exactly `(missing plane source)`. Supplying a field the
  source already answers is refused as laundering; omitting one it does not is
  refused as an incomplete lift. There is no arity that fills a gap silently."
  [plane source supplement]
  (let [carried (carried plane source)
        needed (set/difference answerable (set (keys carried)))]
    (when-not (map? supplement)
      (reject! :invalid-supplement {:plane plane}))
    (let [given (set (keys supplement))
          laundered (set/intersection given (set (keys carried)))]
      (when (seq laundered)
        (reject! :laundered-field {:plane plane :fields laundered}))
      (when-not (= given needed)
        (reject! :supplement-mismatch
                 {:plane plane
                  :missing (set/difference needed given)
                  :unexpected (set/difference given needed)})))
    (contract/validate-receipt!
     (merge {:receipt/version contract/version} carried supplement))))
