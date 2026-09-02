(ns kotobase.metering
  "What one execution actually spent, observed at the storage seam.

  `:cost` in an ExecutionReceipt was the only field left that the host simply
  asserted. Asking for it *after* evaluation meant it could not be attested
  before the work, which is better than a declaration, but it was still a
  number nobody counted.

  This counts it. A metered backend sits between `kotobase.core` and the
  provider and records every block read that passes through it, so the cost in
  a receipt is a fact about the reads the engine performed rather than a claim
  about them.

  Three of the four fields are measured exactly:

  - `:requests` — how many times the provider was asked for blocks.
  - `:bytes` — how many bytes came back.
  - `:dependent-hops` — how many times the caller had to **wait for an answer
    before it could ask the next question**. A round begins when a call is
    issued while none is outstanding, so a batch of concurrent reads is one
    hop and a serial chain of ten is ten. This is the quantity a pack layout
    is supposed to reduce (ADR-2608160100 in the workspace root: `the success
    metric is round trip count`), and it is the one a wall clock on a loaded
    machine cannot tell you.

  The fourth, `:cache-profile`, cannot be measured by **one** meter: a cache in
  front of the decorator is invisible to it and a cache behind it is the
  provider's business, so a single wrapper would be reporting its own position.
  It takes two. Put one meter above a cache and one below it, open a
  `tiered-span`, and how much the cache absorbed is the difference between what
  the engine asked for and what reached the provider — `:hot` when nothing did,
  `:cold` when everything did, `:warm` in between.

  With one meter the profile stays the caller's word, and `:unmeasured` is the
  honest value for it. `span` takes it as an argument for that reason: a field
  that is going to be guessed should be guessed somewhere visible.

  A meter observes; it does not decide. Nothing here refuses a read."
  (:require [kotobase.storage.core :as storage]))

(defn- reject! [reason data]
  (throw (ex-info "metering rejected" (assoc data :kotobase.metering/reason reason))))

(defn- byte-count [value]
  #?(:clj (cond
            (bytes? value) (alength ^bytes value)
            (string? value) (count value)
            :else 0)
     :cljs (cond
             (or (instance? js/Uint8Array value) (instance? js/Int8Array value))
             (.-length value)
             (string? value) (count value)
             :else 0)))

(defn- returned-bytes [result]
  (reduce + 0 (map byte-count (vals result))))

#?(:clj (defn- thenable? [_value] false)
   :cljs (defn- thenable? [value]
           (and (some? value) (fn? (.-then value)))))

(defn- start! [state]
  (swap! state (fn [{:keys [outstanding] :as s}]
                 (-> s
                     (update :requests inc)
                     ;; a round begins only when nothing was in flight: that is
                     ;; exactly the moment the caller had to wait for an answer
                     ;; before it could ask this question
                     (cond-> (zero? outstanding) (update :dependent-hops inc))
                     (update :outstanding inc)))))

(defn- finish! [state result]
  (swap! state (fn [s]
                 (-> s
                     (update :outstanding dec)
                     (update :bytes + (returned-bytes result)))))
  result)

(defn meter
  "Wrap BACKEND so every block read through it is counted.

  Returns `{:backend … :read (fn [] totals)}`. The wrapper delegates the whole
  storage contract; ref reads and block writes are passed through uncounted,
  because a receipt's cost is about the reads that produced the rows and the
  write it is about is the receipt's own commit."
  [backend]
  (when-not (satisfies? storage/IBlockStore backend)
    (reject! :not-a-block-store {}))
  (let [state (atom {:requests 0 :dependent-hops 0 :bytes 0 :outstanding 0})]
    {:read (fn [] (select-keys @state [:requests :dependent-hops :bytes]))
     :backend
     (reify
       storage/IBlockStore
       (-put-blocks! [_ blocks] (storage/-put-blocks! backend blocks))
       (-get-blocks [_ cids]
         (start! state)
         (let [result (storage/-get-blocks backend cids)]
           (if (thenable? result)
             #?(:clj result
                :cljs (.then result #(finish! state %)))
             (finish! state result))))

       storage/IRefStore
       (-read-ref [_ ref-name] (storage/-read-ref backend ref-name))
       (-compare-and-set-ref! [_ ref-name expected next]
         (storage/-compare-and-set-ref! backend ref-name expected next))

       storage/IBackendCapabilities
       (-capabilities [_] (storage/-capabilities backend)))}))

(defn- delta [opened now]
  {:requests (- (:requests now) (:requests opened))
   :dependent-hops (- (:dependent-hops now) (:dependent-hops opened))
   :bytes (- (:bytes now) (:bytes opened))})

(defn cache-profile
  "How much of what the engine asked for never reached the provider.

  ABOVE is what was asked of the cache, BELOW what the cache had to fetch.
  This is a description of one window, not of a deployment: the same cache is
  cold on the read that fills it and hot on the next one, which is what makes
  the field worth measuring per execution rather than configuring per host."
  [above below]
  (let [asked (:requests above)
        fetched (:requests below)]
    (cond
      (not (and (nat-int? asked) (nat-int? fetched) (<= fetched asked)))
      ;; below cannot exceed above unless the two meters are not on the same
      ;; path, and a number derived from meters that disagree is not a
      ;; measurement
      (reject! :meters-not-in-line {:above asked :below fetched})

      (zero? asked) :no-reads
      (zero? fetched) :hot
      (= fetched asked) :cold
      :else :warm)))

(defn tiered-span
  "Open one window across a cache and the provider behind it.

  ABOVE meters what the engine asks for; BELOW meters what the cache could not
  answer. The counts in the receipt are the provider's — that is what the
  execution actually cost — and the profile is what the cache absorbed."
  [{:keys [above below] :as meters}]
  (when-not (and (map? meters) (= #{:above :below} (set (keys meters)))
                 (ifn? (:read above)) (ifn? (:read below)))
    (reject! :not-a-meter-pair {}))
  (let [opened-above ((:read above))
        opened-below ((:read below))]
    (fn []
      (let [asked (delta opened-above ((:read above)))
            fetched (delta opened-below ((:read below)))]
        (assoc fetched :cache-profile (cache-profile asked fetched))))))

(defn span
  "Open a measurement window and return the `:cost` function for one execution.

  The returned function answers with what has been read *since this call*, so
  a meter shared by a long-lived database still reports one execution's cost.
  It is the function `kotobase.governed-execution` invokes after evaluation.

  PROFILE is the caller's name for the cache regime and is the one field this
  namespace does not measure; `:unmeasured` is the honest value."
  [{:keys [read] :as metered} profile]
  (when-not (and (map? metered) (ifn? read))
    (reject! :not-a-meter {}))
  (when-not (keyword? profile)
    (reject! :invalid-cache-profile {:profile profile}))
  (let [opened (read)]
    (fn [] (assoc (delta opened (read)) :cache-profile profile))))
