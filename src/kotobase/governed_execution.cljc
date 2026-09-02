(ns kotobase.governed-execution
  "The read path that produces cross-protocol execution evidence.

  `kotobase.execution-contract` defined three exact records and validated
  their shape. Nothing called it. A contract with no caller is a schema, and a
  schema is not a security boundary — the boundary is the place a read cannot
  get past without producing the evidence, and until now that place did not
  exist.

  This namespace is that place. One entry composes, in this order, with no
  arity that skips a step:

  1. the **RequestEnvelope** is validated and then *bound to the query that
     will actually run*. A signed envelope naming a different query than the
     one evaluated is the whole attack; `:query/digest`, `:tenant`, and
     `:base/commit` are checked against the guest AST before admission, and
     `:authority/policy` against the policy CID the compiler actually
     returned.
  2. **runtime authority** is decided against wall time, the current
     revocation epoch, and a nonce ledger. `validate-request!` proves an
     expiry *field is present*; it cannot prove the request has not expired,
     that the epoch it names is still current, or that the nonce is fresh.
     None of those are answerable without state the host owns, so the host
     supplies it and this namespace refuses when it is absent — a missing
     nonce ledger is a refusal, not an allowance.
  3. the guarded read runs (`kotobase.guarded/read!`: policy, classified
     schema, grant, receipt sink).
  4. the **ExecutionReceipt** is built from what actually happened — a result
     root computed from the rows, a plan digest from the compiled query, a
     cost read *after* evaluation — signed, and cross-checked against the
     manifest and the request by `validate-execution!`.
  5. the receipt is **committed and read back** by the host sink, and only
     then do the rows return. This reuses `kotobase.guarded`'s own rule rather
     than adding one: the receipt sink it already demands is the sink that
     writes the contract record, so there is no ordering left to get wrong and
     no second receipt plane.

  A refusal produces a receipt too. The contract's stated purpose is that
  success and refusal share one evidence plane, which is only true if a denial
  is as durable as a disclosure — otherwise the cheapest way to leave no trace
  is to be refused.

  **Not yet evidence: `:cost`.** It comes from a meter the host is asked for
  *after* evaluation rather than a value the caller declares before it, so it
  cannot be attested ahead of the work — but it is still the host's number.
  Measuring dependent hops, provider requests, and bytes from the pack reads
  themselves is separate, unfinished work; until it lands, a cost in a receipt
  written here says what the host claims it spent."
  (:require [kotobase.execution-contract :as contract]
            [kotobase.guarded :as guarded]))

(def ^:private execute-keys
  #{:request :request-digest :manifest :manifest-cid :authority
    :query-digest :plan-digest :cost :implementation/build
    :result-root :sign :commit!
    :authorize! :schema :grant :query :evaluate!})

(def ^:private authority-keys
  #{:now :epoch :consume-nonce!})

(defn- reject! [reason data]
  (throw (ex-info "governed execution rejected"
                  (assoc data :kotobase.governed-execution/reason reason))))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- non-empty-proof? [value]
  (or (non-empty-string? value)
      (and (coll? value) (seq value))))

(def ^:private rfc3339
  ;; UTC, second precision, optional fraction. Deliberately narrow: this
  ;; namespace compares instants as strings, and a string comparison is only
  ;; an instant comparison inside one exact format. Offsets, lower-case `z`,
  ;; and absent seconds are refused rather than approximated.
  #"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$")

(defn instant-key
  "Return an order-preserving key for one RFC-3339 UTC instant, or nil.

  The fraction is padded to nine digits because the naive comparison is wrong
  exactly where it matters: `\"…:00Z\"` sorts *after* `\"…:00.5Z\"`, so an
  unpadded compare reads a request that expires half a second later as having
  already expired."
  [value]
  (when (string? value)
    (when-let [[_ seconds fraction] (re-matches rfc3339 value)]
      (str seconds "."
           (apply str (take 9 (concat (or fraction "") (repeat \0))))))))

(defn- before? [left right]
  (neg? (compare left right)))

(defn- validate-shape!
  [{:keys [request-digest manifest-cid query-digest plan-digest cost
           result-root sign commit!]
    :as options}]
  (when-not (and (map? options) (= execute-keys (set (keys options))))
    (reject! :invalid-execution-options
             {:missing (vec (remove (set (keys options)) execute-keys))
              :unexpected (vec (remove execute-keys (keys options)))}))
  (when-not (non-empty-string? request-digest)
    (reject! :invalid-request-digest {}))
  (when-not (non-empty-string? manifest-cid)
    (reject! :invalid-manifest-cid {}))
  (when-not (non-empty-string? (:implementation/build options))
    (reject! :invalid-implementation-build {}))
  (doseq [[label f] [[:query-digest query-digest] [:plan-digest plan-digest]
                     [:cost cost] [:result-root result-root]
                     [:sign sign] [:commit! commit!]]]
    (when-not (ifn? f)
      (reject! :missing-host-function {:function label})))
  options)

(defn- timing!
  "Decide expiry, manifest validity, and epoch currency; return the ledger.

  The nonce ledger is returned rather than used here so that a request already
  refused on time does not consume a nonce."
  [{:keys [request manifest authority]}]
  (when-not (and (map? authority) (= authority-keys (set (keys authority))))
    (reject! :invalid-authority
             {:keys (when (map? authority) (set (keys authority)))}))
  (let [{:keys [now epoch consume-nonce!]} authority
        now-key (instant-key now)
        expires-key (instant-key (:expires-at request))
        issued-key (instant-key (:issued-at manifest))]
    (when-not now-key (reject! :invalid-now {:now now}))
    (when-not expires-key
      (reject! :invalid-expiry {:expires-at (:expires-at request)}))
    (when-not issued-key
      (reject! :invalid-issued-at {:issued-at (:issued-at manifest)}))
    (when-not (nat-int? epoch) (reject! :invalid-current-epoch {}))
    (when-not (ifn? consume-nonce!) (reject! :missing-nonce-ledger {}))
    (when-not (before? now-key expires-key)
      (reject! :request-expired {:now now :expires-at (:expires-at request)}))
    (when (before? now-key issued-key)
      (reject! :manifest-not-yet-issued
               {:now now :issued-at (:issued-at manifest)}))
    (when-not (= epoch (:authority/epoch request))
      ;; the request names an epoch and the host names the current one. A
      ;; request signed under a superseded epoch is refused, not reinterpreted
      (reject! :authority-epoch-revoked
               {:request-epoch (:authority/epoch request)
                :current-epoch epoch}))
    consume-nonce!))

(defn- nonce-verdict! [request verdict]
  (when-not (true? verdict)
    ;; anything that is not literally `true` is a replay: a ledger that could
    ;; not answer has not said the nonce is fresh
    (reject! :nonce-replayed {:nonce (:nonce request)}))
  true)

(defn- bind-scope!
  "Tie the signed envelope to the scope of the guest AST about to be admitted."
  [request query]
  (when-not (= (get-in query [:scope :tenant]) (:tenant request))
    (reject! :tenant-mismatch {:envelope (:tenant request)
                               :query (get-in query [:scope :tenant])}))
  (when-not (= (get-in query [:scope :basis]) (:base/commit request))
    (reject! :basis-mismatch {:envelope (:base/commit request)
                              :query (get-in query [:scope :basis])})))

(defn- bind-digest!
  "Without this the envelope is evidence about a query nobody ran."
  [request digest]
  (when-not (non-empty-string? digest)
    (reject! :invalid-query-digest {}))
  (when-not (= digest (:query/digest request))
    (reject! :query-digest-mismatch
             {:envelope (:query/digest request) :evaluated digest}))
  digest)

(defn- bind-decision!
  "Tie the envelope to the policy the compiler actually applied.

  Checked between admission and evaluation, not after it: an envelope that
  names a policy snapshot the compiler did not use describes an execution that
  should not happen, and refusing it once the rows exist is late."
  [request decision]
  (when-not (= (:policy-cid decision) (:authority/policy request))
    (reject! :policy-mismatch {:envelope (:authority/policy request)
                               :compiled (:policy-cid decision)}))
  (when-not (= (:basis decision) (:base/commit request))
    (reject! :basis-mismatch {:envelope (:base/commit request)
                              :compiled (:basis decision)}))
  decision)

(defn- unsigned-receipt
  [{:keys [request-digest manifest-cid] :as options} plan-digest decision
   result-root cost]
  {:receipt/version contract/version
   :request/digest request-digest
   :execution/manifest manifest-cid
   :query/plan-digest plan-digest
   :authority/decision decision
   :result/root result-root
   :cost cost
   :implementation/build (:implementation/build options)})

(defn- signed! [unsigned proof]
  (when-not (non-empty-proof? proof)
    (reject! :invalid-signature {}))
  (assoc unsigned :signature proof))

(defn- checked-plan-digest [digest]
  (when-not (non-empty-string? digest)
    (reject! :invalid-plan-digest {}))
  digest)

(defn- checked-result-root [root]
  (when-not (non-empty-string? root)
    (reject! :invalid-result-root {}))
  root)

(defn- validated-bundle
  [{:keys [request request-digest manifest manifest-cid]} execution-receipt]
  (contract/validate-execution! {:manifest manifest
                                 :manifest-cid manifest-cid
                                 :request request
                                 :request-digest request-digest
                                 :receipt execution-receipt})
  execution-receipt)

(defn- durable-ack! [ack]
  (when-not (and (map? ack)
                 (true? (:receipt/durable? ack))
                 (non-empty-string? (:receipt/cid ack)))
    (reject! :execution-receipt-not-durable {:ack ack}))
  ack)

(defn- authority-refusal?
  "Only the policy layers' own refusals become deny receipts.

  An evaluator that crashed is not an authority decision, and recording it as
  one would put `:deny` in the evidence plane for something policy never
  decided."
  [error]
  (let [data (ex-data error)]
    (boolean (or (:kotobase.guarded/reason data)
                 (:kotobase.query/reason data)))))

(defn- refused! [error ack]
  (throw (ex-info "governed execution refused"
                  {:kotobase.governed-execution/reason :authority-denied
                   :kotobase.governed-execution/deny-receipt-cid (:receipt/cid ack)
                   :kotobase.governed-execution/cause (ex-data error)}
                  error)))

(defn- guarded-request
  "The `kotobase.guarded/read!` request, with SINK as its only receipt sink.

  The caller's own `:receipt!` never reaches the guarded path: the contract
  record *is* the receipt, so there is no second plane to keep in step. The
  evaluator is wrapped so the compiled decision is bound to the envelope
  before any row is read."
  [{:keys [authorize! schema grant query evaluate! request]} sink]
  {:authorize! authorize!
   :schema schema
   :grant grant
   :query query
   :evaluate! (fn [compiled-query decision]
                (bind-decision! request decision)
                (evaluate! compiled-query decision))
   :receipt! sink})

(defn execute!
  "Run one governed execution; return the guarded read plus its receipt.

  Rows are returned only after the ExecutionReceipt has been committed and
  read back by `:commit!`. A policy refusal commits a deny receipt and then
  rethrows, with the original refusal as the cause."
  [{:keys [request query query-digest plan-digest cost result-root sign
           commit!]
    :as options}]
  (validate-shape! options)
  (contract/validate-request! request)
  (contract/validate-manifest! (:manifest options))
  (let [consume-nonce! (timing! options)]
    (bind-scope! request query)
    (bind-digest! request (query-digest query))
    (nonce-verdict! request (consume-nonce! (:nonce request)))
    (let [captured (atom nil)
          sink (fn [{:keys [compiled rows]}]
                 (let [signed (validated-bundle
                               options
                               (let [unsigned (unsigned-receipt
                                               options
                                               (checked-plan-digest
                                                (plan-digest compiled))
                                               :allow
                                               (checked-result-root
                                                (result-root rows))
                                               (cost))]
                                 (signed! unsigned (sign unsigned))))
                       ack (durable-ack! (commit! signed))]
                   (reset! captured signed)
                   ack))
          result (try
                   (guarded/read! (guarded-request options sink))
                   (catch #?(:clj Exception :cljs :default) error
                     (if (authority-refusal? error)
                       (let [unsigned (unsigned-receipt
                                       options
                                       ;; a refusal has no compiled query, so
                                       ;; the host is asked for the digest of
                                       ;; the absence of a plan rather than
                                       ;; having one fabricated for it
                                       (checked-plan-digest (plan-digest nil))
                                       :deny nil (cost))
                             signed (validated-bundle
                                     options (signed! unsigned (sign unsigned)))]
                         (refused! error (durable-ack! (commit! signed))))
                       (throw error))))]
      (assoc result :execution/receipt @captured))))

#?(:cljs
   (defn- then [value f]
     (.then (js/Promise.resolve value) f)))

#?(:cljs
   (defn- receipt-async
     "Chain the host's answers — cost, then signature — into one receipt.

     Each may be a Promise: in a Worker a digest and a signature are both
     `crypto.subtle` calls, so a synchronous-only path would force the host to
     precompute them, which is exactly the shape this namespace refuses."
     [options plan-digest decision result-root]
     (-> (then ((:cost options))
               #(unsigned-receipt options plan-digest decision result-root %))
         (.then (fn [unsigned]
                  (then ((:sign options) unsigned)
                        #(signed! unsigned %)))))))

#?(:cljs
   (defn- commit-async! [options signed]
     (-> (then ((:commit! options) signed) durable-ack!)
         (.then (fn [ack] {:ack ack :receipt signed})))))

#?(:cljs
   (defn execute-async!
     "The Worker path. Every host answer may be a Promise and each is awaited.

     Rows are not returned while policy, evaluation, the nonce ledger, or the
     receipt commit is still in flight."
     [{:keys [request query query-digest plan-digest result-root] :as options}]
     (try
       (validate-shape! options)
       (contract/validate-request! request)
       (contract/validate-manifest! (:manifest options))
       (let [consume-nonce! (timing! options)
             captured (atom nil)
             sink (fn [{:keys [compiled rows]}]
                    (-> (js/Promise.all
                         #js [(js/Promise.resolve (plan-digest compiled))
                              (js/Promise.resolve (result-root rows))])
                        (.then
                         (fn [pair]
                           (receipt-async options
                                          (checked-plan-digest (aget pair 0))
                                          :allow
                                          (checked-result-root (aget pair 1)))))
                        (.then #(validated-bundle options %))
                        (.then #(commit-async! options %))
                        (.then (fn [{:keys [ack receipt]}]
                                 (reset! captured receipt)
                                 ack))))]
         (bind-scope! request query)
         (-> (then (query-digest query) #(bind-digest! request %))
             (.then (fn [_] (consume-nonce! (:nonce request))))
             (.then #(nonce-verdict! request %))
             (.then (fn [_] (guarded/read-async!
                             (guarded-request options sink))))
             (.then (fn [result] (assoc result :execution/receipt @captured)))
             (.catch
              (fn [error]
                (if (authority-refusal? error)
                  (-> (then (plan-digest nil)
                            #(receipt-async options (checked-plan-digest %)
                                            :deny nil))
                      (.then #(validated-bundle options %))
                      (.then #(commit-async! options %))
                      (.then (fn [{:keys [ack]}] (refused! error ack))))
                  (js/Promise.reject error))))))
       (catch :default error
         (js/Promise.reject error)))))
