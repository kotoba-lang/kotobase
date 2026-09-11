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
     one evaluated is the whole attack; `:query/digest` is checked against the
     address of the guest AST, `:tenant` and `:base/commit` against its scope,
     and `:authority/policy` against the policy CID the compiler returned.
  2. **runtime authority** is decided against wall time, the current
     revocation epoch, a nonce ledger, and a signature over the manifest.
     `validate-request!` proves an expiry *field is present*; it cannot prove
     the request has not expired, that the epoch it names is still current,
     that the nonce is fresh, or that the manifest was signed by anyone. None
     of those are answerable without state the host owns, so the host supplies
     it and this namespace refuses when it is absent — a missing nonce ledger
     is a refusal, not an allowance.
  3. the guarded read runs (`kotobase.guarded/read!`: policy, classified
     schema, grant, receipt sink).
  4. the **ExecutionReceipt** is built from what actually happened, and the
     identifiers in it are **computed, not accepted**. `:execution/manifest`
     is the address of the manifest, `:request/digest` the address of the
     envelope, `:result/root` the address of the rows that were served. An
     auditor holding those records can re-derive every one; a record edited in
     any field stops matching the receipt that cites it. Only the physical
     plan digest and the cost stay host-answered, because this layer cannot
     see a plan or a provider read.
  5. the receipt is signed, the signature is **verified before it is written**,
     and the receipt is **committed and read back** by the host sink. Only then
     do the rows return. This reuses `kotobase.guarded`'s own rule rather than
     adding one: the receipt sink it already demands is the sink that writes
     the contract record, so there is no ordering left to get wrong and no
     second receipt plane.

  A refusal produces a receipt too. The contract's stated purpose is that
  success and refusal share one evidence plane, which is only true if a denial
  is as durable as a disclosure — otherwise the cheapest way to leave no trace
  is to be refused.

  The address function is an argument, not a require: `kotobase.execution-
  identity` names the canonical one. What is checked here is that the argument
  behaves like an address — deterministic, insensitive to map entry order, and
  different for different values — which refuses a stub or a constant but not
  a codec that lies.

  `:cost` is asked for *after* evaluation, so it cannot be attested ahead of
  the work. `kotobase.metering` counts it at the storage seam — requests,
  bytes and the number of times the caller had to wait for an answer before
  it could ask the next question — leaving only `:cache-profile` as the
  caller's word, which that namespace marks rather than measures."
  (:require [kotobase.authority-window :as window]
            [kotobase.execution-contract :as contract]
            [kotobase.guarded :as guarded]))

(def ^:private execute-keys
  #{:request :manifest :authority :value-cid :verify
    :plan-digest :cost :implementation/build :sign :commit!
    :authorize! :schema :grant :query :evaluate!})

(defn- reject! [reason data]
  (throw (ex-info "governed execution rejected"
                  (assoc data :kotobase.governed-execution/reason reason))))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- non-empty-proof? [value]
  (or (non-empty-string? value)
      (and (coll? value) (seq value))))

(def instant-key
  "Re-exported from `kotobase.authority-window`, which owns the comparison.

  Kept here because callers and tests already name it and because the padding
  it does is the reason an expiry check is not a string compare."
  window/instant-key)

(def ^:private probe
  ;; two values that differ, and one of the two written in a different entry
  ;; order. An address function must agree with itself on the first, disagree
  ;; on the second, and be indifferent to the third
  {:same (array-map :a 1 :b [2 "three"] :c :four)
   :reordered (array-map :c :four :b [2 "three"] :a 1)
   :other (array-map :a 1 :b [2 "three"] :c :five)})

(defn- address-fn!
  "Refuse anything that is not behaving like a content address.

  Not a codec conformance check — `kotobase.execution-identity/conformant?`
  is that, and it names one canonical answer. This is the weaker property the
  composition actually depends on, and it is what stops a stub: a constant
  fails, a counter fails, and a function that hashes insertion order fails."
  [value-cid]
  (when-not (ifn? value-cid)
    (reject! :missing-host-function {:function :value-cid}))
  (let [answer (fn [k] (try (value-cid (get probe k))
                            (catch #?(:clj Exception :cljs :default) _ ::threw)))
        same (answer :same)]
    (when-not (and (non-empty-string? same)
                   (= same (answer :same))
                   (= same (answer :reordered))
                   (not= same (answer :other)))
      (reject! :not-an-address-function {}))
    value-cid))

(defn- validate-shape!
  [{:keys [value-cid plan-digest cost sign verify commit!] :as options}]
  (when-not (and (map? options) (= execute-keys (set (keys options))))
    (reject! :invalid-execution-options
             {:missing (vec (remove (set (keys options)) execute-keys))
              :unexpected (vec (remove execute-keys (keys options)))}))
  (when-not (non-empty-string? (:implementation/build options))
    (reject! :invalid-implementation-build {}))
  (doseq [[label f] [[:plan-digest plan-digest] [:cost cost] [:sign sign]
                     [:verify verify] [:commit! commit!]]]
    (when-not (ifn? f)
      (reject! :missing-host-function {:function label})))
  (address-fn! value-cid)
  options)

(defn- timing!
  "Decide expiry, manifest validity and epoch currency; return the ledger."
  [{:keys [request manifest authority]}]
  (window/open! {:authority authority
                 :expires-at (:expires-at request)
                 :epoch (:authority/epoch request)
                 :not-before (:issued-at manifest)}))

(defn- nonce-verdict! [request verdict]
  (window/spent! (:nonce request) verdict))

(defn- signature-request
  "What the host is asked to verify: a record's address, its proof, and the
  authority context this execution is running under.

  The address is of the record *without* its signature, because a signature
  cannot be inside the bytes it signs.

  `:tenant` and `:epoch` come from the envelope being executed rather than
  from whatever the verifier was built with. A verifier that closed over its
  own idea of them could be handed a request for a different tenant or a
  superseded epoch and never notice; told by the executor, it cannot. What it
  does with them — which keys are registered for that tenant at that epoch —
  is `kotobase.execution-keys`' business, not this namespace's."
  [value-cid request kind record]
  {:record kind
   :payload-cid (value-cid (dissoc record :signature))
   :signature (:signature record)
   :tenant (:tenant request)
   :epoch (:authority/epoch request)})

(defn- verified! [kind verdict]
  (when-not (true? verdict)
    ;; a verifier that returned nil, false, or something it could not decide
    ;; has not said this signature is good
    (reject! :signature-not-verified {:record kind}))
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
  "The envelope's semantic digest must be the address of the query itself.

  Computed here rather than asked for: an envelope is evidence about a query
  nobody ran unless the digest in it is one anybody can re-derive."
  [request digest]
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

(defn- identities
  "The addresses this execution is about, computed once from the records."
  [{:keys [value-cid manifest request]}]
  {:manifest-cid (value-cid manifest)
   :request-digest (value-cid request)})

(defn- unsigned-receipt
  [options ids plan-digest decision result-root cost]
  {:receipt/version contract/version
   :request/digest (:request-digest ids)
   :execution/manifest (:manifest-cid ids)
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

(defn- validated-bundle
  [{:keys [request manifest]} ids execution-receipt]
  (contract/validate-execution! {:manifest manifest
                                 :manifest-cid (:manifest-cid ids)
                                 :request request
                                 :request-digest (:request-digest ids)
                                 :receipt execution-receipt})
  execution-receipt)

(defn- durable-ack! [ack]
  (when-not (and (map? ack)
                 (true? (:receipt/durable? ack))
                 (non-empty-string? (:receipt/cid ack)))
    (reject! :execution-receipt-not-durable {:ack ack}))
  ack)

(defn- authority-refusal?
  "Only the policy layers' own refusals, and only before admission, become
  deny receipts.

  Both halves are load bearing. An evaluator that crashed is not an authority
  decision, and recording it as one would put `:deny` in the evidence plane
  for something policy never decided. But the reason key alone is not enough:
  `kotobase.authorized-query` also raises `:kotobase.query/reason` *after*
  the rows exist — for a result that is not a vector, or a receipt
  acknowledgement it will not accept — and those are plumbing failures, not
  refusals. Admission is entirely before evaluation, so `admitted?` separates
  them exactly."
  [error admitted?]
  (let [data (ex-data error)]
    (boolean (and (not admitted?)
                  (or (:kotobase.guarded/reason data)
                      (:kotobase.query/reason data))))))

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
  before any row is read, and so that reaching it at all is recorded: past
  this point a failure is not a denial."
  [{:keys [authorize! schema grant query evaluate! request]} sink admitted]
  {:authorize! authorize!
   :schema schema
   :grant grant
   :query query
   :evaluate! (fn [compiled-query decision]
                (reset! admitted true)
                (bind-decision! request decision)
                (evaluate! compiled-query decision))
   :receipt! sink})

(defn execute!
  "Run one governed execution; return the guarded read plus its receipt.

  Rows are returned only after the ExecutionReceipt has been signed, its
  signature verified, and the record committed and read back by `:commit!`. A
  policy refusal commits a deny receipt and then rethrows, with the original
  refusal as the cause."
  [{:keys [request manifest query value-cid verify plan-digest cost sign
           commit!]
    :as options}]
  (validate-shape! options)
  (contract/validate-request! request)
  (contract/validate-manifest! manifest)
  (let [ids (identities options)
        consume-nonce! (timing! options)
        receipt! (fn [ids plan decision result-root]
                   (let [unsigned (unsigned-receipt options ids plan decision
                                                    result-root (cost))
                         signed (signed! unsigned (sign unsigned))]
                     (verified! :execution-receipt
                                (verify (signature-request value-cid request
                                                           :execution-receipt
                                                           signed)))
                     (validated-bundle options ids signed)))]
    (bind-scope! request query)
    (bind-digest! request (value-cid query))
    (verified! :execution-manifest
               (verify (signature-request value-cid request
                                          :execution-manifest manifest)))
    (nonce-verdict! request (consume-nonce! (:nonce request)))
    (let [captured (atom nil)
          admitted (atom false)
          sink (fn [{:keys [compiled rows]}]
                 (let [signed (receipt! ids
                                        (checked-plan-digest
                                         (plan-digest compiled))
                                        :allow (value-cid rows))
                       ack (durable-ack! (commit! signed))]
                   (reset! captured signed)
                   ack))
          result (try
                   (guarded/read! (guarded-request options sink admitted))
                   (catch #?(:clj Exception :cljs :default) error
                     (if (authority-refusal? error @admitted)
                       ;; a refusal has no compiled query, so the host is
                       ;; asked for the digest of the absence of a plan
                       ;; rather than having one fabricated for it
                       (let [signed (receipt! ids
                                              (checked-plan-digest
                                               (plan-digest nil))
                                              :deny nil)]
                         (refused! error (durable-ack! (commit! signed))))
                       (throw error))))]
      (assoc result :execution/receipt @captured))))

#?(:cljs
   (defn- then [value f]
     (.then (js/Promise.resolve value) f)))

#?(:cljs
   (defn- receipt-async
     "Chain the host's answers — cost, signature, verification — into one
     validated receipt.

     Each may be a Promise: in a Worker a signature and its verification are
     both `crypto.subtle` calls, so a synchronous-only path would force the
     host to precompute them, which is exactly the shape this namespace
     refuses. The address function is not among them; a canonical value codec
     is a pure function, and one that needs I/O is not one."
     [{:keys [value-cid sign verify] :as options} ids plan-digest decision
      result-root]
     (-> (then ((:cost options))
               #(unsigned-receipt options ids plan-digest decision result-root %))
         (.then (fn [unsigned]
                  (then (sign unsigned) #(signed! unsigned %))))
         (.then (fn [signed]
                  (-> (then (verify (signature-request value-cid
                                                       (:request options)
                                                       :execution-receipt
                                                       signed))
                            #(verified! :execution-receipt %))
                      (.then (fn [_] (validated-bundle options ids signed)))))))))

#?(:cljs
   (defn- commit-async! [options signed]
     (-> (then ((:commit! options) signed) durable-ack!)
         (.then (fn [ack] {:ack ack :receipt signed})))))

#?(:cljs
   (defn execute-async!
     "The Worker path. Every host answer may be a Promise and each is awaited.

     Rows are not returned while policy, evaluation, the nonce ledger, the
     signature, its verification, or the receipt commit is still in flight."
     [{:keys [request manifest query value-cid verify plan-digest] :as options}]
     (try
       (validate-shape! options)
       (contract/validate-request! request)
       (contract/validate-manifest! manifest)
       (let [ids (identities options)
             consume-nonce! (timing! options)
             captured (atom nil)
             admitted (atom false)
             sink (fn [{:keys [compiled rows]}]
                    (-> (then (plan-digest compiled)
                              #(receipt-async options ids
                                              (checked-plan-digest %)
                                              :allow (value-cid rows)))
                        (.then #(commit-async! options %))
                        (.then (fn [{:keys [ack receipt]}]
                                 (reset! captured receipt)
                                 ack))))]
         (bind-scope! request query)
         (bind-digest! request (value-cid query))
         (-> (then (verify (signature-request value-cid request
                                              :execution-manifest manifest))
                   #(verified! :execution-manifest %))
             (.then (fn [_] (consume-nonce! (:nonce request))))
             (.then #(nonce-verdict! request %))
             (.then (fn [_] (guarded/read-async!
                             (guarded-request options sink admitted))))
             (.then (fn [result] (assoc result :execution/receipt @captured)))
             (.catch
              (fn [error]
                (if (authority-refusal? error @admitted)
                  (-> (then (plan-digest nil)
                            #(receipt-async options ids
                                            (checked-plan-digest %)
                                            :deny nil))
                      (.then #(commit-async! options %))
                      (.then (fn [{:keys [ack]}] (refused! error ack))))
                  (js/Promise.reject error))))))
       (catch :default error
         (js/Promise.reject error)))))
