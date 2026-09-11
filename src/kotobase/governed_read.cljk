(ns kotobase.governed-read
  "The one read path that serves rows and leaves evidence.

  Until now there were two. `kotobase.causal-commit/read!` admitted a query,
  evaluated it and committed a **causal disclosure receipt** before returning
  rows; `kotobase.governed-execution/execute!` did the same and committed an
  **ExecutionReceipt**. Both were correct about withholding rows until a
  receipt was durable, and they wrote different records about the same event.

  `kotobase.evidence` measured the difference rather than asserting it: a
  served disclosure answers one of the eight fields of a version 1 receipt.
  Seven supplied is not evidence of an execution, and the fix for that was
  never a better adapter — it was to stop writing the record that cannot
  answer them. So the disclosure read path is gone and this is its successor.

  What it adds over calling `governed-execution/execute!` directly is the two
  things the retired path did that the contract does not describe:

  - the **authority decision binding**. The retired path checked two things
    the contract does not describe: that the trust decision being exercised
    was an allow — *a challenge is evidence to gather, never permission to
    disclose* — and that the runtime capability in it was a read, for this
    tenant, over exactly these resources. The contract has no resource field;
    scoping a read is the policy compiler's job and `kotobase.guarded` already
    refuses a projection outside the grant. But a trust decision is a separate
    authority from a query policy, and dropping its check while deleting the
    path around it would have been a regression wearing a consolidation's
    clothes. Both are checked here, and the capability is bound to the *signed
    envelope's* principal rather than to a receipt template's, which the
    retired path could not do.
  - the **sink**. The canonical CID commit is wired up rather than assembled
    by every caller, so the easy way to call this is also the one that reads
    its receipt back before releasing rows."
  (:require [kotobase.causal-commit :as causal]
            [kotobase.governed-execution :as governed]))

(def ^:private read-keys
  #{:commit :authority-decision
    :request :manifest :authority :value-cid :verify
    :plan-digest :cost :implementation/build :sign
    :authorize! :schema :grant :query :evaluate!})

(defn- reject! [reason data]
  (throw (ex-info "governed read rejected"
                  (assoc data :kotobase.governed-read/reason reason))))

(defn- require-authority!
  "Bind the trust decision being exercised to the query and the envelope.

  The decision is `grant.causal-trust/decide`'s shape, so its key set is not
  pinned here: a field added to it for some other purpose is not a reason to
  refuse a read. The five facts this path depends on are checked exactly."
  [{:keys [authority-decision request query]}]
  (when-not (map? authority-decision)
    (reject! :missing-authority-decision {}))
  (when-not (= :allow (:decision/status authority-decision))
    ;; a challenge is evidence still to be gathered, not permission
    (reject! :decision-not-allowed
             {:status (:decision/status authority-decision)}))
  (let [capability (:decision/runtime-capability-spec authority-decision)]
    (when-not (map? capability)
      (reject! :missing-capability {}))
    (when-not (= :object/read (:capability/action capability))
      (reject! :read-capability-required
               {:action (:capability/action capability)}))
    (when-not (= (get-in query [:scope :resources])
                 (:capability/resource capability))
      (reject! :query-resource-mismatch
               {:query (get-in query [:scope :resources])
                :capability (:capability/resource capability)}))
    (when-not (= (get-in query [:scope :tenant])
                 (:capability/tenant capability))
      (reject! :query-tenant-mismatch
               {:query (get-in query [:scope :tenant])
                :capability (:capability/tenant capability)}))
    (when-not (= (:principal request) (:capability/principal capability))
    ;; the envelope is the record whose digest the receipt names, so it is the
    ;; one the capability has to be about
      (reject! :principal-mismatch
               {:envelope (:principal request)
                :capability (:capability/principal capability)}))
    capability))

(defn- execution
  "The `governed-execution/execute!` request, with the canonical sink wired in.

  There is deliberately no `:commit!` escape hatch: a caller that wants a
  different sink is choosing a different evidence plane, and that choice
  should be visible as a call to `governed-execution` rather than as an option
  here."
  [{:keys [commit] :as request}]
  (-> request
      (dissoc :commit :authority-decision)
      (assoc :commit! (causal/execution-receipt-sink (:database commit)
                                                     (dissoc commit :database)))))

(defn- validated! [request]
  (when-not (and (map? request) (= read-keys (set (keys request))))
    (reject! :invalid-read-options
             {:missing (vec (remove (set (keys request)) read-keys))
              :unexpected (vec (remove read-keys (keys request)))}))
  (when-not (map? (:commit request))
    (reject! :invalid-commit-options {}))
  (require-authority! request)
  request)

(defn read!
  "Admit, evaluate, commit the ExecutionReceipt, and only then return rows."
  [request]
  (governed/execute! (execution (validated! request))))

#?(:cljs
   (defn read-async!
     "The Worker path. Rows are withheld while anything is still in flight."
     [request]
     (try
       (governed/execute-async! (execution (validated! request)))
       (catch :default error
         (js/Promise.reject error)))))
