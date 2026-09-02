(ns kotobase.authority-window
  "Is this request still allowed to run, right now.

  A contract can prove an expiry *field is present*. It cannot prove the
  request has not expired, that the revocation epoch it names is still
  current, or that its nonce is fresh — those need state the host owns. This
  namespace is where the host's answers are checked, and it refuses when they
  are absent: a missing nonce ledger is a refusal, not an allowance.

  It exists as its own namespace because two governed paths need it — a query
  execution and an authorised effect — and the instant comparison below is not
  something to have two copies of.

  Instants are RFC-3339 UTC only, compared through a nine-digit padded key.
  The naive string compare is wrong exactly where it matters: `\"…:00Z\"` sorts
  *after* `\"…:00.5Z\"`, so an unpadded compare reads a request that expires
  half a second later as having already expired. An instant this namespace
  cannot order is refused rather than ordered wrongly.")

(def window-keys
  "Exactly what a host must supply to decide whether a request may run."
  #{:now :epoch :consume-nonce!})

(def ^:private rfc3339
  #"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$")

(defn- reject! [reason data]
  (throw (ex-info "authority window rejected"
                  (assoc data :kotobase.authority-window/reason reason))))

(defn instant-key
  "Return an order-preserving key for one RFC-3339 UTC instant, or nil."
  [value]
  (when (string? value)
    (when-let [[_ seconds fraction] (re-matches rfc3339 value)]
      (str seconds "."
           (apply str (take 9 (concat (or fraction "") (repeat \0))))))))

(defn- before? [left right]
  (neg? (compare left right)))

(defn open!
  "Decide expiry, not-before, and epoch currency; return the nonce ledger.

  The ledger is returned rather than used so that a request already refused on
  time does not consume a nonce — otherwise a clock-skewed retry of a
  legitimate request is refused as a replay of itself.

  `:not-before` is optional and may be nil: a manifest's issue time when there
  is a manifest, nothing when there is not."
  [{:keys [authority expires-at epoch not-before]}]
  (when-not (and (map? authority) (= window-keys (set (keys authority))))
    (reject! :invalid-authority
             {:keys (when (map? authority) (set (keys authority)))}))
  (let [{:keys [now consume-nonce!]} authority
        current-epoch (:epoch authority)
        now-key (instant-key now)
        expires-key (instant-key expires-at)
        not-before-key (when (some? not-before) (instant-key not-before))]
    (when-not now-key (reject! :invalid-now {:now now}))
    (when-not expires-key (reject! :invalid-expiry {:expires-at expires-at}))
    (when (and (some? not-before) (nil? not-before-key))
      (reject! :invalid-not-before {:not-before not-before}))
    (when-not (nat-int? current-epoch) (reject! :invalid-current-epoch {}))
    (when-not (ifn? consume-nonce!) (reject! :missing-nonce-ledger {}))
    (when-not (before? now-key expires-key)
      (reject! :request-expired {:now now :expires-at expires-at}))
    (when (and not-before-key (before? now-key not-before-key))
      (reject! :not-yet-valid {:now now :not-before not-before}))
    (when-not (= current-epoch epoch)
      ;; the request names an epoch and the host names the current one. A
      ;; request signed under a superseded epoch is refused, not reinterpreted
      (reject! :authority-epoch-revoked
               {:request-epoch epoch :current-epoch current-epoch}))
    consume-nonce!))

(defn spent!
  "Require that a nonce ledger said, literally, that this nonce was fresh."
  [nonce verdict]
  (when-not (true? verdict)
    ;; anything that is not literally `true` is a replay: a ledger that could
    ;; not answer has not said the nonce is fresh
    (reject! :nonce-replayed {:nonce nonce}))
  true)
