(ns kotobase.marketplace-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.dataset :as dataset]
            [kotobase.graph-store :as graph]
            [kotobase.local :as local]
            [kotobase.marketplace :as market]))

(def manifest
  {:dataset/id "dataset/private-1" :dataset/cid "cid-private"
   :dataset/title "Private research corpus" :dataset/source "owner-upload"
   :dataset/owner "did:key:seller" :dataset/visibility :private
   :dataset/license :proprietary-training-license :dataset/rights-asserted? true
   :dataset/format :parquet :dataset/rows 10 :dataset/bytes 100
   :dataset/scores {:license-clarity 5 :provenance 5 :quality 4
                    :deduplication 4 :pii-safety 5 :toxicity-safety 4
                    :language-metadata 4 :format-readiness 5}})

(def offer
  {:offer/id "offer-1" :offer/dataset-id "dataset/private-1"
   :offer/seller "did:key:seller" :offer/status :active
   :offer/currency "JPY" :offer/price-micros 1000000
   :offer/license-kind :training :offer/commercial-terms-cid "cid-terms"})

(deftest private-data-stays-private-and-owner-controls-listing
  (let [s (local/local-store)]
    (dataset/publish! s (constantly true) manifest)
    (is (nil? (market/dataset-view s "did:key:stranger" "dataset/private-1")))
    (is (= "cid-private"
           (:dataset/cid (market/dataset-view s "did:key:seller"
                                              "dataset/private-1"))))
    (is (= :marketplace/private-dataset-not-listable
           (:problem (ex-data
                      (try (market/create-offer! s "did:key:seller" offer)
                           nil
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs cljs.core.ExceptionInfo) e e))))))
    (market/change-visibility! s "did:key:seller" "dataset/private-1"
                               :private :unlisted)
    (market/create-offer! s "did:key:seller" offer)
    (is (empty? (market/public-listings s)))
    (is (nil? (market/dataset-view s "did:key:buyer" "dataset/private-1")))
    (market/grant-entitlement!
     s (constantly true)
     {:entitlement/id "ent-1" :entitlement/offer-id "offer-1"
      :entitlement/buyer "did:key:buyer"
      :entitlement/payment-receipt-cid "receipt-1"})
    (is (= "cid-private"
           (:dataset/cid (market/dataset-view s "did:key:buyer"
                                              "dataset/private-1"))))
    (market/revoke-entitlement! s "did:key:seller" "ent-1")
    (is (nil? (market/dataset-view s "did:key:buyer" "dataset/private-1")))
    (market/change-visibility! s "did:key:seller" "dataset/private-1"
                               :unlisted :public)
    (is (= [["dataset/private-1" "Private research corpus"
             "offer-1" "JPY" 1000000]]
           (market/public-listings s)))))

(deftest seller-and-payment-boundaries-fail-closed
  (let [s (local/local-store)]
    (dataset/publish! s (constantly true) (assoc manifest :dataset/visibility :public))
    (is (= :marketplace/not-dataset-owner
           (:problem (ex-data
                      (try (market/create-offer! s "did:key:attacker" offer)
                           nil
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs cljs.core.ExceptionInfo) e e))))))
    (market/create-offer! s "did:key:seller" offer)
    (is (= :marketplace/payment-unverified
           (:problem (ex-data
                      (try (market/grant-entitlement!
                            s (constantly false)
                            {:entitlement/id "bad" :entitlement/offer-id "offer-1"
                             :entitlement/buyer "did:key:buyer"
                             :entitlement/payment-receipt-cid "fake"})
                           nil
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs cljs.core.ExceptionInfo) e e))))))))
