(ns kotobase.dataset-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.dataset :as dataset]
            [kotobase.graph-store :as graph]
            [kotobase.local :as local]))

(def curated
  {:dataset/id "dataset/commoncrawl-ja-clean-v1"
   :dataset/cid "bafy-curated"
   :dataset/title "Common Crawl Japanese Clean v1"
   :dataset/description "Deduplicated, PII-filtered, licensed training subset"
   :dataset/source "commoncrawl/WARC-derived"
   :dataset/owner "did:key:seller"
   :dataset/visibility :public
   :dataset/license :odc-by-1.0
   :dataset/rights-asserted? true
   :dataset/format :parquet
   :dataset/rows 1000 :dataset/bytes 100000
   :dataset/languages ["ja"] :dataset/splits {:train 900 :validation 100}
   :dataset/offer-kind :one-time :dataset/price-currency "JPY"
   :dataset/price-micros 500000000
   :dataset/scores {:license-clarity 4 :provenance 5 :quality 4
                    :deduplication 5 :pii-safety 5 :toxicity-safety 4
                    :language-metadata 5 :format-readiness 5}})

(deftest suitability-is-transparent-and-gated
  (let [result (dataset/suitability curated)]
    (is (= 92.5 (:score result)))
    (is (:training-eligible? result)))
  (testing "raw mixed-rights web crawl cannot be relabelled training-ready"
    (let [raw (-> curated
                  (assoc :dataset/id "dataset/raw-web"
                         :dataset/license :mixed-web
                         :dataset/rights-asserted? false)
                  (assoc-in [:dataset/scores :license-clarity] 1))
          result (dataset/suitability raw)]
      (is (false? (:training-eligible? result)))
      (is (false? (get-in result [:gates :training-license])))
      (is (false? (get-in result [:gates :rights-asserted]))))))

(deftest publish-makes-marketplace-data-datomic-queryable
  (let [s (local/local-store)]
    (dataset/publish! s (fn [m] (= "bafy-curated" (:dataset/cid m))) curated)
    (is (= [["dataset/commoncrawl-ja-clean-v1"
             "Common Crawl Japanese Clean v1" 92.5]]
           (dataset/training-datasets s)))
    (is (= [["JPY" 500000000]]
           (graph/q s {:language :datalog :find '[?currency ?price]
                       :where '[["dataset/commoncrawl-ja-clean-v1"
                                 :marketplace/price-currency ?currency]
                                ["dataset/commoncrawl-ja-clean-v1"
                                 :marketplace/price-micros ?price]]})))))

(deftest cid-verification-fails-closed
  (let [s (local/local-store)]
    (is (= :dataset/cid-mismatch
           (:problem
            (ex-data
             (try (dataset/publish! s (constantly false) curated)
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core.ExceptionInfo) e e))))))
    (is (empty? (graph/history s)))))
