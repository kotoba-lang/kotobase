(ns kotobase.evidence
  "One evidence plane per subject, and an honest account of the distance to it.

  Receipts were written here before either contract existed:

  - `kotobase.causal-commit` / `kotobase.causal-trust` commit a **causal
    authority decision receipt**;
  - `kotobase.code-graph` persists a **query receipt** bound to an execution
    identity, and an **execution receipt** for one artifact build;
  - `kotobase.admission` requires a durable **audit receipt** before any
    effect thunk runs.

  Saying a contract is the evidence plane while four other planes keep being
  written is not a claim anyone can check, and the tempting way to make it
  true is a mapping function that fills in whatever the source does not carry
  — which produces the same number of planes and a lie.

  The rule that makes an adapter worth having is the one it cannot break: the
  supplement a caller passes must be **exactly** the fields the source record
  does not carry. A field the source answers may not be supplied — that is
  laundering, and it is refused by name — and a field the source lacks may not
  be omitted. So a lifted record is either genuinely derived or it does not
  exist, and *the size of the supplement is the measurement of how far a plane
  is from its contract*.

  ## Two subjects

  A version 1 ExecutionReceipt binds one **query execution** at one immutable
  basis: a plan digest and a result root are required. An admission decides
  whether an effect thunk may run; a code-graph execution receipt records that
  an artifact was built. Neither has either field, and inventing them is what
  this rule exists to prevent — so they lift onto
  `kotobase.effect-contract` instead, which binds an action, a resource, the
  code lock it ran under, and the effects granted.

  Both contracts share a vocabulary on purpose — policy snapshot, revocation
  epoch, request digest, cost, implementation build, signature — so two
  subjects do not mean two languages."
  (:require [clojure.set :as set]
            [kotobase.effect-contract :as effect]
            [kotobase.execution-contract :as contract]))

(def adapter-supplied
  "Fields the adapter itself answers, so they are neither carried nor missing."
  {:query-execution #{:receipt/version}
   :authorised-effect #{:effect/version}})

(def subjects
  "What a plane's records can be evidence *of*."
  {:query-execution
   {:version-key :receipt/version
    :version contract/version
    :fields contract/receipt-keys
    :validate contract/validate-receipt!}
   :authorised-effect
   {:version-key :effect/version
    :version effect/version
    :fields effect/receipt-keys
    :validate effect/validate-receipt!}})

(defn- reject! [reason data]
  (throw (ex-info "evidence lift rejected"
                  (assoc data :kotobase.evidence/reason reason))))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn answerable
  "The fields a source plane or its supplement has to account for."
  [subject]
  (set/difference (get-in subjects [subject :fields])
                  (get adapter-supplied subject)))

(defn- causal-decision-carried
  "What a committed causal authority decision receipt answers.

  Only the decision, and — when it is a denial — that there is no result. Its
  outcome binds a row count when it binds anything at all, which is a fact
  about how many rows there were and not about which rows they were. That gap
  is why the read path that used to write these is gone; what still writes
  them is authority persistence, where there is no result to name.

  A `:challenge` is neither an allow nor a deny — it is evidence still to be
  gathered — and version 1 has no third decision, so it is refused rather than
  flattened into one of the two."
  [record]
  (when-not (map? record) (reject! :unreadable-source {:plane :causal-decision}))
  (let [decision (get-in record [:causal.receipt/decision :decision/status])]
    (when-not (contains? #{:allow :deny} decision)
      (reject! :unreadable-source {:plane :causal-decision
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
  "A receipt this repository already produced under the execution contract.

  It answers everything, so its supplement is empty. Present so that `lift`
  covers every plane and the empty supplement is a measurement rather than an
  omission."
  [record]
  (contract/validate-receipt! record)
  (select-keys record (answerable :query-execution)))

(defn- governed-effect-carried
  "A receipt this repository already produced under the effect contract.

  It answers everything, so its supplement is empty — the same closing
  measurement `:governed-execution` provides for the other subject."
  [record]
  (effect/validate-receipt! record)
  (select-keys record (answerable :authorised-effect)))

(defn- admission-carried
  "What an admission decision answers about the effect it admitted.

  `kotobase.admission/decide` returns the action, the resource, the package
  lock the bytes were admitted under, the post-intersection granted set, and
  whether the thunk may run. It does not name what the thunk produced: the
  effect's result is returned to the caller and never bound into the audit
  record, so an admitted effect owes an outcome root and a refused one does
  not — there is nothing to name."
  [record]
  (when-not (and (map? record) (contains? record :admission/allowed?))
    (reject! :unreadable-source {:plane :admission}))
  (let [allowed? (:admission/allowed? record)]
    (when-not (boolean? allowed?)
      (reject! :unreadable-source {:plane :admission
                                   :field :admission/allowed?}))
    (when-not (and (keyword? (:admission/action record))
                   (non-empty-string? (:admission/resource record))
                   (non-empty-string? (:admission/package-lock-cid record))
                   (set? (:admission/granted record)))
      (reject! :unreadable-source {:plane :admission}))
    (cond-> {:authority/decision (if allowed? :allow :deny)
             :effect/action (:admission/action record)
             :effect/resource (:admission/resource record)
             :code/lock (:admission/package-lock-cid record)
             :effect/granted (:admission/granted record)}
      (not allowed?) (assoc :outcome/roots []))))

(defn- code-graph-execution-carried
  "What a code-graph execution receipt answers about the build it recorded.

  The action is a constant for the same reason the query receipt's decision is
  one: the record exists only because an artifact was built from an admitted
  code graph, so `:build` and `:allow` are what its existence means rather
  than fields it forgot to carry. Its output roots are the outcome."
  [record]
  (when-not (and (map? record)
                 (non-empty-string? (:artifact-cid record))
                 (non-empty-string? (:package-lock-cid record))
                 (non-empty-string? (:policy-cid record))
                 (set? (:granted-effects record))
                 (vector? (:output-root-cids record))
                 (seq (:output-root-cids record))
                 (every? non-empty-string? (:output-root-cids record)))
    (reject! :unreadable-source {:plane :code-graph-execution}))
  {:authority/decision :allow
   :effect/action :build
   :effect/resource (:artifact-cid record)
   :code/lock (:package-lock-cid record)
   :effect/granted (:granted-effects record)
   :authority/policy (:policy-cid record)
   :outcome/roots (:output-root-cids record)})

(def planes
  "Every plane this repository writes, and what its records are evidence of."
  {:causal-decision {:subject :query-execution :carry causal-decision-carried}
   :code-graph-query {:subject :query-execution :carry code-graph-query-carried}
   :governed-execution {:subject :query-execution
                        :carry governed-execution-carried}
   :governed-effect {:subject :authorised-effect
                     :carry governed-effect-carried}
   :admission {:subject :authorised-effect :carry admission-carried}
   :code-graph-execution {:subject :authorised-effect
                          :carry code-graph-execution-carried}})

(defn subject
  "Which contract PLANE's records are evidence under."
  [plane]
  (or (get-in planes [plane :subject])
      (reject! :unknown-plane {:plane plane :known (set (keys planes))})))

(defn carried
  "The version 1 fields SOURCE actually answers, on the named PLANE."
  [plane source]
  (subject plane)
  ((get-in planes [plane :carry]) source))

(defn missing
  "The fields SOURCE cannot answer — the supplement `lift` demands.

  Its size is the distance between that plane and its contract, and it is a
  function of the record: a denial carries an outcome (there is none) where an
  admitted effect does not."
  [plane source]
  (set/difference (answerable (subject plane))
                  (set (keys (carried plane source)))))

(defn lift
  "Return SOURCE as a validated version 1 record of its subject, or throw.

  SUPPLEMENT must be exactly `(missing plane source)`. Supplying a field the
  source already answers is refused as laundering; omitting one it does not is
  refused as an incomplete lift. There is no arity that fills a gap silently."
  [plane source supplement]
  (let [subject (subject plane)
        {:keys [version-key version validate]} (get subjects subject)
        carried (carried plane source)
        needed (set/difference (answerable subject) (set (keys carried)))]
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
    (validate (merge {version-key version} carried supplement))))
