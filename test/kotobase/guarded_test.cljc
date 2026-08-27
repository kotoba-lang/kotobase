(ns kotobase.guarded-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            ;; `the-unguarded-surface-is-declared-and-the-declaration-is-true`
            ;; asks `ns-publics` about `kotobase.core`, so this namespace has
            ;; to load it. Without the require it passed only because some
            ;; OTHER test namespace happened to load it first: run this one
            ;; alone and it threw `No namespace: kotobase.core found`.
            [kotobase.core]
            [kotobase.guarded :as guarded]))

(def schema
  {:invoice/id {:class :public}
   :invoice/amount {:class :internal}
   :invoice/payer-did {:class :personal :erasure-scope :person}
   :invoice/note {:class :restricted :erasure-scope :person}})

(def query
  {:find [:invoice/id :invoice/amount]
   :where [[:invoice/tenant-id "acme"]]
   :scope {:tenant "acme" :resources #{"INV-42"} :purpose :payment-review
           :basis "bafy-basis"}
   :limit 20})

(defn authorize [q]
  {:allowed? true :projection (set (:find q))
   :basis "bafy-basis" :policy-cid "bafy-policy"})

(def rows (fn [_ _] [{:invoice/id "INV-42" :invoice/amount 5000}]))
(def receipt! (fn [_] {:receipt/durable? true :receipt/cid "bafy-receipt"}))
(def grant {:granted #{:public :internal} :scopes #{}})

(defn- reason
  "Either namespace's refusal. The gate rejects with its own key, and a caller
  should not have to know which layer said no — only that nothing was served."
  [f]
  (let [data (ex-data (try (f) (catch #?(:clj clojure.lang.ExceptionInfo
                                        :cljs ExceptionInfo) e e)))]
    (or (:kotobase.guarded/reason data) (:kotobase.query/reason data))))

(deftest a-guarded-read-needs-all-four-or-none-of-it
  (let [request {:authorize! authorize :schema schema :grant grant
                 :query query :evaluate! rows :receipt! receipt!}]
    (testing "the whole thing together works"
      (let [{:keys [rows provenance]} (guarded/read! request)]
        (is (= [{:invoice/id "INV-42" :invoice/amount 5000}] rows))
        (is (= "bafy-receipt" (:receipt-cid provenance)))
        (is (= :payment-review (:purpose provenance)))))

    (testing "and each part alone is required"
      (is (= :missing-schema (reason #(guarded/read! (dissoc request :schema)))))
      (is (= :missing-grant (reason #(guarded/read! (dissoc request :grant)))))
      ;; these two are the gate's own refusals, surfacing through unchanged
      (is (= :missing-receipt-sink
             (reason #(guarded/read! (dissoc request :receipt!)))))
      (is (= :missing-evaluator
             (reason #(guarded/read! (dissoc request :evaluate!)))))
      ;; and a receipt sink that does not persist withholds the rows
      (is (= :receipt-not-durable
             (reason #(guarded/read! (assoc request :receipt!
                                            (fn [_] {:receipt/durable? false})))))))))

(deftest a-partly-classified-schema-is-the-dangerous-kind
  ;; the unclassified half reads as ordinary, so a schema that is partly
  ;; classified is refused rather than used for the part that is
  (is (= :schema-not-fully-classified
         (reason #(guarded/admit {:authorize! authorize
                                  :schema (assoc schema :invoice/secret {})
                                  :grant grant :query query})))))

(deftest a-policy-may-not-outrun-the-grant
  ;; policy and grant are separate authorities and the narrower wins. A policy
  ;; that allows an attribute the caller holds no class for is not a licence.
  (let [wider (assoc query :find [:invoice/id :invoice/payer-did])]
    (is (= :projection-outside-grant
           (reason #(guarded/admit {:authorize! authorize :schema schema
                                    :grant grant :query wider}))))
    ;; refused, not served narrowed: a caller that asked for something it may
    ;; not have should learn that rather than receive a quietly smaller answer
    (testing "and it is allowed once the grant carries both class and scope"
      (is (= #{:invoice/id :invoice/payer-did}
             (:projection (guarded/admit
                           {:authorize! authorize :schema schema
                            :grant {:granted #{:public :personal}
                                    :scopes #{:person}}
                            :query wider})))))))

(deftest the-answer-says-which-attributes-were-addresses
  ;; a caller that treats a content address as a value has read a hash and
  ;; reported it as content
  (let [wider (assoc query :find [:invoice/id :invoice/note])
        {:keys [inline by-reference]}
        (guarded/read! {:authorize! authorize :schema schema
                        :grant {:granted #{:public :restricted}
                                :scopes #{:person}}
                        :query wider
                        :evaluate! (fn [_ _] [{:invoice/id "INV-42"
                                               :invoice/note "bafy-note"}])
                        :receipt! receipt!})]
    (is (= #{:invoice/id} inline))
    (is (= #{:invoice/note} by-reference))))

(deftest the-unguarded-surface-is-declared-and-the-declaration-is-true
  ;; `it is unguarded` should be a fact with a location, not something a reader
  ;; infers. If core grows a read fn, this fails until somebody decides which
  ;; side it is on.
  (let [core-fns (into #{} (keep (fn [[sym var*]]
                                   (when (and (fn? @var*)
                                              (not (:macro (meta var*))))
                                     sym))
                                 (ns-publics 'kotobase.core)))
        reads guarded/unguarded-read-fns
        writes '#{open transact! commit-at!}]
    (is (= (into reads writes) core-fns)
        (str "kotobase.core's public surface changed: " core-fns
             ". A new read fn has to be declared unguarded or routed through "
             "kotobase.guarded."))
    (is (empty? (set/intersection reads writes)))))
