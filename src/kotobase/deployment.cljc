(ns kotobase.deployment
  "Construction boundary that keeps an IStore and its verified deployment
  guarantees together.

  Existing kotobase.kotobase/kotobase-store remains transport-compatible.
  New applications should construct a deployment here and negotiate guarantees
  before entering durability- or finality-sensitive flows."
  (:require [kotobase.kotobase :as remote]
            [kotobase.local :as local]
            [kotobase.profile :as profile]))

(defn standalone
  "Create a standalone deployment. Optional STORE defaults to a fresh
  LocalStore. Its transactional capability is observed, never asserted."
  ([] (standalone (local/local-store)))
  ([store]
   {:kotobase.deployment/store store
    :kotobase.deployment/profile
    (profile/declare-profile {:profile :standalone :store store})}))

(defn remote
  "Create a remote deployment over injected XRPC.

  OPTS:
    :transactional?  construct the strong remote store implementation
    :profile         explicit profile id (default :standalone)
    :evidence        host-supplied evidence checked by kotobase.profile
    :requires        guarantees the caller needs immediately

  This function deliberately does not infer :managed from the kotobase.net
  hostname or from :transactional? alone."
  [xrpc {:keys [transactional? profile evidence requires]
         :or {profile :standalone evidence #{} requires #{}}}]
  (let [store (remote/kotobase-store xrpc {:transactional? transactional?})
        declaration (-> (profile/declare-profile {:profile profile
                                                  :store store
                                                  :evidence evidence})
                        (profile/require-guarantees requires))]
    {:kotobase.deployment/store store
     :kotobase.deployment/profile declaration}))

(defn store [deployment]
  (:kotobase.deployment/store deployment))

(defn declaration [deployment]
  (:kotobase.deployment/profile deployment))

(defn require-guarantees
  "Fail closed unless DEPLOYMENT supplies REQUESTED guarantees; returns the
  deployment unchanged for threading through an application constructor."
  [deployment requested]
  (profile/require-guarantees (declaration deployment) requested)
  deployment)
