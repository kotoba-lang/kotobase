(ns kotobase.capability.backend.holochain
  "Holochain-shaped backend: one signed source chain per agent, plus DHT
  metadata (CreateLink / DeleteLink) stored at a *base address* that does
  not change when a link is added.

  Structure faithful to Holochain's published split, not to the Rust
  conductor:

  - each entity is one agent. A write is an action on that agent's source
    chain (Create / Update of a profile entry, then CreateLink / DeleteLink);
  - the entry itself is content-addressed; updates produce a new entry and
    a header at the agent address pointing at it;
  - discovery is typed links from anchors / paths, stored as a hash-linked
    op chain *at the base*. `get_links` walks that chain. It is an
    application index for the attributes the zome bothered to link — here
    `:person/city` (exact-value anchors) and `:person/score` (path buckets
    of 10) — and it is *not* a merkelized covering index of the whole
    database. Completeness is whatever the neighbourhood integrated;
  - there is no database-wide basis `t`. Per-agent sequence exists;
    `-snapshot-read` therefore refuses (`:global-snapshot` is absent),
    the same hole Ceramic has, for the same reason.

  What this makes visible that the other backends do not: find-by-value is
  O(links at that base), not O(database) and not O(log n) in a Prolly
  tree, and a replica can sync only the agents it declared interest in."
  (:require [ipld.core :as ipld]
            [kotobase.capability :as cap]
            [kotobase.capability.blockstore :as bs]
            [kotobase.capability.workload :as w]
            [kotobase.capability.backend :as be]))

(def base-capabilities
  ;; No :conditional-ref — DHT integration is validation + gossip, not CAS.
  ;; No :linearizable-txn — atomicity is per source-chain action, not across
  ;; agents.
  ;; No :verifiable-index — a get_links result is not a merkle proof against
  ;; a database root; a neighbourhood can omit.
  ;; No :global-snapshot — there is no order between two agents' chains.
  ;; :covering-index IS here, narrowly: typed links answer equality on the
  ;; attributes the zome indexed, without materialising every entry.
  ;; :range-scan IS here, equally narrowly: score lives under path buckets.
  #{:immutable-blocks :cid-verified-read :covering-index :range-scan
    :log-replay-sync :interest-sync :time-travel :warrant-gossip})

(def ^:private score-bucket-width 10)

(def ^:private warrant-neighbourhood
  "DHT authorities a published action is gossiped to. Holochain's published
  redundancy is on the order of tens; 8 is the same constant the ActorDB
  shape uses as its default shard count, so message counts are comparable."
  8)

(defn- warrant-node [action-cid e seqn]
  {"kind" "warrant"
   "action" (ipld/link action-cid)
   "agent" (w/entity-key e)
   "seq" seqn})

(defn- score-bucket [v]
  (* score-bucket-width (quot (long v) score-bucket-width)))

(defn- city-base [v]
  (str "anchor/person/city/" v))

(defn- score-base [v]
  (str "path/person/score/" (score-bucket v)))

(defn- score-bases-covering [lo hi]
  (mapv score-base
        (range (score-bucket lo)
               (+ (score-bucket hi) 1)
               score-bucket-width)))

(defn- entry-node [e attrs]
  {"kind" "entry"
   "entity" (w/entity-key e)
   "data" (into {} (for [[a v] attrs] [(w/attr-str a) v]))})

(defn- action-node [typ prev extra code-cid]
  (cond-> (merge {"kind" "action" "type" typ} extra)
    prev (assoc "prev" (ipld/link prev))
    code-cid (assoc "code" (ipld/link code-cid))))

(defn- activity-node [action-cid entry-cid seqn code-cid]
  (cond-> {"kind" "agent-activity"
           "seq" seqn
           "head" (ipld/link action-cid)
           "entry" (ipld/link entry-cid)}
    code-cid (assoc "code" (ipld/link code-cid))))

(defn- dht-op-node [typ prev target tag value]
  (cond-> {"kind" "dht-op"
           "type" typ
           "target" target}
    prev (assoc "prev" (ipld/link prev))
    tag (assoc "tag" tag)
    (some? value) (assoc "value" value)))

(defn- walk-chain
  "Follow `prev` from `head` until `stop?`. Returns [nodes-newest-first reads]."
  [get-fn head stop?]
  (loop [cid head out [] reads 0]
    (cond
      (nil? cid) [out reads]
      (stop? cid) [out reads]
      :else
      (let [node (ipld/decode (get-fn cid))]
        (recur (some-> (get node "prev") ipld/link-cid)
               (conj out node)
               (inc reads))))))

(defn- integrate-links
  "DHT ops in causal (oldest-first) order → current {target -> value}."
  [ops]
  (reduce (fn [m op]
            (let [typ (get op "type")
                  target (get op "target")]
              (case typ
                "delete-link" (dissoc m target)
                "create-link" (assoc m target (get op "value"))
                m)))
          {}
          ops))

(defn- get-links
  "Walk the DHT op chain at `base`. Returns [target->value reads]."
  [get-fn st base]
  (let [head (get-in @st [:dht base :head])
        [ops reads] (walk-chain get-fn head (constantly false))]
    [(integrate-links (reverse ops)) reads]))

(defn- gossip-agent
  "Walk one agent's chain from `head` to `stop`. Returns [blocks-read actions]."
  [get-fn store warrants head stop fanout]
  (loop [cid head r 0 a 0]
    (if (or (nil? cid) (= cid stop))
      [r a]
      (let [node (ipld/decode (get-fn cid))
            warrant (get warrants cid)
            wr (if warrant
                 (do (get-fn warrant) 2)
                 1)]
        (bs/message! store fanout)
        (recur (some-> (get node "prev") ipld/link-cid)
               (+ r wr)
               (inc a))))))

(defn- append-dht-op!
  [put! st base typ target tag value]
  (let [prev (get-in @st [:dht base :head])
        cid (ipld/put-node! put! (dht-op-node typ prev target tag value))]
    (swap! st update-in [:dht base]
           (fn [s] {:head cid :ops (inc (:ops s 0))}))
    cid))

(defn- append-action!
  [put! st e typ extra code-cid]
  (let [agent (get-in @st [:agents e])
        seqn (inc (:seq agent 0))
        cid (ipld/put-node! put! (action-node typ (:head agent) extra code-cid))
        warrant-cid (ipld/put-node! put! (warrant-node cid e seqn))]
    (swap! st #(-> %
                   (update-in [:agents e] assoc :head cid :seq seqn)
                   (update :actions inc)
                   (assoc-in [:warrants cid] warrant-cid)))
    cid))

(defn- write-entity!
  "One agent's Create or Update, plus link maintenance for city and score."
  [put! store id st e attrs t code-cid]
  (let [agent (get-in @st [:agents e])
        old (:attrs agent {})
        creating? (nil? agent)
        entry-cid (ipld/put-node! put! (entry-node e attrs))
        original (if creating? entry-cid (:original agent))
        typ (if creating? "create" "update")
        action-cid (append-action!
                    put! st e typ
                    {"t" t
                     "entry" (ipld/link entry-cid)
                     "original" (ipld/link original)}
                    code-cid)
        seqn (get-in @st [:agents e :seq])
        activity-cid (ipld/put-node! put!
                                     (activity-node action-cid entry-cid seqn code-cid))
        ek (w/entity-key e)
        old-city (get old :person/city)
        new-city (get attrs :person/city)
        old-score (get old :person/score)
        new-score (get attrs :person/score)]
    (bs/set-ref! store [id :agent e] activity-cid)
    (when (and old-city (not= old-city new-city))
      (let [base (city-base old-city)]
        (append-action! put! st e "delete-link"
                        {"base" base "target" ek "t" t} code-cid)
        (append-dht-op! put! st base "delete-link" ek nil nil)))
    (when (and new-city (or creating? (not= old-city new-city)))
      (let [base (city-base new-city)]
        (append-action! put! st e "create-link"
                        {"base" base "target" ek "tag" (str new-city) "t" t}
                        code-cid)
        (append-dht-op! put! st base "create-link" ek (str new-city) new-city)))
    (when (and (some? old-score) (not= old-score new-score))
      (let [base (score-base old-score)]
        (append-action! put! st e "delete-link"
                        {"base" base "target" ek "t" t} code-cid)
        (append-dht-op! put! st base "delete-link" ek nil nil)))
    (when (and (some? new-score) (or creating? (not= old-score new-score)))
      (let [base (score-base new-score)]
        (append-action! put! st e "create-link"
                        {"base" base "target" ek "tag" (w/->v-key new-score)
                         "t" t}
                        code-cid)
        (append-dht-op! put! st base "create-link" ek (w/->v-key new-score)
                        new-score)))
    (swap! st assoc-in [:agents e]
           (merge (get-in @st [:agents e])
                  {:attrs attrs
                   :entry-cid entry-cid
                   :original original
                   :activity-cid activity-cid}))
    {:entry entry-cid :action action-cid}))

(defrecord HolochainBackend [id label capabilities store put! get-fn st opts]
  be/IDatomBackend
  (-transact! [_ txn]
    (let [by-entity (group-by w/datom-e (:datoms txn))
          code-cid (:code-cid opts)
          t (:t txn)]
      (doseq [[e ds] by-entity]
        (let [old (get-in @st [:agents e :attrs] {})
              attrs (into old (map (fn [d] [(w/datom-a d) (w/datom-v d)]) ds))]
          (write-entity! put! store id st e attrs t code-cid)))
      {:agents-touched (count by-entity)
       :actions (:actions @st)}))

  (-read-entity [_ e]
    ;; A remote reader fetches the agent-activity header at the agent
    ;; address, then the current entry. Two gets, independent of database
    ;; size — the same bound Ceramic has for a single stream.
    (let [activity-cid (bs/ref-value store [id :agent e])]
      (if-not activity-cid
        {:via :agent-activity :blocks-read 0 :value {}}
        (let [header (ipld/decode (get-fn activity-cid))
              entry (ipld/decode (get-fn (ipld/link-cid (get header "entry"))))]
          {:via :agent-activity
           :blocks-read 2
           :value (into {} (for [[a v] (get entry "data")] [(w/->attr a) v]))}))))

  (-find-by-value [_ a v]
    ;; Only the attributes the zome linked are discoverable this way. The
    ;; workload's equality queries are on :person/city; anything else would
    ;; have to walk every agent, and that cost would be charged as a scan.
    (if (not= a :person/city)
      (let [agents (:agents @st)
            hits (vec (for [[e ag] agents
                            :when (= (get-in ag [:attrs a]) v)]
                        (w/entity-key e)))]
        {:via :full-agent-scan
         :entries-scanned (count agents)
         :value hits})
      (let [[m reads] (get-links get-fn st (city-base v))]
        {:via :links
         :blocks-read reads
         :links-at-base (count m)
         :value (vec (sort (keys m)))})))

  (-range-scan [_ a lo hi]
    (if (not= a :person/score)
      {:via :full-agent-scan
       :entries-scanned (count (:agents @st))
       :value (->> (for [[e ag] (:agents @st)
                         :let [v (get-in ag [:attrs a])]
                         :when (and (number? v) (>= v lo) (<= v hi))]
                     [(w/entity-key e) (w/->v-key v)])
                   (sort-by first)
                   vec)}
      (let [bases (score-bases-covering lo hi)
            [pairs reads]
            (reduce (fn [[acc r] base]
                      (let [[m n] (get-links get-fn st base)
                            kept (for [[ek v] m
                                       :when (and (number? v) (>= v lo) (<= v hi))]
                                   [ek (w/->v-key v)])]
                        [(into acc kept) (+ r n)]))
                    [[] 0]
                    bases)]
        {:via :path-links
         :blocks-read reads
         :bases-visited (count bases)
         :value (vec (sort-by first pairs))})))

  (-snapshot-read [_ t _e]
    {:status cap/unsupported
     :capability :global-snapshot
     :basis t
     :why "source chains have no common order; only per-agent sequence exists"})

  (-checkpoint [_]
    {:agent-heads (into {} (for [[e ag] (:agents @st)] [e (:head ag)]))
     :actions (:actions @st)})

  (-sync-from [_ marker {:keys [interest]}]
    ;; Interest-scoped: a replica that named some agents pays only for those
    ;; source chains. Nil interest is a full catch-up of every chain.
    (let [s @st
          old-heads (:agent-heads marker)
          candidates (if interest
                       (filter #(contains? interest %) (keys (:agents s)))
                       (keys (:agents s)))
          [reads actions]
          (reduce (fn [[r a] e]
                    (let [stop (get old-heads e)
                          [nodes n] (walk-chain get-fn
                                                (get-in s [:agents e :head])
                                                #(= % stop))]
                      [(+ r n) (+ a (count nodes))]))
                  [0 0]
                  candidates)]
      {:via (if interest :interest-scoped-sync :log-replay)
       :agents-considered (count candidates)
       :blocks-read reads
       :entries-transferred actions}))

  (-info [_]
    (let [s @st]
      {:agents (count (:agents s))
       :actions (:actions s)
       :warrants (count (:warrants s))
       :dht-bases (count (:dht s))
       :note "links live at the base address as metadata; the parent CID does not change"}))

  be/IWarrantGossip
  (-gossip-warrants [_ marker {:keys [interest fanout]
                               :or {fanout warrant-neighbourhood}}]
    (let [s @st
          old-heads (:agent-heads marker)
          candidates (if interest
                       (filter #(contains? interest %) (keys (:agents s)))
                       (keys (:agents s)))
          [reads actions]
          (reduce (fn [[r a] e]
                    (let [[n k] (gossip-agent get-fn store (:warrants s)
                                              (get-in s [:agents e :head])
                                              (get old-heads e)
                                              fanout)]
                      [(+ r n) (+ a k)]))
                  [0 0]
                  candidates)]
      {:via :warrant-gossip
       :agents-considered (count candidates)
       :warrants actions
       :fanout fanout
       :messages (* actions fanout)
       :blocks-read reads
       :entries-transferred actions})))

(defn make
  [{:keys [store fvm?] :as opts}]
  (let [{:keys [put! get]} (if fvm?
                             (bs/fvm-boundary store)
                             {:put! (bs/put-fn store) :get (bs/get-fn store)})
        code-cid (when fvm?
                   (ipld/put-node! put! {"kind" "actor-code"
                                         "name" "holochain-source-chain"}))]
    (map->HolochainBackend
     {:id (if fvm? :holochain+fvm :holochain)
      :label (if fvm?
               "Holochain-shaped source chains + DHT links inside an FVM boundary"
               "Holochain-shaped source chains + DHT links/anchors")
      :capabilities (cap/declare-capabilities
                     :holochain
                     (cond-> base-capabilities fvm? (conj :deterministic-execution)))
      :store store :put! put! :get-fn get
      :opts (assoc opts :code-cid code-cid)
      :st (atom {:agents {} :dht {} :actions 0 :warrants {}})})))
