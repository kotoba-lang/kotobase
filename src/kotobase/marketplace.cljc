(ns kotobase.marketplace
  "Multi-publisher dataset marketplace and private access boundary.

  Catalog metadata, offers and entitlements are datoms. Dataset bytes remain
  CID-addressed behind a host storage gateway; callers must pass `authorize`
  before the gateway reveals a CID or issues a signed download URL."
  (:require [clojure.string :as str]
            [kotobase.graph-store :as graph]))

(def visibilities #{:private :unlisted :public})
(def offer-statuses #{:draft :active :paused :retired})

(defn- require! [pred value problem data]
  (when-not (pred value)
    (throw (ex-info (name problem) (assoc data :problem problem))))
  value)

(defn offer-datoms
  [{:offer/keys [id dataset-id seller status currency price-micros
                 license-kind commercial-terms-cid]}]
  (doseq [[value problem]
          [[id :marketplace/offer-id-required]
           [dataset-id :marketplace/dataset-id-required]
           [seller :marketplace/seller-required]
           [currency :marketplace/currency-required]
           [commercial-terms-cid :marketplace/terms-required]]]
    (require! #(and (string? %) (not (str/blank? %))) value problem {}))
  (require! offer-statuses status :marketplace/offer-status-invalid {:status status})
  (require! #(and (integer? %) (not (neg? %))) price-micros
            :marketplace/price-invalid {:price-micros price-micros})
  (require! keyword? license-kind :marketplace/license-kind-required {})
  [[id :offer/dataset dataset-id]
   [id :offer/seller seller]
   [id :offer/status status]
   [id :offer/currency currency]
   [id :offer/price-micros price-micros]
   [id :offer/license-kind license-kind]
   [id :offer/commercial-terms-cid commercial-terms-cid]])

(defn- one [db-value e a]
  (some (fn [[entity attr value]]
          (when (and (= e entity) (= a attr)) value))
        (:datoms db-value)))

(defn create-offer!
  "Create an offer only when ACTOR owns DATASET-ID. Private datasets may have a
  draft offer but cannot become active until made unlisted/public."
  [s actor offer]
  (let [dbv (graph/db s)
        dataset-id (:offer/dataset-id offer)
        owner (one dbv dataset-id :dataset/owner)
        visibility (one dbv dataset-id :dataset/visibility)]
    (require! #(= actor %) owner :marketplace/not-dataset-owner
              {:actor actor :dataset/id dataset-id})
    (require! #(= actor %) (:offer/seller offer) :marketplace/seller-mismatch
              {:actor actor :offer/seller (:offer/seller offer)})
    (when (= :active (:offer/status offer))
      (require! #(not= :private %) visibility :marketplace/private-dataset-not-listable
                {:dataset/id dataset-id}))
    (graph/transact!
     s {:tx-id (str "offer:" (:offer/id offer))
        :tx-data (mapv (fn [[e a v]] [:db/add e a v]) (offer-datoms offer))})))

(defn change-visibility!
  [s actor dataset-id from to]
  (require! visibilities to :dataset/visibility-invalid {:visibility to})
  (let [dbv (graph/db s)]
    (require! #(= actor %) (one dbv dataset-id :dataset/owner)
              :marketplace/not-dataset-owner {:actor actor :dataset/id dataset-id})
    (require! #(= from %) (one dbv dataset-id :dataset/visibility)
              :marketplace/stale-visibility {:dataset/id dataset-id :expected from})
    (graph/transact!
     s {:tx-id (str "visibility:" dataset-id ":" (name from) "->" (name to))
        :tx-data [[:db/retract dataset-id :dataset/visibility from]
                  [:db/add dataset-id :dataset/visibility to]]})))

(defn grant-entitlement!
  "Grant access after the host verifies PAYMENT-RECEIPT. Free/manual grants use
  a separately audited verifier; this function never trusts caller claims."
  [s verify-payment {:entitlement/keys [id offer-id buyer payment-receipt-cid
                                        expires-at] :as entitlement}]
  (doseq [[value problem]
          [[id :marketplace/entitlement-id-required]
           [offer-id :marketplace/offer-id-required]
           [buyer :marketplace/buyer-required]
           [payment-receipt-cid :marketplace/payment-receipt-required]]]
    (require! #(and (string? %) (not (str/blank? %))) value problem {}))
  (require! fn? verify-payment :marketplace/payment-verifier-required {})
  (require! true? (boolean (verify-payment entitlement))
            :marketplace/payment-unverified {:offer/id offer-id :buyer buyer})
  (let [dbv (graph/db s)]
    (require! #(= :active %) (one dbv offer-id :offer/status)
              :marketplace/offer-not-active {:offer/id offer-id})
    (graph/transact!
     s {:tx-id (str "entitlement:" id)
        :tx-data (cond-> [[:db/add id :entitlement/offer offer-id]
                          [:db/add id :entitlement/buyer buyer]
                          [:db/add id :entitlement/status :active]
                          [:db/add id :entitlement/payment-receipt-cid
                           payment-receipt-cid]]
                   expires-at (conj [:db/add id :entitlement/expires-at expires-at]))})))

(defn revoke-entitlement!
  "Revoke access without deleting the commercial/audit history."
  [s seller entitlement-id]
  (let [dbv (graph/db s)
        offer-id (one dbv entitlement-id :entitlement/offer)]
    (require! #(= seller %) (one dbv offer-id :offer/seller)
              :marketplace/not-offer-seller {:seller seller :offer/id offer-id})
    (require! #(= :active %) (one dbv entitlement-id :entitlement/status)
              :marketplace/entitlement-not-active {:entitlement/id entitlement-id})
    (graph/transact!
     s {:tx-id (str "entitlement-revoke:" entitlement-id)
        :tx-data [[:db/retract entitlement-id :entitlement/status :active]
                  [:db/add entitlement-id :entitlement/status :revoked]]})))

(defn authorized?
  "Metadata/bytes access decision. Public is readable by anyone; unlisted and
  private require owner or an entitlement. Listing visibility is separate."
  [db-value actor dataset-id]
  (let [owner (one db-value dataset-id :dataset/owner)
        visibility (one db-value dataset-id :dataset/visibility)
        entitled? (some
                   (fn [[entitlement attr buyer]]
                     (when (and (= attr :entitlement/buyer) (= buyer actor))
                       (let [offer (one db-value entitlement :entitlement/offer)]
                         (and (= :active (one db-value entitlement :entitlement/status))
                              (= dataset-id (one db-value offer :offer/dataset))))))
                   (:datoms db-value))]
    (boolean (or (= actor owner) (= visibility :public) entitled?))))

(defn dataset-view
  "Return safe catalog facts. CID is included only after authorization. Private
  datasets are indistinguishable from missing datasets to other callers."
  [s actor dataset-id]
  (let [dbv (graph/db s)
        owner (one dbv dataset-id :dataset/owner)
        visibility (one dbv dataset-id :dataset/visibility)
        allowed? (authorized? dbv actor dataset-id)]
    (when (or allowed? (= visibility :public))
      (cond-> {:dataset/id dataset-id
               :dataset/title (one dbv dataset-id :dataset/title)
               :dataset/visibility visibility}
        allowed? (assoc :dataset/cid (one dbv dataset-id :dataset/cid)
                        :dataset/owner owner)))))

(defn public-listings
  "Only public datasets with active offers appear in marketplace discovery."
  [s]
  (graph/q s {:language :datalog :find '[?dataset ?title ?offer ?currency ?price]
              :where '[[?dataset :dataset/visibility :public]
                       [?dataset :dataset/title ?title]
                       [?offer :offer/dataset ?dataset]
                       [?offer :offer/status :active]
                       [?offer :offer/currency ?currency]
                       [?offer :offer/price-micros ?price]]}))
