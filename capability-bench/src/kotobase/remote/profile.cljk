(ns kotobase.remote.profile
  "An object-store profile: no block is local, so every block access is a
  request, and what matters is no longer bytes but **requests and hops**.

  Two numbers, and they are not the same number:

  - **requests** — total GET/PUT operations. Sets the bill and the bandwidth.
    Independent requests can be issued in parallel.
  - **hops** — the length of the longest chain of requests where each address
    is only known after decoding the previous response. A Prolly descent is a
    hop chain: you cannot ask for the leaf until the root says which leaf. The
    latency floor is `hops x RTT` however much you parallelise.

  On a local store both are free and neither shows up. On S3 the hop count is
  the latency and the request count is the invoice, so two designs that look
  equivalent in memory can be orders of magnitude apart on one of them.

  Client-side encryption is applied here, at the block boundary, and this
  namespace deliberately implements the *plaintext-addressed* variant: the
  block keeps the CID of its plaintext and the object under it is ciphertext.
  That is the only variant compatible with an unmodified content-addressed
  tree, and it has a consequence worth stating rather than burying — **the
  server can no longer verify what it holds**, because it cannot recompute a
  plaintext CID from ciphertext. Integrity becomes client-only.
  `kotobase.remote.encryption` measures the ciphertext-addressed variants,
  where the server can verify and the cost lands on deduplication instead."
  (:refer-clojure :exclude [get])
  (:require [kotobase.capability.blockstore :as bs]
            #?(:cljs ["crypto" :as crypto])))

(def gcm-overhead
  "12-byte nonce + 16-byte GCM tag, per block, unconditionally."
  28)

(def zero
  {:requests-get 0 :requests-put 0
   :bytes-get 0 :bytes-put 0
   :cache-hits 0 :cache-misses 0
   :hops 0
   :encrypt-ms 0.0 :decrypt-ms 0.0
   :plaintext-bytes 0 :ciphertext-bytes 0})

(defn- now [] #?(:cljs (js/performance.now) :clj (/ (System/nanoTime) 1e6)))

(defn sha256 [b]
  #?(:cljs (.digest (.update (.createHash crypto "sha256") b))
     :clj (throw (ex-info "jvm crypto path not implemented" {}))))

(defn- ->buf [b]
  #?(:cljs (if (instance? js/Buffer b) b (js/Buffer.from b))
     :clj b))

(defn encrypt
  "AES-256-GCM. `:convergent` derives key and nonce from the plaintext hash so
  equal plaintexts encrypt equally; `:random` draws a fresh nonce."
  [mode plaintext]
  #?(:cljs
     (let [pt (->buf plaintext)
           digest (sha256 pt)
           k (.subarray digest 0 32)
           iv (if (= mode :convergent)
                (.subarray digest 0 12)
                (.randomBytes crypto 12))
           c (.createCipheriv crypto "aes-256-gcm" k iv)
           body (.concat js/Buffer #js [(.update c pt) (.final c)])]
       (.concat js/Buffer #js [iv body (.getAuthTag c)]))
     :clj (throw (ex-info "jvm crypto path not implemented" {}))))

(defn decrypt
  "`k` is the 32-byte content key. A real client holds it out of band (derived
  from the plaintext hash for convergent mode, or from a key hierarchy);
  deriving it from the stored bytes would defeat the point, so the benchmark
  keeps it beside the block and charges only the cipher work — which is what
  is being measured."
  [k plaintext-len ciphertext]
  #?(:cljs
     (let [ct (->buf ciphertext)
           iv (.subarray ct 0 12)
           body (.subarray ct 12 (+ 12 plaintext-len))
           tag (.subarray ct (+ 12 plaintext-len))
           d (.createDecipheriv crypto "aes-256-gcm" k iv)]
       (.setAuthTag d tag)
       (.concat js/Buffer #js [(.update d body) (.final d)]))
     :clj (throw (ex-info "jvm crypto path not implemented" {}))))

(defn make
  "`encryption` is nil, `:convergent` or `:random`.
  `cache-size` 0 disables the block cache. Eviction is FIFO, not LRU — stated
  because it changes the hit rate."
  [{:keys [encryption cache-size] :or {cache-size 0}}]
  (let [store (bs/make)
        state (atom (assoc zero :cache {} :cache-order [] :lengths {} :keys {}))]
    {:store store
     :state state
     :encryption encryption
     :cache-size cache-size
     :put!
     (fn [cid bytes]
       (let [pt (->buf bytes)
             t0 (now)
             payload (if encryption (encrypt encryption pt) pt)
             t1 (now)]
         (swap! state #(-> %
                           (update :requests-put inc)
                           (update :bytes-put + (.-length payload))
                           (update :plaintext-bytes + (.-length pt))
                           (update :ciphertext-bytes + (.-length payload))
                           (update :encrypt-ms + (- t1 t0))
                           (assoc-in [:lengths cid] (.-length pt))
                           (assoc-in [:keys cid] (when encryption
                                                   (.subarray (sha256 pt) 0 32)))))
         (bs/put! store cid payload)
         nil))
     :get
     (fn [cid]
       (let [{:keys [cache lengths] ks :keys} @state]
         (if-let [hit (clojure.core/get cache cid)]
           (do (swap! state update :cache-hits inc) hit)
           (let [raw (bs/get store cid)
                 t0 (now)
                 b (if (and raw encryption)
                     (decrypt (clojure.core/get ks cid)
                              (clojure.core/get lengths cid) raw)
                     raw)
                 t1 (now)]
             (swap! state #(-> % (update :requests-get inc)
                               (update :cache-misses inc)
                               (update :decrypt-ms + (- t1 t0))
                               (update :bytes-get + (if raw (.-length raw) 0))))
             (when (and b (pos? cache-size))
               (swap! state (fn [s]
                              (let [order (conj (:cache-order s) cid)
                                    [order cache]
                                    (if (> (count order) cache-size)
                                      [(subvec order 1)
                                       (dissoc (:cache s) (first order))]
                                      [order (:cache s)])]
                                (assoc s :cache-order order
                                       :cache (assoc cache cid b))))))
             b))))}))

(defn barrier!
  "Mark the end of one dependent round trip: the point where the next
  request's address could only be known after decoding the previous response."
  ([p] (barrier! p 1))
  ([p n] (swap! (:state p) update :hops + n) nil))

(defn stats [p] (dissoc @(:state p) :cache :cache-order :lengths :keys))
(defn reset-stats! [p]
  (swap! (:state p) #(merge % (dissoc zero :cache :cache-order)))
  nil)
(defn blocks [p] (bs/block-count (:store p)))
(defn stored-bytes [p] (bs/stored-bytes (:store p)))

(defn latency
  "Model the wall clock `requests` and `hops` would cost against an object
  store at `rtt-ms`, under two issue strategies:

  - `serial` — one request at a time (a naive client): requests x RTT
  - `pipelined` — every independent request in parallel, paying only for the
    dependent chain: hops x RTT

  Real clients sit between the two. Both bounds are reported so nobody has to
  believe an invented concurrency factor."
  [{:keys [requests hops]} rtt-ms]
  {:rtt-ms rtt-ms
   :serial-ms (* requests rtt-ms)
   :pipelined-ms (* hops rtt-ms)})
