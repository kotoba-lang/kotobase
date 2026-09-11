(ns kotobase.capability.blockstore
  "One instrumented block store, shared by every backend under test.

  This is the layer the comparison is actually about. Every architecture in
  this benchmark ends up at the same place — put an immutable block, get an
  immutable block, compare-and-set a mutable ref — and what distinguishes them
  is *how many times* and *how many bytes*. So the counters here, not the wall
  clock, are the primary result: they are independent of the interpreter this
  happens to run on.

  `fvm-boundary` wraps the same store in the cost a deterministic Wasm
  executor (FVM/ref-fvm) actually adds on the hot path: every block crosses a
  host/guest boundary and is *copied*, once each way. It counts crossings and
  copied bytes so a reader can multiply by their own measured syscall cost
  instead of trusting one from this container."
  (:refer-clojure :exclude [get]))

(defn- blen [b]
  #?(:cljs (.-length ^js b)
     :clj (alength ^bytes b)))

(defn- copy-bytes [b]
  #?(:cljs (.slice ^js b 0)
     :clj (aclone ^bytes b)))

(def zero-stats
  {:puts 0 :put-bytes 0 :dup-puts 0
   :gets 0 :get-bytes 0
   :ref-cas 0 :ref-cas-failed 0
   :messages 0
   :boundary-crossings 0 :boundary-copied-bytes 0})

(defn make
  []
  (atom {:blocks {} :refs {} :stats zero-stats}))

(defn- bump! [store k n]
  (swap! store update-in [:stats k] + n))

(defn put!
  "Store `bytes` at `cid`. Deduplicates — a CID already present is counted as
  a duplicate put and costs no new bytes, which is the whole reason content
  addressing is here."
  [store cid bytes]
  (let [n (blen bytes)]
    (if (contains? (:blocks @store) cid)
      (swap! store update :stats #(-> % (update :puts inc) (update :dup-puts inc)))
      (swap! store #(-> %
                        (assoc-in [:blocks cid] bytes)
                        (update :stats (fn [s] (-> s (update :puts inc)
                                                   (update :put-bytes + n))))))))
  nil)

(defn get
  [store cid]
  (let [b (clojure.core/get (:blocks @store) cid)]
    (when b
      (swap! store update :stats #(-> % (update :gets inc)
                                      (update :get-bytes + (blen b)))))
    b))

(defn put-fn [store] (fn [cid bytes] (put! store cid bytes)))
(defn get-fn [store] (fn [cid] (get store cid)))

;; ── mutable refs (the linearizable half of the storage contract) ────────────

(defn cas!
  "Compare-and-set a mutable ref. Returns true on success. A backend without
  this capability must not call it — that is what the capability guard is for."
  [store ref-key expected new-value]
  (let [ok (= expected (get-in @store [:refs ref-key]))]
    (if ok
      (swap! store #(-> % (assoc-in [:refs ref-key] new-value)
                        (update-in [:stats :ref-cas] inc)))
      (swap! store update :stats #(-> % (update :ref-cas inc)
                                      (update :ref-cas-failed inc))))
    ok))

(defn ref-value [store ref-key] (get-in @store [:refs ref-key]))

(defn set-ref!
  "Unconditional ref write — for backends whose ref plane is *not* a CAS
  (gossiped heads, IPNS single-writer). Counted separately from `cas!` on
  purpose: it is a weaker primitive and the matrix says so."
  [store ref-key v]
  (swap! store assoc-in [:refs ref-key] v)
  nil)

;; ── actor messages ─────────────────────────────────────────────────────────

(defn message!
  "Count n inter-actor messages. Actor-model backends pay in messages what
  single-root backends pay in write amplification; both are measured."
  ([store] (message! store 1))
  ([store n] (bump! store :messages n) nil))

;; ── FVM / deterministic-executor boundary ──────────────────────────────────

(defn fvm-boundary
  "Wrap `[put-fn get-fn]` in a host/guest boundary: each block is copied once
  on the way in and once on the way out, and each crossing is counted.

  This models the cost the FVM spec itself flags — fine-grained IPLD access
  from inside Wasm turns one logical index descent into a sequence of
  syscalls, each with a copy — without pretending to reproduce any particular
  VM's per-syscall constant."
  [store]
  {:put! (fn [cid bytes]
           (swap! store update :stats
                  #(-> % (update :boundary-crossings inc)
                       (update :boundary-copied-bytes + (blen bytes))))
           (put! store cid (copy-bytes bytes)))
   :get (fn [cid]
          (let [b (get store cid)]
            (when b
              (swap! store update :stats
                     #(-> % (update :boundary-crossings inc)
                          (update :boundary-copied-bytes + (blen b))))
              (copy-bytes b))))})

;; ── accounting ─────────────────────────────────────────────────────────────

(defn stats [store] (:stats @store))
(defn reset-stats! [store] (swap! store assoc :stats zero-stats) nil)
(defn block-count [store] (count (:blocks @store)))
(defn stored-bytes [store] (reduce + 0 (map blen (vals (:blocks @store)))))

(defn delta
  "stats-after minus stats-before, dropping zeros so results stay readable."
  [before after]
  (into {} (for [k (keys zero-stats)
                 :let [d (- (clojure.core/get after k 0) (clojure.core/get before k 0))]
                 :when (not (zero? d))]
             [k d])))
