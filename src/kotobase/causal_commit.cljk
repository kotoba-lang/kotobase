(ns kotobase.causal-commit
  "Canonical CID commit persistence for causal identity and authority records.

  Every write names an exact immutable basis and returns a new Kotobase commit
  CID. No mutable ref is read or published. Concurrent writers therefore form
  explicit branches, while `receipt-at` replays and verifies one exact branch.
  Raw credentials and identity evidence are outside this projection.

  The disclosure read path that used to live here is gone; `execution-receipt-
  sink` and `kotobase.governed-read` replace it. A guarded read now commits an
  ExecutionReceipt, which answers every field of the version 1 contract, in
  place of a disclosure receipt, which answered one — see
  `docs/ADR-evidence-plane.md`."
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [grant.causal-trust :as trust]
            [identity.adapters.ledger :as identity-ledger]
            [kotobase.core :as core]
            [kotobase.execution-contract :as contract]))

(def format-version "kotobase.causal-record.v1")
(def identity-stream "causal-identity")
(def decision-stream "causal-decisions")
(def execution-stream "causal-executions")

(def ^:private genesis-basis "kotobase:genesis")
(def ^:private format-attribute "kotobase.causal/format")
(def ^:private stream-attribute "kotobase.causal/stream")
(def ^:private basis-attribute "kotobase.causal/expected-basis")
(def ^:private record-count-attribute "kotobase.causal/record-count")
(def ^:private receipt-attribute "kotobase.causal/receipt")
(def ^:private index-attribute "kotobase.causal/index")
(def ^:private payload-attribute "kotobase.causal/payload-edn")

(def ^:private forbidden-field-keys
  #{:credential/raw :evidence/raw :identity.evidence/raw
    :secret/raw :authentication/token
    "credential/raw" "evidence/raw" "identity.evidence/raw"
    "secret/raw" "authentication/token"})

(def ^:private immediate-runtime
  {:then (fn [value f] (f value))
   :all (fn [values] (vec values))})

#?(:cljs
   (def ^:private promise-runtime
     {:then (fn [value f] (.then (js/Promise.resolve value) f))
      :all (fn [values]
             (-> (js/Promise.all (clj->js (vec values)))
                 (.then #(vec (array-seq %)))))}))

(def ^:private completion-runtime
  #?(:clj immediate-runtime
     :cljs promise-runtime))

(defn- then [value f]
  ((:then completion-runtime) value f))

(defn- all [values]
  ((:all completion-runtime) values))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- reject! [reason data]
  (throw (ex-info "canonical causal commit rejected"
                  (assoc data :kotobase.causal-commit/reason reason))))

(defn- contains-forbidden-field? [value]
  (cond
    (map? value)
    (or (some forbidden-field-keys (keys value))
        (some contains-forbidden-field? (vals value)))

    (coll? value) (some contains-forbidden-field? value)
    :else false))

(defn- canonical-order [left right]
  (compare (pr-str left) (pr-str right)))

(declare canonical-value)

(defn- canonical-value [value]
  (cond
    (map? value)
    (into (sorted-map-by canonical-order)
          (map (fn [[key item]]
                 [(canonical-value key) (canonical-value item)]))
          value)

    (set? value)
    (into (sorted-set-by canonical-order) (map canonical-value) value)

    (vector? value) (mapv canonical-value value)
    (list? value) (apply list (map canonical-value value))
    :else value))

(defn- encode-record [record]
  (when-not (map? record)
    (reject! :invalid-record {:record record}))
  (when (contains-forbidden-field? record)
    (reject! :raw-secret-slot {:record-keys (set (keys record))}))
  (pr-str (canonical-value record)))

(defn- record-id [receipt-id index]
  (str receipt-id "#record/" index))

(defn- transaction-data [stream receipt-id expected-basis records]
  (into [[receipt-id format-attribute format-version]
         [receipt-id stream-attribute stream]
         [receipt-id basis-attribute (or expected-basis genesis-basis)]
         [receipt-id record-count-attribute (str (count records))]]
        (mapcat
         (fn [index record]
           (let [entity (record-id receipt-id index)]
             [[entity receipt-attribute receipt-id]
              [entity stream-attribute stream]
              [entity index-attribute (str index)]
              [entity payload-attribute (encode-record record)]]))
         (range)
         records)))

(defn- entity-at [snapshot entity-id]
  (then
   (core/q snapshot [entity-id nil nil])
   (fn [rows]
     (reduce
      (fn [entity {:keys [p o]}]
        (when (contains? entity p)
          (reject! :duplicate-attribute {:entity entity-id :attribute p}))
        (assoc entity p o))
      {}
      rows))))

(defn- decode-record [snapshot stream receipt-id entity-id]
  (then
   (entity-at snapshot entity-id)
   (fn [record]
     (let [index (some-> (get record index-attribute) parse-long)]
       (when-not (and (= receipt-id (get record receipt-attribute))
                      (= stream (get record stream-attribute))
                      (some? index)
                      (non-empty-string? (get record payload-attribute)))
         (reject! :invalid-record-envelope {:entity entity-id}))
       {:index index
        :payload (edn/read-string (get record payload-attribute))}))))

(defn- verified-receipt
  [commit-cid receipt-id stream encoded-basis expected-count decoded]
  (let [decoded (->> decoded (sort-by :index) vec)
        indexes (mapv :index decoded)]
    (when-not (and (non-empty-string? stream)
                   (non-empty-string? encoded-basis)
                   (nat-int? expected-count)
                   (= expected-count (count decoded))
                   (= (vec (range expected-count)) indexes)
                   (every? map? (map :payload decoded)))
      (reject! :incomplete-or-reordered-records
               {:expected-count expected-count :indexes indexes}))
    {:receipt/id receipt-id
     :receipt/commit-cid commit-cid
     :receipt/basis-cid (when-not (= genesis-basis encoded-basis)
                          encoded-basis)
     :receipt/stream stream
     :receipt/records (mapv :payload decoded)}))

(defn receipt-at
  "Read and verify RECEIPT-ID from exactly COMMIT-CID.

  The mutable head is never consulted. Provider bytes are reverified through
  `core/at-cid`; malformed headers, gaps, duplicates, or cross-stream records
  fail closed."
  [database commit-cid receipt-id]
  (when-not (and (non-empty-string? commit-cid)
                 (non-empty-string? receipt-id))
    (reject! :invalid-receipt-location {}))
  (then
   (core/at-cid database commit-cid)
   (fn [snapshot]
     (then
      (entity-at snapshot receipt-id)
      (fn [header]
        (when-not (= format-version (get header format-attribute))
          (reject! :receipt-not-found-or-invalid
                   {:commit-cid commit-cid :receipt-id receipt-id}))
        (let [stream (get header stream-attribute)
              encoded-basis (get header basis-attribute)
              expected-count (some-> (get header record-count-attribute)
                                     parse-long)]
          (then
           (core/q snapshot [nil receipt-attribute receipt-id])
           (fn [record-links]
             (let [record-ids (->> record-links (map :s) sort vec)]
               (then
                (all (mapv #(decode-record snapshot stream receipt-id %)
                           record-ids))
                #(verified-receipt commit-cid receipt-id stream encoded-basis
                                   expected-count %)))))))))))

(defn- commit-records!
  [database stream receipt-id expected-basis records]
  (when-not (non-empty-string? stream)
    (reject! :invalid-stream {}))
  (when-not (non-empty-string? receipt-id)
    (reject! :invalid-receipt-cid {}))
  (when-not (or (nil? expected-basis) (non-empty-string? expected-basis))
    (reject! :invalid-expected-basis {}))
  (when-not (and (vector? records) (seq records) (every? map? records))
    (reject! :invalid-records {}))
  (then
   (core/commit-at!
    database expected-basis
    (transaction-data stream receipt-id expected-basis records))
   (fn [commit-cid]
     (then
      (receipt-at database commit-cid receipt-id)
      (fn [proof]
        (when-not (= {:receipt/basis-cid expected-basis
                      :receipt/stream stream
                      :receipt/records records}
                     (select-keys proof [:receipt/basis-cid
                                         :receipt/stream
                                         :receipt/records]))
          (reject! :write-proof-mismatch {:proof proof}))
        {:receipt/durable? true
         :receipt/cid receipt-id
         :receipt/commit-cid commit-cid
         :receipt/basis-cid expected-basis
         :receipt/record-count (count records)
         :receipt/route :canonical-cid-dag})))))

(defn identity-ledger
  "Adapt canonical Kotobase commits to identity's atomic ledger contract.

  Options are `:tx/receipt-cid` and `:tx/expected-basis-cid`. The latter is
  an immutable commit CID (or nil for genesis), never a mutable revision."
  [database]
  (reify identity-ledger/ILedger
    (transact! [_ datoms opts]
      (when-not (= #{:tx/receipt-cid :tx/expected-basis-cid}
                   (set (keys opts)))
        (reject! :invalid-identity-transaction-options {}))
      (let [receipt-id (:tx/receipt-cid opts)
            records (mapv (fn [datom]
                            {:causal.record/type :identity-datom
                             :causal.record/receipt-cid receipt-id
                             :causal.record/datom datom})
                          datoms)]
        (commit-records! database identity-stream receipt-id
                         (:tx/expected-basis-cid opts) records)))))

(defn persist-decision!
  "Validate and commit a secret-free authority receipt to an exact basis."
  [database receipt expected-basis]
  (let [receipt (trust/receipt receipt)]
    (when-not (= expected-basis (:causal.receipt/basis-cid receipt))
      (reject! :receipt-basis-mismatch
               {:expected-basis expected-basis
                :receipt-basis (:causal.receipt/basis-cid receipt)}))
    (commit-records! database decision-stream
                     (:causal.receipt/id receipt)
                     expected-basis [receipt])))

(defn execution-receipt-sink
  "Build the `:commit!` that `kotobase.governed-execution` requires.

  The ExecutionReceipt is validated again here rather than trusted from the
  caller — this is the boundary where it becomes durable, and a record that
  only the layer that built it ever checked is checked once. It is then
  written at an exact immutable basis and *read back from the commit it
  produced* before the acknowledgement returns: `commit-records!` refuses a
  write whose read-back does not reproduce it, so `durable` here means `read
  back`, not `the write call returned`.

  `receipt-cid-fn` is the host's canonical codec. This namespace does not hash
  a printed representation to name a receipt."
  [database {:keys [expected-basis-cid receipt-cid-fn] :as options}]
  (when-not (= #{:expected-basis-cid :receipt-cid-fn} (set (keys options)))
    (reject! :invalid-execution-sink-options {}))
  (when-not (ifn? receipt-cid-fn)
    (reject! :missing-receipt-cid-function {}))
  (when-not (or (nil? expected-basis-cid) (non-empty-string? expected-basis-cid))
    (reject! :invalid-expected-basis {}))
  (fn [receipt]
    (contract/validate-receipt! receipt)
    (let [receipt-id (receipt-cid-fn receipt)]
      (when-not (non-empty-string? receipt-id)
        (reject! :invalid-receipt-cid {}))
      (commit-records! database execution-stream receipt-id
                       expected-basis-cid [receipt]))))
