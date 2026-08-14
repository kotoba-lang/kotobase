(ns kotobase.capability
  "The capability vocabulary three CID-database architectures are compared on.

  A backend does not get to be \"fast\" at something it cannot do. Every
  benchmark number in this tree is paired with the capability the operation
  needs, and a backend that lacks the capability returns `::unsupported`
  instead of a number — so the comparison never silently credits a log replay
  as if it were an index lookup, or a per-stream materialisation as if it were
  a global snapshot.

  Capabilities are declared per backend and checked at call time
  (`guard`): claiming one you do not implement fails loudly rather than
  producing a plausible measurement."
  (:require [clojure.string :as str]))

(def ^:const unsupported ::unsupported)

(defn unsupported?
  [x]
  (or (= x unsupported)
      (and (map? x) (= (:status x) unsupported))))

(def vocabulary
  "capability -> what a backend must actually do to claim it."
  {:immutable-blocks
   "Facts live in immutable blocks addressed by CIDv1 (dag-cbor/sha2-256)."

   :cid-verified-read
   "The reader recomputes the CID of fetched bytes before decoding them;
    provider bytes are untrusted."

   :conditional-ref
   "A mutable ref supports linearizable compare-and-set, so a commit either
    wins or is told it lost. Not the same as 'has a head pointer'."

   :multi-writer-merge
   "Two writers that never talked to each other converge, by merge rather
    than by coordination (CRDT / Merkle-CRDT)."

   :linearizable-txn
   "One transaction lands atomically across everything it touches, or not
    at all."

   :cross-shard-txn
   "Atomicity survives crossing a shard / actor / stream boundary."

   :covering-index
   "There is an index that answers 'which entities have attribute a = v'
    without materialising the whole database."

   :verifiable-index
   "Index membership is provable against the root CID — a reader who
    distrusts the server can check the answer."

   :range-scan
   "Ordered scan over an index range without a full materialisation."

   :global-snapshot
   "A single consistent read-state across all entities, not just per
    stream/shard."

   :time-travel
   "An arbitrary past state is a first-class readable value."

   :structural-delta-sync
   "A replica converges in O(changed subtrees), by comparing content
    addresses — not by replaying history."

   :log-replay-sync
   "A replica converges by fetching and replaying log entries it lacks."

   :interest-sync
   "A replica may declare interest in a subset and sync only that subset."

   :warrant-gossip
   "Validation receipts for source-chain actions propagate to a neighbourhood.
    A peer fetches the warrant and the action without replaying the whole DHT."

   :analytical-projection
   "A columnar projection exists for scans/aggregates, separate from the
    transactional index."

   :deterministic-execution
   "old-root + message + code-CID -> new-root is reproducible, so a second
    party can re-execute and check the transition."})

(def ^:private known (set (keys vocabulary)))

(defn declare-capabilities
  "Validate a backend's declared capability set against the vocabulary."
  [id caps]
  (let [caps (set caps)
        unknown (remove known caps)]
    (when (seq unknown)
      (throw (ex-info (str "unknown capabilities declared by " id)
                      {:backend id :unknown (vec unknown)})))
    caps))

(defn supports?
  [backend-caps cap]
  (contains? (set backend-caps) cap))

(defn guard
  "Run `f` only if `caps` includes `cap`; otherwise return an
  `::unsupported` marker carrying the reason. This is the whole point of the
  capability layer: an architecture that cannot do the operation shows up as a
  hole in the matrix, not as a fast number."
  [caps cap f]
  (if (supports? caps cap)
    (f)
    {:status unsupported :capability cap :why (get vocabulary cap)}))

(defn matrix
  "backends -> rows of {:capability c :by-backend {id bool}}."
  [backends]
  (let [ids (mapv :id backends)]
    (for [cap (sort (keys vocabulary))]
      {:capability cap
       :by-backend (into {} (for [b backends]
                              [(:id b) (supports? (:capabilities b) cap)]))
       :ids ids})))

(defn render-matrix
  [backends]
  (let [ids (mapv :id backends)
        w (apply max 24 (map (comp count name) (keys vocabulary)))
        pad (fn [s n] (str s (str/join (repeat (max 0 (- n (count s))) " "))))]
    (str/join
     "\n"
     (into [(str (pad "capability" w) " | " (str/join " | " (map name ids)))]
           (for [{:keys [capability by-backend]} (matrix backends)]
             (str (pad (name capability) w) " | "
                  (str/join " | "
                            (map (fn [id]
                                   (pad (if (get by-backend id) "yes" "-")
                                        (count (name id))))
                                 ids))))))))
