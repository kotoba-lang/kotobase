(ns kotobase.dataset
  "Datomic-queryable dataset catalog and training-suitability contract.

  Dataset bytes stay content-addressed outside this catalog. A manifest binds
  their CID, provenance, license, safety/quality evidence, splits and commercial
  offer. Publishing creates datoms in the same graph-store used by application
  and Git data. Unknown/mixed rights fail closed: discoverable does not mean
  LLM-training-eligible."
  (:require [clojure.string :as str]
            [kotobase.graph-store :as graph]))

(def score-dimensions
  [:license-clarity :provenance :quality :deduplication
   :pii-safety :toxicity-safety :language-metadata :format-readiness])

(def training-licenses
  #{:cc0 :cc-by-4.0 :apache-2.0 :mit :odc-by-1.0 :odbl-1.0
    :proprietary-training-license})

(defn- require! [pred value problem data]
  (when-not (pred value)
    (throw (ex-info (name problem) (assoc data :problem problem))))
  value)

(defn validate-manifest
  "Validate the minimum immutable/catalog boundary. Returns MANIFEST."
  [{:dataset/keys [id cid title source license rights-asserted?
                   format rows bytes scores owner visibility] :as manifest}]
  (doseq [[value problem]
          [[id :dataset/id-required] [cid :dataset/cid-required]
           [title :dataset/title-required] [source :dataset/source-required]]]
    (require! #(and (string? %) (not (str/blank? %))) value problem {}))
  (require! keyword? license :dataset/license-required {:dataset/id id})
  (require! #(and (string? %) (not (str/blank? %))) owner
            :dataset/owner-required {:dataset/id id})
  (require! #{:private :unlisted :public} visibility
            :dataset/visibility-invalid {:dataset/id id :visibility visibility})
  (require! boolean? rights-asserted? :dataset/rights-assertion-required
            {:dataset/id id})
  (require! keyword? format :dataset/format-required {:dataset/id id})
  (require! #(and (integer? %) (not (neg? %))) rows :dataset/rows-invalid
            {:dataset/id id})
  (require! #(and (integer? %) (not (neg? %))) bytes :dataset/bytes-invalid
            {:dataset/id id})
  (doseq [[dimension score] scores]
    (require! (set score-dimensions) dimension :dataset/unknown-score-dimension
              {:dataset/id id :dimension dimension})
    (require! #(and (number? %) (<= 0 % 5)) score :dataset/score-out-of-range
              {:dataset/id id :dimension dimension :score score}))
  manifest)

(defn suitability
  "Transparent 0-100 LLM training suitability plus non-negotiable gates.

  Missing dimensions score zero. Eligibility additionally requires a known
  training license, explicit rights assertion, license clarity >=4, PII safety
  >=4, provenance >=3 and quality >=3. A high average cannot compensate for a
  failed legal/privacy gate."
  [manifest]
  (let [{:dataset/keys [license rights-asserted? scores id]}
        (validate-manifest manifest)
        dims (into {} (map (fn [k] [k (double (get scores k 0))]) score-dimensions))
        score (* 20.0 (/ (reduce + (vals dims)) (count score-dimensions)))
        gates {:training-license (contains? training-licenses license)
               :rights-asserted rights-asserted?
               :license-clarity (>= (:license-clarity dims) 4)
               :pii-safety (>= (:pii-safety dims) 4)
               :provenance (>= (:provenance dims) 3)
               :quality (>= (:quality dims) 3)}]
    {:dataset/id id :score score :dimensions dims :gates gates
     :training-eligible? (every? true? (vals gates))}))

(defn manifest-datoms
  "Project one dataset and its marketplace offer into canonical datoms."
  [{:dataset/keys [id cid title description source license format rows bytes
                   owner visibility
                   languages splits price-currency price-micros offer-kind]
    :as manifest}]
  (let [{:keys [score dimensions gates training-eligible?]}
        (suitability manifest)]
    (vec
     (concat
      [[id :dataset/cid cid]
       [id :dataset/title title]
       [id :dataset/source source]
       [id :dataset/owner owner]
       [id :dataset/visibility visibility]
       [id :dataset/license license]
       [id :dataset/format format]
       [id :dataset/rows rows]
       [id :dataset/bytes bytes]
       [id :dataset/llm-suitability score]
       [id :dataset/training-eligible? training-eligible?]]
      (when description [[id :dataset/description description]])
      (map (fn [language] [id :dataset/language language]) languages)
      (map (fn [[split n]] [id :dataset/split {:name split :rows n}]) splits)
      (map (fn [[dimension value]]
             [id :dataset/score {:dimension dimension :value value}])
           dimensions)
      (map (fn [[gate passed?]]
             [id :dataset/gate {:gate gate :passed? passed?}]) gates)
      (when offer-kind
        [[id :marketplace/offer-kind offer-kind]
         [id :marketplace/price-currency price-currency]
         [id :marketplace/price-micros price-micros]])))))

(defn publish!
  "Verify the manifest CID through host-supplied VERIFY, then transact catalog
  facts atomically. VERIFY receives the complete manifest."
  [s verify manifest]
  (validate-manifest manifest)
  (require! fn? verify :dataset/verifier-required {})
  (require! true? (boolean (verify manifest)) :dataset/cid-mismatch
            {:dataset/id (:dataset/id manifest)})
  (graph/transact!
   s {:tx-id (str "dataset:" (:dataset/id manifest) ":" (:dataset/cid manifest))
      :tx-data (mapv (fn [[e a v]] [:db/add e a v])
                     (manifest-datoms manifest))}))

(defn training-datasets
  "Return dataset id/title/score for every legally and technically eligible
  catalog entry, using the ordinary Datomic query surface."
  [s]
  (graph/q s {:language :datalog :find '[?dataset ?title ?score]
              :where '[[?dataset :dataset/training-eligible? true]
                       [?dataset :dataset/title ?title]
                       [?dataset :dataset/llm-suitability ?score]]}))
